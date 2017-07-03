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

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.NetUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.NettyContext;
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
 */
public final class HttpServer
		implements NettyConnector<HttpServerRequest, HttpServerResponse> {

	/**
	 * Build a simple Netty HTTP server listening on localhost (127.0.0.1) and
	 * port {@literal 80}.
	 *
	 * @return a simple HTTP Server
	 */
	public static HttpServer create() {
		return create(NetUtil.LOCALHOST.getHostAddress());
	}

	/**
	 * Build a simple Netty HTTP server listening over bind address and port passed
	 * through the {@link HttpServerOptions}.
	 * Use {@literal 0} to let the system assign a random port.
	 *
	 * @param options the options for the server, including bind address and port.
	 *
	 * @return a simple HTTP server
	 */
	public static HttpServer create(Consumer<? super HttpServerOptions> options) {
		Objects.requireNonNull(options, "options");
		HttpServerOptions serverOptions = HttpServerOptions.create();
		serverOptions.loopResources(HttpResources.get());
		options.accept(serverOptions);
		return new HttpServer(serverOptions.duplicate());
	}

	/**
	 * Build a simple Netty HTTP server listening on localhost (127.0.0.1) and the provided
	 * port
	 * Use {@literal 0} to let the system assign a random port.
	 *
	 * @param port the port to listen to, or 0 to dynamically attribute one.
	 *
	 * @return a simple HTTP server
	 */
	public static HttpServer create(int port) {
		return create("0.0.0.0", port);
	}

	/**
	 * Build a simple Netty HTTP server listening on the provided bind address and
	 * port {@literal 80}.
	 *
	 * @param bindAddress address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
	 *
	 * @return a simple HTTP server
	 */
	public static HttpServer create(String bindAddress) {
		return create(bindAddress, 80);
	}

	/**
	 * Build a simple Netty HTTP server listening on the provided bind address and port.
	 *
	 * @param bindAddress address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
	 * @param port the port to listen to, or 0 to dynamically attribute one.
	 *
	 * @return a simple HTTP server
	 */
	public static HttpServer create(String bindAddress, int port) {
		return create(opts -> opts.listen(bindAddress, port));
	}

	final TcpBridgeServer server;
	final HttpServerOptions options;

	HttpServer(HttpServerOptions options) {
		this.server = new TcpBridgeServer(options);
		this.options = options;
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
	public Mono<? extends NettyContext> newHandler(BiFunction<? super HttpServerRequest, ? super
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
	public Mono<? extends NettyContext> newRouter(Consumer<? super HttpServerRoutes>
			routesBuilder) {
		Objects.requireNonNull(routesBuilder, "routeBuilder");
		HttpServerRoutes routes = HttpServerRoutes.newRoutes();
		routesBuilder.accept(routes);
		return newHandler(routes);
	}

	public BlockingNettyContext startRouter(Consumer<? super HttpServerRoutes> routesBuilder) {
		Objects.requireNonNull(routesBuilder, "routeBuilder");
		HttpServerRoutes routes = HttpServerRoutes.newRoutes();
		routesBuilder.accept(routes);
		return start(routes);
	}

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
				MonoSink<NettyContext> sink) {
			return ContextHandler.newServerContext(sink,
					options,
					loggingHandler,
					(ch, c, msg) -> HttpServerOperations.bindHttp(ch, handler, c, msg))
			                     .onPipeline(this)
			                     .autoCreateOperations(false);
		}

        @Override
        public void accept(ChannelPipeline p, ContextHandler<Channel> c) {
            p.addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
                    .addLast(NettyPipeline.HttpEncoder,new HttpResponseEncoder());

            if(options.minCompressionResponseSize >= 0) {
                    p.addLast(NettyPipeline.CompressionHandler, new CompressionHandler(options.minCompressionResponseSize));
            }

            p.addLast(NettyPipeline.HttpServerHandler, new HttpServerHandler(c));
        }

		@Override
		protected LoggingHandler loggingHandler() {
			return loggingHandler;
		}
	}
}
