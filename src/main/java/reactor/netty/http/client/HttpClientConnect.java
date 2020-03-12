/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty.http.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.HttpOperations;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.InetSocketAddressUtil;
import reactor.netty.tcp.ProxyProvider;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpClient;
import reactor.netty.channel.ChannelMetricsHandler;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 */
final class HttpClientConnect extends HttpClient {

	final HttpTcpClient defaultClient;

	HttpClientConnect() {
		this(DEFAULT_TCP_CLIENT);
	}

	HttpClientConnect(TcpClient defaultClient) {
		Objects.requireNonNull(defaultClient, "tcpClient");
		this.defaultClient = new HttpTcpClient(defaultClient);
	}

	@Override
	protected TcpClient tcpConfiguration() {
		return defaultClient;
	}

	static final class HttpTcpClient extends TcpClient {

		final TcpClient defaultClient;

		HttpTcpClient(TcpClient defaultClient) {
			this.defaultClient = defaultClient;
		}

		@Override
		public Mono<? extends Connection> connect(Bootstrap b) {
			SslProvider ssl = SslProvider.findSslSupport(b);

			if (b.config()
				 .group() == null) {

				LoopResources loops = HttpResources.get();

				EventLoopGroup elg = loops.onClient(LoopResources.DEFAULT_NATIVE);

				b.group(elg)
				 .channel(loops.onChannel(elg));
			}

			HttpClientConfiguration conf = HttpClientConfiguration.getAndClean(b);
			ClientCookieEncoder cookieEncoder = conf.cookieEncoder;
			ClientCookieDecoder cookieDecoder = conf.cookieDecoder;
			BootstrapHandlers.channelOperationFactory(b,
					(ch, c, msg) -> new HttpClientOperations(ch, c, cookieEncoder, cookieDecoder));

			if (ssl != null) {
				if (ssl.getDefaultConfigurationType() == null) {
					switch (conf.protocols) {
						case HttpClientConfiguration.h11:
							ssl = SslProvider.updateDefaultConfiguration(ssl, SslProvider.DefaultConfigurationType.TCP);
							break;
						case HttpClientConfiguration.h2:
							ssl = SslProvider.updateDefaultConfiguration(ssl, SslProvider.DefaultConfigurationType.H2);
							break;
					}
				}
				SslProvider.setBootstrap(b, ssl);
			}

			SslProvider defaultSsl = ssl;

			if (conf.deferredConf != null) {
				return Mono.fromCallable(() -> new HttpClientConfiguration(conf))
				           .transform(conf.deferredConf)
				           .flatMap(c -> new MonoHttpConnect(b, c, defaultClient, defaultSsl));
			}

			return new MonoHttpConnect(b, conf, defaultClient, defaultSsl);
		}

		@Override
		public Bootstrap configure() {
			return defaultClient.configure();
		}

		@Nullable
		@Override
		public ProxyProvider proxyProvider() {
			return defaultClient.proxyProvider();
		}

		@Nullable
		@Override
		public SslProvider sslProvider() {
			return defaultClient.sslProvider();
		}
	}


	static final class MonoHttpConnect extends Mono<Connection> {

		final Bootstrap               bootstrap;
		final HttpClientConfiguration configuration;
		final TcpClient               tcpClient;
		final SslProvider             sslProvider;
		final ProxyProvider           proxyProvider;

		MonoHttpConnect(Bootstrap bootstrap,
				HttpClientConfiguration configuration,
				TcpClient tcpClient,
				@Nullable SslProvider sslProvider) {
			this.bootstrap = bootstrap;
			this.configuration = configuration;
			this.sslProvider = sslProvider;
			this.tcpClient = tcpClient;
			this.proxyProvider = ProxyProvider.findProxySupport(bootstrap);
		}

