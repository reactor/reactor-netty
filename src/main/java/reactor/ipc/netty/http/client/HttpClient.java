/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.NetUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyState;
import reactor.ipc.netty.options.ColocatedEventLoopGroup;
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.channel.NettyOperations;
import reactor.ipc.netty.http.server.HttpServerResponse;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.tcp.TcpClient;

/**
 * The base class for a Netty-based Http client.
 *
 * @author Stephane Maldini
 */
public class HttpClient implements NettyConnector<HttpClientResponse, HttpClientRequest> {

	/**
	 * @return a simple HTTP client
	 */
	public static HttpClient create() {
		return create(ClientOptions.create()
		                           .sslSupport());
	}

//	public static EventLoopGroup defaultEventLoopGroup() {
//		ColocatedEventLoopGroup eventLoopSelector = DefaultState.CLIENT_GROUP.;
//		int ioThreadCount = TcpServer.DEFAULT_IO_THREAD_COUNT;
//		this.ioGroup = new ColocatedEventLoopGroup(channelAdapter.newEventLoopGroup(ioThreadCount,
//				(Runnable r) -> {
//					Thread t = new Thread(r, "reactor-tcp-client-io-"+COUNTER
//							.incrementAndGet());
//					t.setDaemon(options.daemon());
//					return t;
//				}));
//		for (; ; ) {
//			ColocatedEventLoopGroup s = cachedSchedulers.get(key);
//			if (s != null) {
//				return s;
//			}
//			s = new ColocatedEventLoopGroup(key, schedulerSupplier.get());
//			if (DefaultState.CLIENT_GROUP.compareAndSet(global, null, s)) {
//				return s;
//			}
//			s._shutdown();
//		}

	/**
	 * @return a simple HTTP client
	 */
	public static HttpClient create(ClientOptions options) {
		return new HttpClient(Objects.requireNonNull(options, "options"));
	}
//		if (eventLoopSelector == null) {

	/**
	 * @return a simple HTTP client
	 */
	public static HttpClient create(String address) {
		return create(address, 80);
	}
//		}
//		return DEFAULT_STATE.clientGroup;
//	}

	/**
	 * @return a simple HTTP client
	 */
	public static HttpClient create(String address, int port) {
		return create(ClientOptions.create()
		                           .connect(address, port));
	}

	/**
	 * @return a simple HTTP client
	 */
	public static HttpClient create(int port) {
		return create(ClientOptions.create()
		                           .connect(NetUtil.LOCALHOST.getHostAddress(), port));
	}

	final TcpBridgeClient client;

	protected HttpClient(final ClientOptions options) {
		this.client = new TcpBridgeClient(options);
	}

	/**
	 * HTTP DELETE the passed URL. When connection has been made, the passed handler is
	 * invoked and can be used to build precisely the request and write data to it.
	 *
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on open channel
	 *
	 * @return a {@link Mono} of the {@link HttpServerResponse} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> delete(String url,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
		return request(HttpMethod.DELETE, url, handler);
	}

	/**
	 * HTTP DELETE the passed URL. When connection has been made, the passed handler is
	 * invoked and can be used to build precisely the request and write data to it.
	 *
	 * @param url the target remote URL
	 *
	 * @return a {@link Mono} of the {@link HttpServerResponse} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> delete(String url) {
		return request(HttpMethod.DELETE, url, null);
	}

	/**
	 * HTTP GET the passed URL. When connection has been made, the passed handler is
	 * invoked and can be used to build precisely the request and write data to it.
	 *
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on open channel
	 *
	 * @return a {@link Mono} of the {@link HttpServerResponse} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> get(String url,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
		return request(HttpMethod.GET, url, handler);
	}

	/**
	 * HTTP GET the passed URL.
	 *
	 * @param url the target remote URL
	 *
	 * @return a {@link Mono} of the {@link HttpServerResponse} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> get(String url) {
		return request(HttpMethod.GET, url, null);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<HttpClientResponse> newHandler(BiFunction<? super HttpClientResponse, ? super HttpClientRequest, ? extends Publisher<Void>> ioHandler) {
		return (Mono<HttpClientResponse>) client.newHandler((BiFunction<NettyInbound, NettyOutbound, Publisher<Void>>) ioHandler);
	}

	/**
	 * HTTP PATCH the passed URL. When connection has been made, the passed handler is
	 * invoked and can be used to build precisely the request and write data to it.
	 *
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on open channel
	 *
	 * @return a {@link Mono} of the {@link HttpServerResponse} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> patch(String url,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
		return request(HttpMethod.PATCH, url, handler);
	}

	/**
	 * HTTP PATCH the passed URL.
	 *
	 * @param url the target remote URL
	 *
	 * @return a {@link Mono} of the {@link HttpServerResponse} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> patch(String url) {
		return request(HttpMethod.PATCH, url, null);
	}

	/**
	 * HTTP POST the passed URL. When connection has been made, the passed handler is
	 * invoked and can be used to build precisely the request and write data to it.
	 *
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on open channel
	 *
	 * @return a {@link Mono} of the {@link HttpServerResponse} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> post(String url,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
		return request(HttpMethod.POST, url, handler);
	}

	/**
	 * HTTP PUT the passed URL. When connection has been made, the passed handler is
	 * invoked and can be used to build precisely the request and write data to it.
	 *
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on open channel
	 *
	 * @return a {@link Mono} of the {@link HttpServerResponse} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> put(String url,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
		return request(HttpMethod.PUT, url, handler);
	}

	/**
	 * Use the passed HTTP method to send to the given URL. When connection has been made,
	 * the passed handler is invoked and can be used to build precisely the request and
	 * write data to it.
	 *
	 * @param method the HTTP method to send
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on opened TCP connection
	 *
	 * @return a {@link Mono} of the {@link HttpServerResponse} ready to consume for
	 * response
	 */
	public Mono<HttpClientResponse> request(HttpMethod method,
			String url,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
		final URI currentURI;
		try {
			if (method == null && url == null) {
				throw new IllegalArgumentException("Method && url cannot be both null");
			}
			currentURI = new URI(parseURL(url, false));
		}
		catch (Exception e) {
			return Mono.error(e);
		}
		return new MonoHttpClientResponse(this, currentURI, method, handler);
	}

