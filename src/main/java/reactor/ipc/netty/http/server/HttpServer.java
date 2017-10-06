/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.server;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.NetUtil;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.options.ServerOptions;
import reactor.ipc.netty.tcp.BlockingNettyContext;
import reactor.ipc.netty.tcp.TcpServer;

/**
 * Base functionality needed by all servers that communicate with clients over HTTP.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public final class HttpServer
		implements NettyConnector<HttpServerRequest, HttpServerResponse> {

	/**
	 * Build a simple Netty HTTP server listening on localhost (127.0.0.1) and
	 * port {@literal 8080}.
	 *
	 * @return a simple HTTP Server
	 */
	public static HttpServer create() {
		return builder().build();
	}

	/**
	 * Build a simple Netty HTTP server listening over bind address and port passed
	 * through the {@link HttpServerOptions}.
	 * Use {@literal 0} to let the system assign a random port.
	 *
	 * @param options the options for the server, including bind address and port.
	 * @return a simple HTTP server
	 */
	public static HttpServer create(Consumer<? super HttpServerOptions.Builder> options) {
		return builder().options(options).build();
	}

	/**
	 * Build a simple Netty HTTP server listening on localhost (127.0.0.1) and the provided
	 * port
	 * Use {@literal 0} to let the system assign a random port.
	 *
	 * @param port the port to listen to, or 0 to dynamically attribute one.
	 * @return a simple HTTP server
	 */
	public static HttpServer create(int port) {
		return builder().listenAddress(new InetSocketAddress(port)).build();
	}

	/**
	 * Build a simple Netty HTTP server listening on the provided bind address and
	 * port {@literal 8080}.
	 *
	 * @param bindAddress address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
	 * @return a simple HTTP server
	 */
	public static HttpServer create(String bindAddress) {
		return builder().bindAddress(bindAddress).build();
	}

	/**
	 * Build a simple Netty HTTP server listening on the provided bind address and port.
	 *
	 * @param bindAddress address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
	 * @param port the port to listen to, or 0 to dynamically attribute one.
	 * @return a simple HTTP server
	 */
	public static HttpServer create(String bindAddress, int port) {
		return builder().bindAddress(bindAddress).port(port).build();
	}

	/**
	 * Creates a builder for {@link HttpServer HttpServer}
	 *
	 * @return a new HttpServer builder
	 */
	public static HttpServer.Builder builder() {
		return new HttpServer.Builder();
	}

	private final TcpBridgeServer server;
	final HttpServerOptions options;

	private HttpServer(HttpServer.Builder builder) {
		HttpServerOptions.Builder serverOptionsBuilder = HttpServerOptions.builder();
		if (Objects.isNull(builder.options)) {
			if (Objects.isNull(builder.bindAddress)) {
				serverOptionsBuilder.listenAddress(builder.listenAddress.get());
			}
			else {
				serverOptionsBuilder.host(builder.bindAddress).port(builder.port);
			}
		}
		else {
			builder.options.accept(serverOptionsBuilder);
		}
		if (!serverOptionsBuilder.isLoopAvailable()) {
			serverOptionsBuilder.loopResources(HttpResources.get());
		}
		this.options = serverOptionsBuilder.build();
		this.server = new TcpBridgeServer(this.options);
	}

	/**
	 * Get a copy of the {@link HttpServerOptions} currently in effect.
	 *
	 * @return the http server options
	 */
	public final HttpServerOptions options() {
		return this.options.duplicate();
	}

	@Override
	public String toString() {
		return "HttpServer: " + options.asSimpleString();
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<? extends Connection> newHandler(BiFunction<? super HttpServerRequest, ? super
			HttpServerResponse, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return server.newHandler((BiFunction<NettyInbound, NettyOutbound, Publisher<Void>>) handler);
	}

	/**
	 * Define routes for the server through the provided {@link HttpServerRoutes} builder.
	 *
	 * @param routesBuilder provides a route builder to be mutated in order to define routes.
	 * @return a new {@link Mono} starting the router on subscribe
	 */
	public Mono<? extends Connection> newRouter(Consumer<? super HttpServerRoutes>
			routesBuilder) {
		Objects.requireNonNull(routesBuilder, "routeBuilder");
		HttpServerRoutes routes = HttpServerRoutes.newRoutes();
		routesBuilder.accept(routes);
		return newHandler(routes);
	}

	/**
	 * Start an HttpServer with routes defined through the provided {@link HttpServerRoutes}
	 * builder, in a blocking fashion, and wait for it to finish initializing.
	 * The returned {@link BlockingNettyContext} class offers a simplified API around operating
	 * the client/server in a blocking fashion, including to {@link BlockingNettyContext#shutdown() shut it down}.
	 *
	 * @param routesBuilder provides a route builder to be mutated in order to define routes.
	 * @return a {@link BlockingNettyContext}
	 */
	public BlockingNettyContext startRouter(Consumer<? super HttpServerRoutes> routesBuilder) {
		Objects.requireNonNull(routesBuilder, "routeBuilder");
		HttpServerRoutes routes = HttpServerRoutes.newRoutes();
		routesBuilder.accept(routes);
		return start(routes);
	}

	/**
	 * Start an HttpServer with routes defined through the provided {@link HttpServerRoutes}
	 * builder, in a fully blocking fashion, not only waiting for it to
	 * initialize but also blocking during the full lifecycle of the server.
	 * Since most servers will be long-lived, this is more adapted to running a server
	 * out of a main method, only allowing shutdown of the servers through sigkill.
	 * <p>
	 * Note that a {@link Runtime#addShutdownHook(Thread) JVM shutdown hook} is added
	 * by this method in order to properly disconnect the client/server upon receiving
	 * a sigkill signal.
	 *
	 * @param routesBuilder provides a route builder to be mutated in order to define routes.
	 */
	public void startRouterAndAwait(Consumer<? super HttpServerRoutes> routesBuilder) {
		startRouterAndAwait(routesBuilder, null);
	}

	/**
	 * Start an HttpServer with routes defined through the provided {@link HttpServerRoutes}
	 * builder, in a fully blocking fashion, not only waiting for it to
	 * initialize but also blocking during the full lifecycle of the server.
	 * Since most servers will be long-lived, this is more adapted to running a server
	 * out of a main method, only allowing shutdown of the servers through sigkill.
	 * <p>
	 * Note that a {@link Runtime#addShutdownHook(Thread) JVM shutdown hook} is added
	 * by this method in order to properly disconnect the client/server upon receiving
	 * a sigkill signal.
	 *
	 * @param routesBuilder provides a route builder to be mutated in order to define routes.
	 * @param onStart an optional callback to be invoked once the server has finished initializing.
	 */
	public void startRouterAndAwait(Consumer<? super HttpServerRoutes> routesBuilder,
			Consumer<BlockingNettyContext> onStart) {
		Objects.requireNonNull(routesBuilder, "routeBuilder");
		HttpServerRoutes routes = HttpServerRoutes.newRoutes();
		routesBuilder.accept(routes);
		startAndAwait(routes, onStart);
	}

	static final LoggingHandler loggingHandler = new LoggingHandler(HttpServer.class);

	final class TcpBridgeServer extends TcpServer
			implements BiConsumer<ChannelPipeline, ContextHandler<Channel>> {

		TcpBridgeServer(ServerOptions options) {
			super(options);
		}

		@Override
		protected ContextHandler<Channel> doHandler(
				BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
				MonoSink<Connection> sink) {
			return ContextHandler.newServerContext(sink,
					options,
					loggingHandler,
					(ch, c, msg) -> HttpServerOperations.bindHttp(ch, handler, c, msg))
			                     .onPipeline(this)
			                     .autoCreateOperations(false);
		}

        @Override
        public void accept(ChannelPipeline p, ContextHandler<Channel> c) {
            p.addLast(NettyPipeline.HttpCodec, new HttpServerCodec());

            if(options.minCompressionResponseSize() >= 0) {
                    p.addLast(NettyPipeline.CompressionHandler, new CompressionHandler(options.minCompressionResponseSize()));
            }

            p.addLast(NettyPipeline.HttpServerHandler, new HttpServerHandler(c));
        }

		@Override
		protected LoggingHandler loggingHandler() {
			return loggingHandler;
		}
	}

	public static final class Builder {
		private String bindAddress = null;
		private int port = 8080;
		private Supplier<InetSocketAddress> listenAddress = () -> new InetSocketAddress(NetUtil.LOCALHOST, port);
		private Consumer<? super HttpServerOptions.Builder> options;

		private Builder() {
		}

		/**
		 * The address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
		 *
		 * @param bindAddress address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
		 * @return {@code this}
		 */
		public final Builder bindAddress(String bindAddress) {
			this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
			return this;
		}

		/**
		 * The {@link InetSocketAddress} to listen on.
		 *
		 * @param listenAddress the listen address
		 * @return {@code this}
		 */
		public final Builder listenAddress(InetSocketAddress listenAddress) {
			Objects.requireNonNull(listenAddress, "listenAddress");
			this.listenAddress = () -> listenAddress;
			return this;
		}

		/**
		 * The port to listen to, or 0 to dynamically attribute one.
		 *
		 * @param port the port to listen to, or 0 to dynamically attribute one.
		 * @return {@code this}
		 */
		public final Builder port(int port) {
			this.port = port;
			return this;
		}

		/**
		 * The options for the server, including bind address and port.
		 *
		 * @param options the options for the server, including bind address and port.
		 * @return {@code this}
		 */
		public final Builder options(Consumer<? super HttpServerOptions.Builder> options) {
			this.options = Objects.requireNonNull(options, "options");
			return this;
		}

		public HttpServer build() {
			return new HttpServer(this);
		}
	}
}