		@Override
		public void subscribe(CoreSubscriber<? super Connection> actual) {
			final Bootstrap b = bootstrap.clone();

			HttpClientHandler handler =
					new HttpClientHandler(configuration, b.config().remoteAddress(), sslProvider, proxyProvider);

			b.remoteAddress(handler);

			if (sslProvider != null) {
				if ((configuration.protocols & HttpClientConfiguration.h2c) == HttpClientConfiguration.h2c) {
					Operators.error(actual, new IllegalArgumentException("Configured " +
							"H2 Clear-Text protocol " +
							"with TLS. Use the non clear-text h2 protocol via " +
							"HttpClient#protocol or disable TLS " +
							"via HttpClient#tcpConfiguration(tcp -> tcp.noSSL())"));
					return;
				}
				if ((configuration.protocols & HttpClientConfiguration.h11) == HttpClientConfiguration.h11) {
					BootstrapHandlers.updateConfiguration(b, NettyPipeline.HttpInitializer,
							new Http1Initializer(handler.decoder, handler.compress, configuration.protocols));
//					return;
				}
//				if ((configuration.protocols & HttpClientConfiguration.h2) == HttpClientConfiguration.h2) {
//					BootstrapHandlers.updateConfiguration(b,
//							NettyPipeline.HttpInitializer,
//							new H2Initializer(
//									configuration.decoder.validateHeaders,
//									configuration.minCompressionSize,
//									compressPredicate(configuration.compressPredicate, configuration.minCompressionSize),
//									configuration.forwarded));
//					return;
//				}
			}
			else {
				if ((configuration.protocols & HttpClientConfiguration.h2) == HttpClientConfiguration.h2) {
					Operators.error(actual, new IllegalArgumentException(
							"Configured H2 protocol without TLS. Use a clear-text " +
							"h2 protocol via HttpClient#protocol or configure TLS via HttpClient#secure"));
					return;
				}
//				if ((configuration.protocols & HttpClientConfiguration.h11orH2c) == HttpClientConfiguration.h11orH2c) {
//					BootstrapHandlers.updateConfiguration(b,
//							NettyPipeline.HttpInitializer,
//							new Http1OrH2CleartextInitializer(configuration.decoder.maxInitialLineLength,
//									configuration.decoder.maxHeaderSize,
//									configuration.decoder.maxChunkSize,
//									configuration.decoder.validateHeaders,
//									configuration.decoder.initialBufferSize,
//									configuration.minCompressionSize,
//									compressPredicate(configuration.compressPredicate, configuration.minCompressionSize),
//									configuration.forwarded));
//					return;
//				}
				if ((configuration.protocols & HttpClientConfiguration.h11) == HttpClientConfiguration.h11) {
					BootstrapHandlers.updateConfiguration(b,
							NettyPipeline.HttpInitializer,
							new Http1Initializer(handler.decoder, handler.compress, configuration.protocols));
//					return;
				}
//				if ((configuration.protocols & HttpClientConfiguration.h2c) == HttpClientConfiguration.h2c) {
//					BootstrapHandlers.updateConfiguration(b,
//							NettyPipeline.HttpInitializer,
//							new H2CleartextInitializer(
//									conf.decoder.validateHeaders,
//									conf.minCompressionSize,
//									compressPredicate(conf.compressPredicate, conf.minCompressionSize),
//									conf.forwarded));
//					return;
//				}
			}

//			Operators.error(actual, new IllegalArgumentException("An unknown " +
//					"HttpClient#protocol " + "configuration has been provided: "+String.format("0x%x", configuration.protocols)));

			Mono.<Connection>create(sink -> {
				Bootstrap finalBootstrap;
				//append secure handler if needed
				if (handler.toURI.isSecure()) {
					if (sslProvider == null) {
						if ((configuration.protocols & HttpClientConfiguration.h2c) == HttpClientConfiguration.h2c) {
							sink.error(new IllegalArgumentException("Configured H2 " +
									"Clear-Text protocol without TLS while " +
									"trying to redirect to a secure address."));
							return;
						}
						//should not need to handle H2 case already checked outside of
						// this callback
						finalBootstrap = SslProvider.setBootstrap(b.clone(),
								HttpClientSecure.DEFAULT_HTTP_SSL_PROVIDER);
					}
					else {
						finalBootstrap = b.clone();
					}
				}
				else {
					if (sslProvider != null) {
						finalBootstrap = SslProvider.removeSslSupport(b.clone());
					}
					else {
						finalBootstrap = b.clone();
					}
				}

				BootstrapHandlers.connectionObserver(finalBootstrap,
						new HttpObserver(sink, handler)
						        .then(BootstrapHandlers.connectionObserver(finalBootstrap))
						        .then(new HttpIOHandlerObserver(sink, handler)));

				tcpClient.connect(finalBootstrap)
				         .subscribe(new TcpClientSubscriber(sink));

			}).retry(handler)
			  .subscribe(actual);
		}