	/**
	 * WebSocket to the passed URL.
	 *
	 * @param url the target remote URL
	 *
	 * @return a {@link Mono} of the {@link HttpServerResponse} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> ws(String url) {
		return request(HttpMethod.GET, parseURL(url, true),
				req -> req.upgradeToWebsocket(NettyOperations.noopHandler()));
	}

	/**
	 * WebSocket to the passed URL.
	 *
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on opened WS connection
	 *
	 * @return a {@link Mono} of the {@link HttpServerResponse} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> ws(String url,
			final Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
		return request(HttpMethod.GET,
				parseURL(url, true),
				ch -> ch.upgradeToWebsocket((in, out) -> handler.apply((HttpClientRequest) out)));
	}

	@SuppressWarnings("unchecked")
	Mono<?> newHandler(URI url,
			Consumer<? super Channel> onSetup,
			BiFunction<? super HttpClientResponse, ? super HttpClientRequest, ? extends Publisher<Void>> ioHandler) {

		boolean secure = url.getScheme() != null && (url.getScheme()
		                                                .toLowerCase()
		                                                .equals(HTTPS_SCHEME) || url.getScheme()
		                                                                            .toLowerCase()
		                                                                            .equals(WSS_SCHEME));
		int port = url.getPort() != -1 ? url.getPort() : (secure ? 443 : 80);
		return client.newHandler((BiFunction<NettyInbound, NettyOutbound, Publisher<Void>>) ioHandler,
				client.getOptions()
				      .proxyType() != null ?
						InetSocketAddress.createUnresolved(url.getHost(), port) :
						new InetSocketAddress(url.getHost(), port), secure, onSetup);
	}

	final String parseURL(String url, boolean ws) {
		if (!url.startsWith(HTTP_SCHEME) && !url.startsWith(WS_SCHEME)) {
			final String parsedUrl = (ws ? WS_SCHEME : HTTP_SCHEME) + "://";
			if (url.startsWith("/")) {
				InetSocketAddress remote = client.getOptions()
				                                 .remoteAddress();

				return parsedUrl + (client.getOptions()
				                          .proxyType() == null && remote != null ?
						remote.getHostName() + ":" + remote.getPort() : "localhost") +
						url;
			}
			else {
				return parsedUrl + url;
			}
		}
		else {
			return url;
		}
	}

	//
//
	final static class DefaultState {

		volatile ColocatedEventLoopGroup clientGroup;

		static final AtomicReferenceFieldUpdater<DefaultState, ColocatedEventLoopGroup>
				CLIENT_GROUP = AtomicReferenceFieldUpdater.newUpdater(DefaultState.class,
				ColocatedEventLoopGroup.class,
				"clientGroup");

	}

	final static DefaultState   global         = new DefaultState();
	final static String         WS_SCHEME      = "ws";
	final static String         WSS_SCHEME     = "wss";
	final static String         HTTP_SCHEME    = "http";
	final static String         HTTPS_SCHEME   = "https";
	final static LoggingHandler loggingHandler = new LoggingHandler(HttpClient.class);

	@SuppressWarnings("unchecked")
	final class TcpBridgeClient extends TcpClient {

		TcpBridgeClient(ClientOptions options) {
			super(options);
		}

		@Override
		protected Mono<NettyState> newHandler(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
				InetSocketAddress address,
				boolean secure,
				Consumer<? super Channel> onSetup) {
			return super.newHandler(handler, address, secure, onSetup);
		}

		@Override
		protected void onSetup(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
				SocketChannel ch,
				MonoSink<NettyState> sink) {
			ch.pipeline()
			  .addLast(NettyHandlerNames.HttpCodecHandler, new HttpClientCodec());

			HttpClientOperations.bindHttp(ch, handler, sink);
		}

		@Override
		protected LoggingHandler logger() {
			return loggingHandler;
		}
	}
}
