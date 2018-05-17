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

package reactor.ipc.netty.http.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.Callable;
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
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.AbortedException;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.tcp.InetSocketAddressUtil;
import reactor.ipc.netty.tcp.ProxyProvider;
import reactor.ipc.netty.tcp.SslProvider;
import reactor.ipc.netty.tcp.TcpClient;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

/**
 * @author Stephane Maldini
 */
final class HttpClientConnect extends HttpClient {

	static final HttpClientConnect INSTANCE = new HttpClientConnect();
	static final AsciiString       ALL      = new AsciiString("*/*");
	static final Logger            log      =
			Loggers.getLogger(HttpClientFinalizer.class);

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

	static void channelFactoryAndLoops(TcpClient tcpClient, Bootstrap b) {
		if (b.config()
		     .group() == null) {

			LoopResources loops = HttpResources.get();

			boolean useNative =
					LoopResources.DEFAULT_NATIVE && !(tcpClient.sslContext() instanceof JdkSslContext);

			EventLoopGroup elg = loops.onClient(useNative);

			b.group(elg)
			 .channel(loops.onChannel(elg));
		}
	}

	static final BiFunction<String, Integer, InetSocketAddress> URI_ADDRESS_MAPPER =
			InetSocketAddressUtil::createUnresolved;

	static final class HttpTcpClient extends TcpClient {

		final TcpClient defaultClient;

		HttpTcpClient(TcpClient defaultClient) {
			this.defaultClient = defaultClient;
		}

		@Override
		public Mono<? extends Connection> connect(Bootstrap b) {
			channelFactoryAndLoops(defaultClient, b);
			BootstrapHandlers.channelOperationFactory(b, HTTP_OPS);
			return new MonoHttpConnect(b, defaultClient);
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
		public SslContext sslContext() {
			return defaultClient.sslContext();
		}
	}


	static final ChannelOperations.OnSetup HTTP_OPS =
			(ch, c, msg) -> new HttpClientOperations(ch, c).bind();

	static final class MonoHttpConnect extends Mono<Connection> {

		final Bootstrap               bootstrap;
		final HttpClientConfiguration configuration;
		final TcpClient               tcpClient;

		MonoHttpConnect(Bootstrap bootstrap,
				TcpClient tcpClient) {
			this.bootstrap = bootstrap;
			this.configuration = HttpClientConfiguration.getAndClean(bootstrap);
			this.tcpClient = tcpClient;
		}

		@Override
		public void subscribe(CoreSubscriber<? super Connection> actual) {
			final Bootstrap b = bootstrap.clone();

			HttpClientHandler handler = new HttpClientHandler(b.config()
			                                                   .remoteAddress(), configuration);

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

			b.remoteAddress(handler);

			Mono.<Connection>create(sink -> {
				Bootstrap finalBootstrap;
				//append secure handler if needed
				if (handler.activeURI.isSecure()) {
					finalBootstrap = SslProvider.addDefaultSslSupport(b.clone());
				}
				else {
					finalBootstrap = b.clone();
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
					log.debug("{} handler is being applied: {}",
							connection.channel(), handler);
				}
				handler.channel(connection.channel());

				ChannelOperations<?, ?> ops =
						(ChannelOperations<?, ?>) connection;

				Mono.fromDirect(handler.apply(ops, ops))
				    .subscribe(connection.disposeSubscriber());
			}
		}
	}

	static final class HttpClientHandler extends SocketAddress
			implements BiFunction<NettyInbound, NettyOutbound, Publisher<Void>>,
			           Predicate<Throwable>, Supplier<SocketAddress> {

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
		HttpClientHandler(@Nullable SocketAddress address, HttpClientConfiguration configuration) {
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
					new UriEndpointFactory(addressSupplier, false, URI_ADDRESS_MAPPER);

			this.websocketProtocols = configuration.websocketSubprotocols;
			this.handler = configuration.body;
			this.activeURI = uriEndpointFactory.createUriEndpoint(uri, configuration.websocketSubprotocols != null);
		}

		@Override
		public SocketAddress get() {
			return activeURI.getRemoteAddress();
		}

		@Override
		public Publisher<Void> apply(NettyInbound in, NettyOutbound out) {
			try {
				UriEndpoint uri = activeURI;
				HttpClientOperations ch = (HttpClientOperations) in;
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

				if (method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.DELETE) {
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
						return handler.apply(ch, out);
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
}