		final static class TcpClientSubscriber implements CoreSubscriber<Connection> {

			final MonoSink<Connection> sink;

			TcpClientSubscriber(MonoSink<Connection> sink) {
				this.sink = sink;
			}

			@Override
			public void onSubscribe(Subscription s) {
				s.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(Connection connection) {
				sink.onCancel(connection);
			}

			@Override
			public void onError(Throwable throwable) {
				sink.error(throwable);
			}

			@Override
			public void onComplete() {

			}

			@Override
			public Context currentContext() {
				return sink.currentContext();
			}
		}
	}

	final static class HttpObserver implements ConnectionObserver {

		final MonoSink<Connection> sink;
		final HttpClientHandler handler;

		HttpObserver(MonoSink<Connection> sink, HttpClientHandler handler) {
			this.sink = sink;
			this.handler = handler;
		}

		@Override
		public Context currentContext() {
			return sink.currentContext();
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			if (error instanceof RedirectClientException) {
				if (log.isDebugEnabled()) {
					log.debug(format(connection.channel(), "The request will be redirected"));
				}
			}
			else if (AbortedException.isConnectionReset(error)) {
				if (log.isDebugEnabled()) {
					log.debug(format(connection.channel(), "The connection observed an error, " +
							"the request will be retried"), error);
				}
			}
			else if (log.isWarnEnabled()) {
				log.warn(format(connection.channel(), "The connection observed an error"), error);
			}
			sink.error(error);
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == HttpClientState.RESPONSE_RECEIVED) {
				sink.success(connection);
				return;
			}
			if (newState == State.CONFIGURED && HttpClientOperations.class == connection.getClass()) {
				handler.channel((HttpClientOperations) connection);
			}
		}
	}

	final static class HttpIOHandlerObserver implements ConnectionObserver {

		final MonoSink<Connection> sink;
		final HttpClientHandler handler;

		HttpIOHandlerObserver(MonoSink<Connection> sink, HttpClientHandler handler) {
			this.sink = sink;
			this.handler = handler;
		}

