/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
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
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2MultiplexCodecBuilder;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
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
import reactor.netty.FutureMono;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.InetSocketAddressUtil;
import reactor.netty.tcp.ProxyProvider;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpClient;
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

				SslContext sslContext = ssl != null ? ssl.getSslContext() : null;

				boolean useNative =
						LoopResources.DEFAULT_NATIVE && !(sslContext instanceof JdkSslContext);

				EventLoopGroup elg = loops.onClient(useNative);

				Integer maxConnections = (Integer) b.config().attrs().get(AttributeKey.valueOf("maxConnections"));

				if (maxConnections != null && maxConnections != -1 && elg instanceof Supplier) {
					EventLoopGroup delegate = (EventLoopGroup) ((Supplier) elg).get();
					b.group(delegate)
					 .channel(loops.onChannel(delegate));
				}
				else {
					b.group(elg)
					 .channel(loops.onChannel(elg));
				}
			}

			HttpClientConfiguration conf = HttpClientConfiguration.getAndClean(b);
			BootstrapHandlers.channelOperationFactory(b,
					(ch, c, msg) -> new HttpClientOperations(ch, c, conf.cookieEncoder, conf.cookieDecoder));

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
				@Nullable SslProvider               sslProvider) {
			this.bootstrap = bootstrap;
			this.configuration = configuration;
			this.sslProvider = sslProvider;
			this.tcpClient = tcpClient;
			this.proxyProvider = ProxyProvider.findProxySupport(bootstrap);
		}

		@Override
		public void subscribe(CoreSubscriber<? super Connection> actual) {
			final Bootstrap b = bootstrap.clone();

			HttpClientHandler handler = new HttpClientHandler(configuration, b.config()
			                                                                  .remoteAddress(), sslProvider, proxyProvider);

			b.remoteAddress(handler);

			if (sslProvider != null) {
				if ((configuration.protocols & HttpClientConfiguration.h2c) == HttpClientConfiguration.h2c) {
					Operators.error(actual, new IllegalArgumentException("Configured" +
							" H2 Clear-Text protocol " +
							"with TLS. Use the non clear-text h2 protocol via " +
							"HttpClient#protocol or disable TLS" +
							" via HttpClient#tcpConfiguration(tcp -> tcp.noSSL())"));
					return;
				}
				if ((configuration.protocols & HttpClientConfiguration.h11) == HttpClientConfiguration.h11) {
					BootstrapHandlers.updateConfiguration(b, NettyPipeline.HttpInitializer,
							new Http1Initializer(handler, configuration.protocols));
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
							"Configured H2 protocol without TLS. Use" + " a clear-text " + "h2 protocol via HttpClient#protocol or configure TLS" + " via HttpClient#secure"));
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
							new Http1Initializer(handler, configuration.protocols));
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
				if (handler.activeURI.isSecure()) {
					if (sslProvider == null) {
						if ((configuration.protocols & HttpClientConfiguration.h2c) == HttpClientConfiguration.h2c) {
							sink.error(new IllegalArgumentException("Configured H2 " +
									"Clear-Text" + " protocol" + " without TLS while " +
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
				handler.channel(connection.channel());
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
					log.debug(format(connection.channel(), "Handler is being applied: {}"),
							handler);
				}

				Mono.defer(() -> Mono.fromDirect(handler.requestWithBody((HttpClientOperations)connection)))
				    .subscribe(connection.disposeSubscriber());
			}
		}
	}

	static final class HttpClientHandler extends SocketAddress
			implements Predicate<Throwable>, Supplier<SocketAddress> {

		final HttpMethod         method;
		final HttpHeaders        defaultHeaders;
		final BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>>
		                         handler;
		final boolean            compress;
		final Boolean            chunkedTransfer;
		final UriEndpointFactory uriEndpointFactory;
		final String             websocketProtocols;
		final int                maxFramePayloadLength;

		final ClientCookieEncoder cookieEncoder;
		final ClientCookieDecoder cookieDecoder;

		final BiPredicate<HttpClientRequest, HttpClientResponse> followRedirectPredicate;

		final HttpResponseDecoderSpec decoder;

		final ProxyProvider proxyProvider;

		volatile UriEndpoint        activeURI;
		volatile Supplier<String>[] redirectedFrom;
		volatile boolean retried;

		@SuppressWarnings("unchecked")
		HttpClientHandler(HttpClientConfiguration configuration, @Nullable SocketAddress address,
				@Nullable SslProvider sslProvider, @Nullable ProxyProvider proxyProvider) {
			this.method = configuration.method;
			this.compress = configuration.acceptGzip;
			this.followRedirectPredicate = configuration.followRedirectPredicate;
			this.chunkedTransfer = configuration.chunkedTransfer;
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
				this.defaultHeaders.set(HttpHeaderNames.ACCEPT_ENCODING,
				                        HttpHeaderValues.GZIP);
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
			this.handler = configuration.body;
			this.activeURI = uriEndpointFactory.createUriEndpoint(uri, configuration.websocketSubprotocols != null);
		}

		@Override
		public SocketAddress get() {
			SocketAddress address = activeURI.getRemoteAddress();
			if (proxyProvider != null && !proxyProvider.shouldProxy(address) &&
					address instanceof InetSocketAddress) {
				address = InetSocketAddressUtil.replaceWithResolved((InetSocketAddress) address);
			}

			return address;
		}

		Publisher<Void> requestWithBody(HttpClientOperations ch) {
			try {
				UriEndpoint uri = activeURI;
				HttpHeaders headers = ch.getNettyRequest()
				                        .setUri(uri.getPathAndQuery())
				                        .setMethod(method)
				                        .setProtocolVersion(HttpVersion.HTTP_1_1)
				                        .headers();

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

				if (chunkedTransfer == null) {
					if (Objects.equals(method, HttpMethod.GET) ||
							Objects.equals(method, HttpMethod.HEAD) ||
							Objects.equals(method, HttpMethod.DELETE)) {
						ch.chunkedTransfer(false);
					} else if (!headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
						ch.chunkedTransfer(true);
					}
				}
				else {
					ch.chunkedTransfer(chunkedTransfer);
				}

				if (handler != null) {
					if (websocketProtocols != null) {
						WebsocketUpgradeOutbound wuo = new WebsocketUpgradeOutbound(ch, websocketProtocols, maxFramePayloadLength, compress);
						return Flux.concat(handler.apply(ch, wuo), wuo.then());
					}
					else {
						return handler.apply(ch, ch);
					}
				}
				else {
					if (websocketProtocols != null) {
						return Mono.fromRunnable(() -> ch.withWebsocketSupport(websocketProtocols, maxFramePayloadLength, compress));
					}
					else {
						return ch.send();
					}
				}
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
			UriEndpoint from = activeURI;
			if (to.startsWith("/")) {
				activeURI = uriEndpointFactory.createUriEndpoint(to, from.isWs(),
						() -> URI_ADDRESS_MAPPER.apply(from.host, from.port));
			}
			else {
				activeURI = uriEndpointFactory.createUriEndpoint(to, from.isWs());
			}
			this.redirectedFrom = addToRedirectedFromArray(redirectedFrom, from);
		}

		@SuppressWarnings("unchecked")
		static Supplier<String>[] addToRedirectedFromArray(@Nullable Supplier<String>[] redirectedFrom,
				UriEndpoint from) {
			Supplier<String> fromUrlSupplier = from::toExternalForm;
			if (redirectedFrom == null) {
				return new Supplier[]{fromUrlSupplier};
			}
			else {
				Supplier<String>[] newRedirectedFrom =
						new Supplier[redirectedFrom.length + 1];
				System.arraycopy(redirectedFrom,
						0,
						newRedirectedFrom,
						0,
						redirectedFrom.length);
				newRedirectedFrom[redirectedFrom.length] = fromUrlSupplier;
				return newRedirectedFrom;
			}
		}

		void channel(Channel channel) {
			Supplier<String>[] redirectedFrom = this.redirectedFrom;
			if (redirectedFrom != null) {
				ChannelOperations<?, ?> ops = ChannelOperations.get(channel);
				if (ops instanceof HttpClientOperations) {
					((HttpClientOperations) ops).redirectedFrom = redirectedFrom;
				}
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
				redirect(activeURI.toString());
				return true;
			}
			return false;
		}

		@Override
		public String toString() {
			return "{" + "uri=" + activeURI + ", method=" + method + '}';
		}
	}

	static final class WebsocketUpgradeOutbound implements NettyOutbound {

		final HttpClientOperations ch;
		final Mono<Void>           m;
		final String               websocketProtocols;

		WebsocketUpgradeOutbound(HttpClientOperations ch, String websocketProtocols, int websocketMaxFramePayloadLength,
				boolean compress) {
			this.ch = ch;
			this.websocketProtocols = websocketProtocols;
			this.m = Mono.fromRunnable(() -> ch.withWebsocketSupport(websocketProtocols, websocketMaxFramePayloadLength, compress));
		}

		@Override
		public ByteBufAllocator alloc() {
			return ch.alloc();
		}

		@Override
		public NettyOutbound sendObject(Publisher<?> dataStream) {
			return then(FutureMono.deferFuture(() -> ch.channel()
			                                           .writeAndFlush(dataStream)));
		}

		@Override
		public NettyOutbound sendObject(Object message) {
			return then(FutureMono.deferFuture(() -> ch.channel()
			                                           .writeAndFlush(message)),
			            () -> ReactorNetty.safeRelease(message));
		}

		@Override
		public <S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
				BiFunction<? super Connection, ? super S, ?> mappedInput,
				Consumer<? super S> sourceCleanup) {
			Objects.requireNonNull(sourceInput, "sourceInput");
			Objects.requireNonNull(mappedInput, "mappedInput");
			Objects.requireNonNull(sourceCleanup, "sourceCleanup");

			return then(Mono.using(
					sourceInput,
					s -> FutureMono.from(ch.channel()
					                       .writeAndFlush(mappedInput.apply(ch, s))),
					sourceCleanup)
			);
		}

		@Override
		public NettyOutbound withConnection(Consumer<? super Connection> withConnection) {
			return ch.withConnection(withConnection);
		}

		@Override
		public Mono<Void> then() {
			if (!ch.channel().isActive()) {
				return Mono.error(new AbortedException("Connection has been closed BEFORE response"));
			}

			return m;
		}
	}

	static final class Http1Initializer
			implements BiConsumer<ConnectionObserver, Channel>  {

		final HttpClientHandler handler;
		final int protocols;

		Http1Initializer(HttpClientHandler handler, int protocols) {
			this.handler = handler;
			this.protocols = protocols;
		}

		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			channel.pipeline()
			       .addLast(NettyPipeline.HttpCodec,
			                new HttpClientCodec(handler.decoder.maxInitialLineLength(),
			                                    handler.decoder.maxHeaderSize(),
			                                    handler.decoder.maxChunkSize(),
			                                    handler.decoder.failOnMissingResponse,
			                                    handler.decoder.validateHeaders(),
			                                    handler.decoder.initialBufferSize(),
			                                    handler.decoder.parseHttpAfterConnectRequest));

			if (handler.compress) {
				channel.pipeline()
				       .addAfter(NettyPipeline.HttpCodec,
						       NettyPipeline.HttpDecompressor,
						       new HttpContentDecompressor());
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
			return handler.compress == that.handler.compress &&
					protocols == that.protocols;
		}

		@Override
		public int hashCode() {
			return Objects.hash(handler.compress, protocols);
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
			implements BiConsumer<ConnectionObserver, Channel>, ChannelOperations
			.OnSetup, GenericFutureListener<Future<Http2StreamChannel>> {
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
			if(evt == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_SUCCESSFUL) {
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
				HttpToHttp2ConnectionHandlerBuilder h2HandlerBuilder = new
						HttpToHttp2ConnectionHandlerBuilder()
						.frameListener(new InboundHttp2ToHttpAdapterBuilder(connection)
										.maxContentLength(65536)
										.propagateSettings(true)
										.build())
						.connection(connection);

				if (p.get(NettyPipeline.LoggingHandler) != null) {
					h2HandlerBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG, HttpClient.class));
				}

				p.addLast(NettyPipeline.HttpCodec, httpClientCodec);
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
				Http2MultiplexCodecBuilder http2MultiplexCodecBuilder =
						Http2MultiplexCodecBuilder.forClient(new Http2StreamInitializer())
						                          .initialSettings(Http2Settings.defaultSettings());

				if (p.get(NettyPipeline.LoggingHandler) != null) {
					http2MultiplexCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG, HttpClient.class));
				}

				p.addLast(http2MultiplexCodecBuilder.build());

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

	static void openStream(Channel ch, ConnectionObserver listener,
			HttpClientInitializer initializer) {
		Http2StreamChannelBootstrap http2StreamChannelBootstrap =
				new Http2StreamChannelBootstrap(ch).handler(new ChannelInitializer() {
					@Override
					protected void initChannel(Channel ch) {
						ch.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(false));
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

		http2StreamChannelBootstrap.open()
		                           .addListener(initializer);
	}

	static final class Http2StreamInitializer extends ChannelInitializer<Channel> {

		Http2StreamInitializer() {
		}

		@Override
		protected void initChannel(Channel ch) {
			System.out.println("test");
			// TODO handle server pushes
		}
	}

	static final HttpClientConnect INSTANCE = new HttpClientConnect();
	static final AsciiString       ALL      = new AsciiString("*/*");
	static final Logger            log      = Loggers.getLogger(HttpClientConnect.class);


	static final BiFunction<String, Integer, InetSocketAddress> URI_ADDRESS_MAPPER =
			InetSocketAddressUtil::createUnresolved;
}
