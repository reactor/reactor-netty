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

package reactor.ipc.netty.http;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import org.reactivestreams.Publisher;
import reactor.core.Loopback;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSource;
import reactor.ipc.netty.common.ChannelBridge;
import reactor.ipc.netty.common.ColocatedEventLoopGroup;
import reactor.ipc.netty.common.NettyChannel;
import reactor.ipc.netty.common.NettyHandlerNames;
import reactor.ipc.netty.config.ClientOptions;
import reactor.ipc.netty.tcp.TcpChannel;
import reactor.ipc.netty.tcp.TcpClient;

/**
 * The base class for a Netty-based Http client.
 *
 * @author Stephane Maldini
 */
public class HttpClient
		implements Loopback {

	static final DefaultState global = new DefaultState();

	public static EventLoopGroup defaultEventLoopGroup() {
		ColocatedEventLoopGroup eventLoopGroup = DefaultState.CLIENT_GROUP.;
		int ioThreadCount = TcpServer.DEFAULT_TCP_THREAD_COUNT;
		this.ioGroup = new ColocatedEventLoopGroup(channelAdapter.newEventLoopGroup(ioThreadCount,
				(Runnable r) -> {
					Thread t = new Thread(r, "reactor-tcp-client-io-"+COUNTER
							.incrementAndGet());
					t.setDaemon(options.daemon());
					return t;
				}));
		for (; ; ) {
			ColocatedEventLoopGroup s = cachedSchedulers.get(key);
			if (s != null) {
				return s;
			}
			s = new ColocatedEventLoopGroup(key, schedulerSupplier.get());
			if (DefaultState.CLIENT_GROUP.compareAndSet(global, null, s)) {
				return s;
			}
			s._shutdown();
		}

		if (eventLoopGroup == null) {

		}
		return DEFAULT_STATE.clientGroup;
	}


	/**
	 * @return a simple HTTP client
	 */
	public static HttpClient create() {
		return create(ClientOptions.create()
		                           .sslSupport());
	}

	/**
	 * @return a simple HTTP client
	 */
	public static HttpClient create(ClientOptions options) {
		return new HttpClient(options);
	}

	/**
	 * @return a simple HTTP client
	 */
	public static HttpClient create(String address) {
		return create(address, 80);
	}

	/**
	 * @return a simple HTTP client
	 */
	public static HttpClient create(String address, int port) {
		return create(ClientOptions.create()
		                           .connect(address, port));
	}

	final TcpBridgeClient client;

	protected HttpClient(final ClientOptions options) {
		this.client = new TcpBridgeClient(options);
	}

	@Override
	public Object connectedInput() {
		return client;
	}

	/**
	 * HTTP DELETE the passed URL. When connection has been made, the passed handler is
	 * invoked and can be used to build precisely the request and write data to it.
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on open channel
	 * @return a {@link Mono} of the {@link HttpChannel} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> delete(String url,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
		return request(HttpMethod.DELETE, url, handler);
	}

	/**
	 * HTTP DELETE the passed URL. When connection has been made, the passed handler is invoked and can be used to build
	 * precisely the request and write data to it.
	 *
	 * @param url the target remote URL
	 *
	 * @return a {@link Mono} of the {@link HttpChannel} ready to consume for response
	 */
	public final Mono<HttpClientResponse> delete(String url) {
		return request(HttpMethod.DELETE, url, null);
	}

	/**
	 * HTTP GET the passed URL. When connection has been made, the passed handler is
	 * invoked and can be used to build precisely the request and write data to it.
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on open channel
	 * @return a {@link Mono} of the {@link HttpChannel} ready to consume for
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
	 * @return a {@link Mono} of the {@link HttpChannel} ready to consume for response
	 */
	public final Mono<HttpClientResponse> get(String url) {
		return request(HttpMethod.GET, url, null);
	}

	/**
	 * HTTP PATCH the passed URL. When connection has been made, the passed handler is
	 * invoked and can be used to build precisely the request and write data to it.
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on open channel
	 * @return a {@link Mono} of the {@link HttpChannel} ready to consume for
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
	 * @return a {@link Mono} of the {@link HttpChannel} ready to consume for response
	 */
	public final Mono<HttpClientResponse> patch(String url) {
		return request(HttpMethod.PATCH, url, null);
	}

	/**
	 * Is shutdown
	 * @return is shutdown
	 */
	public boolean isShutdown() {
		return client.isShutdown();
	}

	/**
	 * HTTP POST the passed URL. When connection has been made, the passed handler is
	 * invoked and can be used to build precisely the request and write data to it.
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on open channel
	 * @return a {@link Mono} of the {@link HttpChannel} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> post(String url,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
		return request(HttpMethod.POST, url, handler);
	}

	/**
	 * HTTP PUT the passed URL. When connection has been made, the passed handler is
	 * invoked and can be used to build precisely the request and write data to it.
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on open channel
	 * @return a {@link Mono} of the {@link HttpChannel} ready to consume for
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
	 * @param method the HTTP method to send
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on opened TCP connection
	 * @return a {@link Mono} of the {@link HttpChannel} ready to consume for
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
		return new MonoHttpClientChannel(this, currentURI, method, handler);
	}

	/**
	 * WebSocket to the passed URL.
	 * @param url the target remote URL
	 * @return a {@link Mono} of the {@link HttpChannel} ready to consume for
	 * response
	 */
	public final Mono<HttpClientResponse> ws(String url) {
		return request(HttpMethod.GET, parseURL(url, true),
				HttpClientRequest::upgradeToWebsocket);
	}

	/**
	 * WebSocket to the passed URL.
	 *
	 * @param url the target remote URL
	 * @param handler the {@link Function} to invoke on opened TCP connection
	 *
	 * @return a {@link Mono} of the {@link HttpChannel} ready to consume for response
	 */
	public final Mono<HttpClientResponse> ws(String url, final Function<? super
			HttpClientRequest, ? extends Publisher<Void>> handler) {
		return request(HttpMethod.GET,
				parseURL(url, true),
				ch -> ch.upgradeToTextWebsocket()
				        .then(() -> MonoSource.wrap(handler.apply(ch))));
	}

	/**
	 * @return
	 */
	public final Mono<Void> shutdown() {
		return client.shutdown();
	}

	Mono<Void> doStart(URI url, ChannelBridge<? extends
			TcpChannel> bridge, Function<? super HttpChannel, ? extends Publisher<Void>> handler) {

		boolean secure = url.getScheme() != null && (url.getScheme()
		                                                .toLowerCase()
		                                                .equals(HTTPS_SCHEME) || url.getScheme()
		                                                                            .toLowerCase()
		                                                                            .equals(WSS_SCHEME));
		int port = url.getPort() != -1 ? url.getPort() : (secure ? 443 : 80);
		return client.doStart(inoutChannel -> handler.apply(((NettyHttpChannel) inoutChannel)),
				client.getOptions()
				      .proxyType() != null ?
						InetSocketAddress.createUnresolved(url.getHost(), port) :
						new InetSocketAddress(url.getHost(), port),
				bridge, secure);
	}

	final String parseURL(String url, boolean ws) {
		if (!url.startsWith(HTTP_SCHEME) && !url.startsWith(WS_SCHEME)) {
			final String parsedUrl = (ws ? WS_SCHEME : HTTP_SCHEME) + "://";
			if (url.startsWith("/")) {
				return parsedUrl + (client.getOptions()
				                          .proxyType() == null && client.getConnectAddress() != null ?
						client.getConnectAddress()
						      .getHostName() + ":" + client.getConnectAddress().getPort() :
						"localhost") + url;
			}
			else {
				return parsedUrl + url;
			}
		}
		else {
			return url;
		}
	}
	final static String WS_SCHEME    = "ws";
	final static String WSS_SCHEME   = "wss";
	final static String HTTP_SCHEME  = "http";
	final static String HTTPS_SCHEME = "https";

	@SuppressWarnings("unchecked")
	final class TcpBridgeClient extends TcpClient {

		TcpBridgeClient(ClientOptions options) {
			super(options);
		}

		@Override
		protected void bindChannel(Function<? super NettyChannel, ? extends Publisher<Void>> handler,
				SocketChannel ch,
				ChannelBridge<? extends TcpChannel> channelBridge) {
			ch.pipeline()
			  .addLast(NettyHandlerNames.HttpCodecHandler, new HttpClientCodec())
			  .addLast(NettyHandlerNames.ReactiveBridge, new NettyHttpClientHandler
					  (handler,
					  (ChannelBridge<HttpClientChannel>) channelBridge, ch));
		}

		@Override
		protected Mono<Void> doStart(Function<? super NettyChannel, ? extends Publisher<Void>> handler,
				InetSocketAddress address,
				ChannelBridge<? extends TcpChannel> channelBridge,
				boolean secure) {
			return super.doStart(handler, address, channelBridge, secure);
		}

		@Override
		protected Class<?> logClass() {
			return HttpClient.class;
		}

		@Override
		protected ClientOptions getOptions() {
			return super.getOptions();
		}
	}

	final static class DefaultState {

		volatile ColocatedEventLoopGroup clientGroup;

		static final AtomicReferenceFieldUpdater<DefaultState, ColocatedEventLoopGroup>
				CLIENT_GROUP = AtomicReferenceFieldUpdater.newUpdater(DefaultState.class,
				ColocatedEventLoopGroup.class,
				"clientGroup");


	}
}