		@Override
		public Context currentContext() {
			return sink.currentContext();
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == ConnectionObserver.State.CONFIGURED
					&& HttpClientOperations.class == connection.getClass()) {
				if (log.isDebugEnabled()) {
					log.debug(format(connection.channel(), "Handler is being applied: {}"), handler);
				}

				Mono.defer(() -> Mono.fromDirect(handler.requestWithBody((HttpClientOperations) connection)))
				    .subscribe(connection.disposeSubscriber());
			}
		}
	}

	static final class HttpClientHandler extends SocketAddress
			implements Predicate<Throwable>, Supplier<SocketAddress> {

		final HttpMethod              method;
		final HttpHeaders             defaultHeaders;
		final BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>>
		                              handler;
		final boolean                 compress;
		final UriEndpointFactory      uriEndpointFactory;
		final String                  websocketProtocols;
		final int                     maxFramePayloadLength;
		final boolean                 websocketProxyPing;
		final ClientCookieEncoder     cookieEncoder;
		final ClientCookieDecoder     cookieDecoder;
		final BiPredicate<HttpClientRequest, HttpClientResponse>
		                              followRedirectPredicate;
		final Consumer<HttpClientRequest>
		                              redirectRequestConsumer;
		final HttpResponseDecoderSpec decoder;
		final ProxyProvider           proxyProvider;

		volatile UriEndpoint        toURI;
		volatile UriEndpoint        fromURI;
		volatile Supplier<String>[] redirectedFrom;
		volatile boolean            retried;

		@SuppressWarnings("unchecked")
		HttpClientHandler(HttpClientConfiguration configuration, @Nullable SocketAddress address,
				@Nullable SslProvider sslProvider, @Nullable ProxyProvider proxyProvider) {
			this.method = configuration.method;
			this.compress = configuration.acceptGzip;
			this.followRedirectPredicate = configuration.followRedirectPredicate;
			this.redirectRequestConsumer = configuration.redirectRequestConsumer;
			this.cookieEncoder = configuration.cookieEncoder;
			this.cookieDecoder = configuration.cookieDecoder;
			this.decoder = configuration.decoder;
			this.proxyProvider = proxyProvider;

			HttpHeaders defaultHeaders = configuration.headers;
			if (compress) {
				if (defaultHeaders == null) {
					this.defaultHeaders = new DefaultHttpHeaders();
				}
				else {
					this.defaultHeaders = defaultHeaders;
				}
				this.defaultHeaders.set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
			}
			else {
				this.defaultHeaders = defaultHeaders;
			}

			String baseUrl = configuration.baseUrl;

			String uri = configuration.uri;

			uri = uri == null ? "/" : uri;

			if (baseUrl != null && uri.startsWith("/")) {
				if (baseUrl.endsWith("/")) {
					baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
				}
				uri = baseUrl + uri;
			}

			Supplier<SocketAddress> addressSupplier;
			if (address instanceof Supplier) {
				addressSupplier = (Supplier<SocketAddress>) address;
			}
			else {
				addressSupplier = () -> address;
			}

			this.uriEndpointFactory =
					new UriEndpointFactory(addressSupplier, sslProvider != null, URI_ADDRESS_MAPPER);

			this.websocketProtocols = configuration.websocketSubprotocols;
			this.maxFramePayloadLength = configuration.websocketMaxFramePayloadLength;
			this.websocketProxyPing = configuration.websocketProxyPing;
			this.handler = configuration.body;
			this.toURI = uriEndpointFactory.createUriEndpoint(uri, configuration.websocketSubprotocols != null);
		}

		@Override
		public SocketAddress get() {
			SocketAddress address = toURI.getRemoteAddress();
			if (proxyProvider != null && !proxyProvider.shouldProxy(address) &&
					address instanceof InetSocketAddress) {
				address = InetSocketAddressUtil.replaceWithResolved((InetSocketAddress) address);
			}

			return address;
		}

		Publisher<Void> requestWithBody(HttpClientOperations ch) {
			try {
				ch.resourceUrl = toURI.toExternalForm();

				UriEndpoint uri = toURI;
				HttpHeaders headers = ch.getNettyRequest()
				                        .setUri(uri.getPathAndQuery())
				                        .setMethod(method)
				                        .setProtocolVersion(HttpVersion.HTTP_1_1)
				                        .headers();

				ch.path = HttpOperations.resolvePath(ch.uri());

				if (defaultHeaders != null) {
					headers.set(defaultHeaders);
				}

				if (!headers.contains(HttpHeaderNames.USER_AGENT)) {
					headers.set(HttpHeaderNames.USER_AGENT, USER_AGENT);
				}

				SocketAddress remoteAddress = uri.getRemoteAddress();
				if (!headers.contains(HttpHeaderNames.HOST) && remoteAddress instanceof InetSocketAddress) {
					headers.set(HttpHeaderNames.HOST,
					            resolveHostHeaderValue((InetSocketAddress) remoteAddress));
				}

				if (!headers.contains(HttpHeaderNames.ACCEPT)) {
					headers.set(HttpHeaderNames.ACCEPT, ALL);
				}

				ch.followRedirectPredicate(followRedirectPredicate);

				if (!Objects.equals(method, HttpMethod.GET) &&
						!Objects.equals(method, HttpMethod.HEAD) &&
						!Objects.equals(method, HttpMethod.DELETE) &&
						!headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
					ch.chunkedTransfer(true);
				}

				ch.listener().onStateChange(ch, HttpClientState.REQUEST_PREPARED);
				if (websocketProtocols != null) {
					Mono<Void> result =
							Mono.fromRunnable(() -> ch.withWebsocketSupport(websocketProtocols, maxFramePayloadLength, websocketProxyPing, compress));
					if (handler != null) {
						result = result.thenEmpty(Mono.fromRunnable(() -> Flux.concat(handler.apply(ch, ch))));
					}
					return result;
				}

				Consumer<HttpClientRequest> consumer = null;
				if (fromURI != null && !toURI.equals(fromURI)) {
					if (handler instanceof RedirectSendHandler) {
						headers.remove(HttpHeaderNames.EXPECT)
						       .remove(HttpHeaderNames.COOKIE)
						       .remove(HttpHeaderNames.AUTHORIZATION)
						       .remove(HttpHeaderNames.PROXY_AUTHORIZATION);
					}
					else {
						consumer = request ->
						        request.requestHeaders()
						               .remove(HttpHeaderNames.EXPECT)
						               .remove(HttpHeaderNames.COOKIE)
						               .remove(HttpHeaderNames.AUTHORIZATION)
						               .remove(HttpHeaderNames.PROXY_AUTHORIZATION);
					}
				}
				if (this.redirectRequestConsumer != null) {
					consumer = consumer != null ? consumer.andThen(this.redirectRequestConsumer) :
					                              this.redirectRequestConsumer;
				}
				ch.redirectRequestConsumer(consumer);
				return handler != null ? handler.apply(ch, ch) : ch.send();
			}
			catch (Throwable t) {
				return Mono.error(t);
			}
		}

		static String resolveHostHeaderValue(@Nullable InetSocketAddress remoteAddress) {
			if (remoteAddress != null) {
				String host = HttpUtil.formatHostnameForHttp(remoteAddress);
				int port = remoteAddress.getPort();
				if (port != 80 && port != 443) {
					host = host + ':' + port;
				}
				return host;
			}
			else {
				return "localhost";
			}
		}

		void redirect(String to) {
			Supplier<String>[] redirectedFrom = this.redirectedFrom;
			UriEndpoint from = toURI;
			if (to.startsWith("/")) {
				SocketAddress address = from.getRemoteAddress();
				if (address instanceof InetSocketAddress) {
					InetSocketAddress inetSocketAddress = (InetSocketAddress) address;
					toURI = uriEndpointFactory.createUriEndpoint(from, to,
							() -> URI_ADDRESS_MAPPER.apply(inetSocketAddress.getHostString(), inetSocketAddress.getPort()));
				}
				else {
					toURI = uriEndpointFactory.createUriEndpoint(from, to,
							() -> URI_ADDRESS_MAPPER.apply(from.host, from.port));
				}
			}
			else {
				toURI = uriEndpointFactory.createUriEndpoint(to, from.isWs());
			}
			fromURI = from;
			this.redirectedFrom = addToRedirectedFromArray(redirectedFrom, from);
		}

		@SuppressWarnings({"unchecked","rawtypes"})
		static Supplier<String>[] addToRedirectedFromArray(@Nullable Supplier<String>[] redirectedFrom,
				UriEndpoint from) {
			Supplier<String> fromUrlSupplier = from::toExternalForm;
			if (redirectedFrom == null) {
				return new Supplier[]{fromUrlSupplier};
			}
			else {
				Supplier<String>[] newRedirectedFrom = new Supplier[redirectedFrom.length + 1];
				System.arraycopy(redirectedFrom,
						0,
						newRedirectedFrom,
						0,
						redirectedFrom.length);
				newRedirectedFrom[redirectedFrom.length] = fromUrlSupplier;
				return newRedirectedFrom;
			}
		}

		void channel(HttpClientOperations ops) {
			Supplier<String>[] redirectedFrom = this.redirectedFrom;
			if (redirectedFrom != null) {
				ops.redirectedFrom = redirectedFrom;
			}
		}

		@Override
		public boolean test(Throwable throwable) {
			if (throwable instanceof RedirectClientException) {
				RedirectClientException re = (RedirectClientException) throwable;
				redirect(re.location);
				return true;
			}
			if (AbortedException.isConnectionReset(throwable) && !retried) {
				retried = true;
				redirect(toURI.toString());
				return true;
			}
			return false;
		}

		@Override
		public String toString() {
			return "{" + "uri=" + toURI + ", method=" + method + '}';
		}
	}


	static final class Http1Initializer implements BiConsumer<ConnectionObserver, Channel> {

		final HttpResponseDecoderSpec decoder;
		final boolean compress;
		final int protocols;

		Http1Initializer(HttpResponseDecoderSpec decoder, boolean compress, int protocols) {
			this.decoder = decoder;
			this.compress = compress;
			this.protocols = protocols;
		}

		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			ChannelPipeline p = channel.pipeline();
			p.addLast(NettyPipeline.HttpCodec,
			          new HttpClientCodec(decoder.maxInitialLineLength(),
			                              decoder.maxHeaderSize(),
			                              decoder.maxChunkSize(),
			                              decoder.failOnMissingResponse,
			                              decoder.validateHeaders(),
			                              decoder.initialBufferSize(),
			                              decoder.parseHttpAfterConnectRequest));

			if (compress) {
				p.addAfter(NettyPipeline.HttpCodec,
				           NettyPipeline.HttpDecompressor,
				           new HttpContentDecompressor());
			}

			ChannelHandler handler = p.get(NettyPipeline.ChannelMetricsHandler);
			if (handler != null) {
				ChannelMetricsRecorder channelMetricsRecorder = ((ChannelMetricsHandler) handler).recorder();
				if (channelMetricsRecorder instanceof HttpClientMetricsRecorder) {
					p.addLast(NettyPipeline.HttpMetricsHandler,
							new HttpClientMetricsHandler((HttpClientMetricsRecorder) channelMetricsRecorder));
				}
			}
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			Http1Initializer that = (Http1Initializer) o;
			return compress == that.compress &&
					protocols == that.protocols;
		}

		@Override
		public int hashCode() {
			return Objects.hash(compress, protocols);
		}
	}

