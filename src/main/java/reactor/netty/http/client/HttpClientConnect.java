/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
import io.netty.handler.codec.http.HttpVersion;
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
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
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

import static reactor.netty.LogFormatter.format;

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

	static void channelFactoryAndLoops(Bootstrap b) {
		if (b.config()
		     .group() == null) {

			LoopResources loops = HttpResources.get();

			SslProvider ssl = SslProvider.findSslSupport(b);
			SslContext sslContext = ssl != null ? ssl.getSslContext() : null;

			boolean useNative =
					LoopResources.DEFAULT_NATIVE && !(sslContext instanceof JdkSslContext);

			EventLoopGroup elg = loops.onClient(useNative);

			b.group(elg)
			 .channel(loops.onChannel(elg));
		}
	}

	static final class HttpTcpClient extends TcpClient {

		final TcpClient defaultClient;

		HttpTcpClient(TcpClient defaultClient) {
			this.defaultClient = defaultClient;
		}

		@Override
		public Mono<? extends Connection> connect(Bootstrap b) {
			channelFactoryAndLoops(b);
			BootstrapHandlers.channelOperationFactory(b, HTTP_OPS);
			HttpClientConfiguration conf = HttpClientConfiguration.getAndClean(b);

			if (conf.deferredUri != null) {
				return conf.deferredUri.flatMap(uri ->
						new MonoHttpConnect(b, new HttpClientConfiguration(conf, uri), defaultClient));
			}

			return new MonoHttpConnect(b, conf, defaultClient);
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


	static final ChannelOperations.OnSetup HTTP_OPS =
			(ch, c, msg) -> new HttpClientOperations(ch, c);

	static final class MonoHttpConnect extends Mono<Connection> {

		final Bootstrap               bootstrap;
		final HttpClientConfiguration configuration;
		final TcpClient               tcpClient;

		MonoHttpConnect(Bootstrap bootstrap,
				HttpClientConfiguration configuration,
				TcpClient tcpClient) {
			this.bootstrap = bootstrap;
			this.configuration = configuration;
			this.tcpClient = tcpClient;
		}

		@Override
		public void subscribe(CoreSubscriber<? super Connection> actual) {
			final Bootstrap b = bootstrap.clone();

			SslProvider ssl = SslProvider.findSslSupport(b);
			if (ssl != null) {
				SslProvider.updateSslSupport(b,
						SslProvider.addHandlerConfigurator(ssl,
								HttpClientSecure.DEFAULT_HOSTNAME_VERIFICATION));
			}

			HttpClientHandler handler = new HttpClientHandler(configuration, b.config()
			                                                                  .remoteAddress(), ssl);

			b.remoteAddress(handler);

			BootstrapHandlers.updateConfiguration(b,
					NettyPipeline.HttpInitializer,
					(listener, channel) -> {
						channel.pipeline()
						       .addLast(NettyPipeline.HttpCodec, new HttpClientCodec());

						if (handler.compress) {
							channel.pipeline()
							       .addAfter(NettyPipeline.HttpCodec,
							                 NettyPipeline.HttpDecompressor,
							                 new HttpContentDecompressor());
						}

					});

			Mono.<Connection>create(sink -> {
				Bootstrap finalBootstrap;
				//append secure handler if needed
				if (handler.activeURI.isSecure()) {
					if (ssl == null) {
						finalBootstrap = SslProvider.updateSslSupport(b.clone(),
								HttpClientSecure.DEFAULT_HTTP_SSL_PROVIDER);
					}
					else {
						finalBootstrap = b.clone();
					}
				}
				else {
					if (ssl != null) {
						finalBootstrap = SslProvider.removeSslSupport(b.clone());
					}
					else {
						finalBootstrap = b.clone();
					}
				}

				BootstrapHandlers.connectionObserver(finalBootstrap,
						BootstrapHandlers.connectionObserver(finalBootstrap).then(new HttpObserver(sink, handler)));

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
			sink.error(error);
		}

		@Override
		@SuppressWarnings("unchecked")
		public void onStateChange(Connection connection, State newState) {
			if (newState == HttpClientOperations.RESPONSE_RECEIVED) {
				sink.success(connection);
				return;
			}
			if (newState == State.CONFIGURED && HttpClientOperations.class == connection.getClass()) {
				if (log.isDebugEnabled()) {
					log.debug(format(connection.channel(), "Handler is being applied: {}"),
							handler);
				}
				handler.channel(connection.channel());

//				Mono.fromDirect(initializer.upgraded)
//				    .then(Mono.defer(() -> Mono.fromDirect(handler.requestWithbody((HttpClientOperations)connection))))
//				    .subscribe(connection.disposeSubscriber());
				Mono.defer(() -> Mono.fromDirect(handler.requestWithbody((HttpClientOperations)connection)))
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
		final boolean            followRedirect;
		final boolean            compress;
		final Boolean            chunkedTransfer;
		final UriEndpointFactory uriEndpointFactory;
		final String             websocketProtocols;

		volatile UriEndpoint        activeURI;
		volatile Supplier<String>[] redirectedFrom;

		@SuppressWarnings("unchecked")
		HttpClientHandler(HttpClientConfiguration configuration, @Nullable SocketAddress address, @Nullable SslProvider sslProvider) {
			this.method = configuration.method;
			this.compress = configuration.acceptGzip;
			this.followRedirect = configuration.followRedirect;
			this.chunkedTransfer = configuration.chunkedTransfer;

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
					baseUrl = baseUrl.substring(0, baseUrl.length() - 2);
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
			this.handler = configuration.body;
			this.activeURI = uriEndpointFactory.createUriEndpoint(uri, configuration.websocketSubprotocols != null);
		}

		@Override
		public SocketAddress get() {
			return activeURI.getRemoteAddress();
		}

		public Publisher<Void> requestWithbody(HttpClientOperations ch) {
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

				if (!headers.contains(HttpHeaderNames.HOST)) {
					headers.set(HttpHeaderNames.HOST,
					            resolveHostHeaderValue(ch.address()));
				}

				if (!headers.contains(HttpHeaderNames.ACCEPT)) {
					headers.set(HttpHeaderNames.ACCEPT, ALL);
				}

				ch.followRedirect(followRedirect);

				if (Objects.equals(method, HttpMethod.GET) ||
						Objects.equals(method, HttpMethod.HEAD) ||
								Objects.equals(method, HttpMethod.DELETE)) {
					ch.chunkedTransfer(false);
				}
				else {
					ch.chunkedTransfer(true);
				}

				if (chunkedTransfer != null) {
					ch.chunkedTransfer(chunkedTransfer);
				}

				if (handler != null) {
					if (websocketProtocols != null) {
						WebsocketUpgradeOutbound wuo = new WebsocketUpgradeOutbound(ch, websocketProtocols);
						return Flux.concat(handler.apply(ch, wuo), wuo.then());
					}
					else {
						return handler.apply(ch, ch);
					}
				}
				else {
					if (websocketProtocols != null) {
						return Mono.fromRunnable(() -> ch.withWebsocketSupport(websocketProtocols));
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

		private static String resolveHostHeaderValue(@Nullable InetSocketAddress remoteAddress) {
			if (remoteAddress != null) {
				String host = remoteAddress.getHostString();
				if (VALID_IPV6_PATTERN.matcher(host)
				                      .matches()) {
					host = "[" + host + "]";
				}
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
		private static Supplier<String>[] addToRedirectedFromArray(@Nullable Supplier<String>[] redirectedFrom,
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
				channel.attr(HttpClientOperations.REDIRECT_ATTR_KEY)
				       .set(redirectedFrom);
			}
		}

		@Override
		public boolean test(Throwable throwable) {
			if (throwable instanceof RedirectClientException) {
				RedirectClientException re = (RedirectClientException) throwable;
				redirect(re.location);
				return true;
			}
			if (AbortedException.isConnectionReset(throwable)) {
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

		WebsocketUpgradeOutbound(HttpClientOperations ch, String websocketProtocols) {
			this.ch = ch;
			this.websocketProtocols = websocketProtocols;
			this.m = Mono.fromRunnable(() -> ch.withWebsocketSupport(websocketProtocols));
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
			                                           .writeAndFlush(message)));
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
			return m;
		}
	}

	static final Pattern VALID_IPV6_PATTERN;

	static {
		try {
			VALID_IPV6_PATTERN = Pattern.compile("([0-9a-f]{1,4}:){7}([0-9a-f]){1,4}",
			                                     Pattern.CASE_INSENSITIVE);
		}
		catch (PatternSyntaxException e) {
			throw new IllegalStateException("Impossible");
		}
	}

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
			ChannelOperations<?, ?> ops = Connection.from(ctx.channel()).as(ChannelOperations.class);
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
			return new HttpClientOperations(c, listener);
		}

		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			ChannelPipeline p = channel.pipeline();

			if (p.get(NettyPipeline.SslHandler) != null) {
				p.addLast(new Http2ClientInitializer(listener, this));
			}
			else {
				HttpClientCodec httpClientCodec = new HttpClientCodec();

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

				openStream(ctx.channel(), listener, parent.upgraded, parent);

				return;
			}

			if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
				p.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpCodec, new HttpClientCodec());

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
			DirectProcessor<Void> upgraded, GenericFutureListener<Future<Http2StreamChannel>> onStreamOpen) {
		Http2StreamChannelBootstrap http2StreamChannelBootstrap =
				new Http2StreamChannelBootstrap(ch).handler(new ChannelInitializer() {
					@Override
					protected void initChannel(Channel ch) {
						ch.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(false));
						ChannelOperations.addReactiveBridge(ch, HTTP_OPS, listener);
						if (log.isDebugEnabled()) {
							log.debug(format(ch, "Initialized HTTP/2 pipeline {}"), ch.pipeline());
						}
						upgraded.onComplete();
					}
				});

		http2StreamChannelBootstrap.open()
		                           .addListener(onStreamOpen);
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
	static final Logger            log      =
			Loggers.getLogger(HttpClientFinalizer.class);


	static final BiFunction<String, Integer, InetSocketAddress> URI_ADDRESS_MAPPER =
			InetSocketAddressUtil::createUnresolved;
}
