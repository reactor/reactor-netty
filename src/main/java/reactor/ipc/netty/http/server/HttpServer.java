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

package reactor.ipc.netty.http.server;

import java.util.Iterator;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.NetUtil;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.options.NettyOptions;
import reactor.ipc.netty.options.ServerOptions;
import reactor.ipc.netty.tcp.TcpServer;

/**
 * Base functionality needed by all servers that communicate with clients over HTTP.
 *
 * @author Stephane Maldini
 */
public final class HttpServer
		implements NettyConnector<HttpServerRequest, HttpServerResponse> {

	/**
	 * Build a simple Netty HTTP server listening on 127.0.0.1 and 12012
	 *
	 * @return a simple HTTP Server
	 */
	public static HttpServer create() {
		return create(NetUtil.LOCALHOST.getHostAddress());
	}

	/**
	 * Build a simple Netty HTTP server listening othe passed bind address and port
	 *
	 * @param options
	 *
	 * @return a simple HTTP server
	 */
	public static HttpServer create(Consumer<? super HttpServerOptions> options) {
		Objects.requireNonNull(options, "options");
		HttpServerOptions serverOptions = HttpServerOptions.create();
		serverOptions.channelResources(HttpResources.defaultHttpLoops());
		options.accept(serverOptions);
		return new HttpServer(serverOptions.duplicate());
	}

	/**
	 * Build a simple Netty HTTP server listening on 127.0.0.1 and the passed port
	 *
	 * @param port the port to listen to
	 *
	 * @return a simple HTTP server
	 */
	public static HttpServer create(int port) {
		return create("0.0.0.0", port);
	}

	/**
	 * Build a simple Netty HTTP server listening on 127.0.0.1 and 12012
	 *
	 * @param bindAddress address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
	 *
	 * @return a simple HTTP server
	 */
	public static HttpServer create(String bindAddress) {
		return create(bindAddress, NettyOptions.DEFAULT_PORT);
	}

	/**
	 * Build a simple Netty HTTP server listening othe passed bind address and port
	 *
	 * @param bindAddress address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
	 * @param port the port to listen to
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

	@Override
	@SuppressWarnings("unchecked")
	public Mono<? extends NettyContext> newHandler(BiFunction<? super HttpServerRequest, ? super
			HttpServerResponse, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return server.newHandler((BiFunction<NettyInbound, NettyOutbound, Publisher<Void>>) handler);
	}

	/**
	 *
	 * @param routesBuilder a mutable route builder
	 * @return a new {@link Mono} starting the router on subscribe
	 */
	public Mono<? extends NettyContext> newRouter(Consumer<? super HttpServerRoutes>
			routesBuilder) {
		Objects.requireNonNull(routesBuilder, "routeBuilder");
		HttpServerRoutes routes = HttpServerRoutes.newRoutes();
		routesBuilder.accept(routes);
		return newHandler((req, resp) -> {
			try {
				Publisher<Void> afterHandlers = routeRequestResponse(req, resp, routes);

				if (afterHandlers == null) {
					resp.responseHeaders().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
					resp.status(HttpResponseStatus.NOT_FOUND);
					resp.disableChunkedTransfer();
					return resp.sendHeaders();
				}
				else {
					return afterHandlers;
				}
			}
			catch (Throwable t) {
				Exceptions.throwIfFatal(t);
				return Mono.error(t);
			}
			//500
		});
	}

	final Publisher<Void> routeRequestResponse(HttpServerRequest req,
			HttpServerResponse resp,
			HttpServerRoutes routes) {

		if (routes == null) {
			return null;
		}

		final Iterator<? extends BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>>>
				selected = routes.apply(req, resp)
				                 .iterator();

		if (!selected.hasNext()) {
			return null;
		}

		BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>>
				ioHandler = selected.next();

		return ioHandler.apply(req, resp);
	}

	static final LoggingHandler loggingHandler = new LoggingHandler(HttpServer.class);

	final class TcpBridgeServer extends TcpServer {

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
					(ch, c) -> HttpServerOperations.bindHttp(ch, handler, c));
		}
	}
}