//	static final class H2Initializer
//			implements BiConsumer<ConnectionObserver, Channel>  {
//
//		final HttpClientHandler handler;
//
//		H2Initializer(HttpClientHandler handler) {
//			this.handler = handler;
//		}
//
//		@Override
//		public void accept(ConnectionObserver listener, Channel channel) {
//			ChannelPipeline p = channel.pipeline();
//
//			Http2MultiplexCodecBuilder http2MultiplexCodecBuilder =
//					Http2MultiplexCodecBuilder.forClient(new Http2StreamInitializer(listener, forwarded))
//					                          .validateHeaders(validate)
//					                          .initialSettings(Http2Settings.defaultSettings());
//
//			if (p.get(NettyPipeline.LoggingHandler) != null) {
//				http2MultiplexCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG, "reactor.netty.http.client.h2.secured"));
//			}
//
//			p.addLast(NettyPipeline.HttpCodec, http2MultiplexCodecBuilder.build());
//		}
//	}

	@ChannelHandler.Sharable
	static final class HttpClientInitializer
			extends ChannelInboundHandlerAdapter
			implements BiConsumer<ConnectionObserver, Channel>, ChannelOperations.OnSetup,
			GenericFutureListener<Future<Http2StreamChannel>> {
		final HttpClientHandler handler;
		final DirectProcessor<Void> upgraded;

		HttpClientInitializer(HttpClientHandler handler) {
			this.handler = handler;
			this.upgraded = DirectProcessor.create();
		}

		@Override
		public void operationComplete(Future<Http2StreamChannel> future) {
			if (!future.isSuccess()) {
				upgraded.onError(future.cause());
			}
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
			if (ops != null) {
				ops.listener().onStateChange(ops, ConnectionObserver.State.CONFIGURED);
			}
			ctx.fireChannelActive();
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
			if (evt == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_SUCCESSFUL) {
				//upgraded to h2, read the next streams/settings from server

				ctx.channel()
				   .read();

				ctx.pipeline()
				   .remove(this);
			}
			else if (evt == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_REJECTED) {
				ctx.pipeline()
				   .remove(this);


				if (handler.compress) {
					ctx.pipeline()
					   .addLast(NettyPipeline.HttpDecompressor, new HttpContentDecompressor());
				}
			}
			ctx.fireUserEventTriggered(evt);
		}

		@Override
		public ChannelOperations<?, ?> create(Connection c, ConnectionObserver listener, @Nullable Object msg) {
			return new HttpClientOperations(c, listener, handler.cookieEncoder, handler.cookieDecoder);
		}

		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			ChannelPipeline p = channel.pipeline();

			if (p.get(NettyPipeline.SslHandler) != null) {
				p.addLast(new Http2ClientInitializer(listener, this));
			}
			else {
				HttpClientCodec httpClientCodec =
						new HttpClientCodec(handler.decoder.maxInitialLineLength(),
						                    handler.decoder.maxHeaderSize(),
						                    handler.decoder.maxChunkSize(),
						                    handler.decoder.failOnMissingResponse,
						                    handler.decoder.validateHeaders(),
						                    handler.decoder.initialBufferSize(),
						                    handler.decoder.parseHttpAfterConnectRequest);

				final Http2Connection connection = new DefaultHttp2Connection(false);
				HttpToHttp2ConnectionHandlerBuilder h2HandlerBuilder =
						new HttpToHttp2ConnectionHandlerBuilder()
						        .frameListener(new InboundHttp2ToHttpAdapterBuilder(connection)
						                               .maxContentLength(65536)
						                               .propagateSettings(true)
						                               .build())
						        .connection(connection);

				if (p.get(NettyPipeline.LoggingHandler) != null) {
					h2HandlerBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG, HttpClient.class));
				}

				p.addLast(NettyPipeline.HttpCodec, httpClientCodec);
//				TODO
//				ChannelHandler handler = p.get(NettyPipeline.TcpMetricsHandler);
//				if (handler != null) {
//					TcpMetricsHandler tcpMetrics = (TcpMetricsHandler) handler;
//					HttpClientMetricsHandler httpMetrics =
//							new HttpClientMetricsHandler(tcpMetrics.registry(),
//									tcpMetrics.name(),
//									"http",
//									tcpMetrics.remoteAddress());
//					p.addLast(NettyPipeline.HttpMetricsHandler, httpMetrics);
//				}
//				 .addLast(new HttpClientUpgradeHandler(httpClientCodec,
//				          new Http2ClientUpgradeCodec(h2HandlerBuilder.build()), 65536))
//				 .addLast(this);
//				ChannelOperations<?, ?> ops = HTTP_OPS.create(Connection.from(channel), listener,	null);
//				if (ops != null) {
//					ops.bind();
//					listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);
//				}

				upgraded.onComplete();
			}
		}
	}

	static final class Http2ClientInitializer extends ApplicationProtocolNegotiationHandler {

		final HttpClientInitializer parent;
		final ConnectionObserver listener;

		Http2ClientInitializer(ConnectionObserver listener, HttpClientInitializer parent) {
			super(ApplicationProtocolNames.HTTP_1_1);
			this.listener = listener;
			this.parent = parent;
		}

		@Override
		protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
			ChannelPipeline p = ctx.pipeline();

			if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
				Http2FrameCodecBuilder http2FrameCodecBuilder =
						Http2FrameCodecBuilder.forClient()
						                      .validateHeaders(true)
						                      .initialSettings(Http2Settings.defaultSettings());

				if (p.get(NettyPipeline.LoggingHandler) != null) {
					http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG, HttpClient.class));
				}

				p.addLast(http2FrameCodecBuilder.build())
				 .addLast(new Http2MultiplexHandler(new Http2StreamInitializer()));

				openStream(ctx.channel(), listener, parent);

				return;
			}

			if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
				p.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpCodec,
						new HttpClientCodec(parent.handler.decoder.maxInitialLineLength(),
						                    parent.handler.decoder.maxHeaderSize(),
						                    parent.handler.decoder.maxChunkSize(),
						                    parent.handler.decoder.failOnMissingResponse,
						                    parent.handler.decoder.validateHeaders(),
						                    parent.handler.decoder.initialBufferSize(),
						                    parent.handler.decoder.parseHttpAfterConnectRequest));

				if (parent.handler.compress) {
					p.addAfter(NettyPipeline.HttpCodec,
					           NettyPipeline.HttpDecompressor,
					           new HttpContentDecompressor());
				}

				ChannelHandler handler = p.get(NettyPipeline.ChannelMetricsHandler);
				if (handler != null) {
					ChannelMetricsRecorder channelMetricsRecorder = ((ChannelMetricsHandler) handler).recorder();
					if (channelMetricsRecorder instanceof HttpClientMetricsRecorder) {
						p.addLast(NettyPipeline.HttpMetricsHandler,
								new HttpClientMetricsHandler((HttpClientMetricsRecorder) channelMetricsRecorder));
					}
				}
//				ChannelOperations<?, ?> ops = HTTP_OPS.create(Connection.from(ctx.channel()), listener,	null);
//				if (ops != null) {
//					ops.bind();
//					listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);
//				}
				parent.upgraded.onComplete();
				return;
			}
			parent.upgraded.onError(new IllegalStateException("unknown protocol: " + protocol));
		}

	}

	@SuppressWarnings({"rawtypes", "FutureReturnValueIgnored"})
	static void openStream(Channel ch, ConnectionObserver listener,
			HttpClientInitializer initializer) {
		Http2StreamChannelBootstrap http2StreamChannelBootstrap =
				new Http2StreamChannelBootstrap(ch).handler(new ChannelInitializer() {
					@Override
					protected void initChannel(Channel ch) {
						ch.pipeline()
						  .addLast(new Http2StreamFrameToHttpObjectCodec(false));
						ChannelOperations.addReactiveBridge(ch,
								(conn, l, msg) -> new HttpClientOperations(conn, l,
										initializer.handler.cookieEncoder,
										initializer.handler.cookieDecoder),
								listener);
						if (log.isDebugEnabled()) {
							log.debug(format(ch, "Initialized HTTP/2 pipeline {}"), ch.pipeline());
						}
						initializer.upgraded.onComplete();
					}
				});

		//"FutureReturnValueIgnored" this is deliberate
		http2StreamChannelBootstrap.open()
		                           .addListener(initializer);
	}

	static final class Http2StreamInitializer extends ChannelInitializer<Channel> {

		Http2StreamInitializer() {
		}

		@Override
		protected void initChannel(Channel ch) {
			// TODO handle server pushes
		}
	}

	static final HttpClientConnect INSTANCE = new HttpClientConnect();
	static final AsciiString       ALL      = new AsciiString("*/*");
	static final Logger            log      = Loggers.getLogger(HttpClientConnect.class);


	static final BiFunction<String, Integer, InetSocketAddress> URI_ADDRESS_MAPPER =
			InetSocketAddressUtil::createUnresolved;
}
