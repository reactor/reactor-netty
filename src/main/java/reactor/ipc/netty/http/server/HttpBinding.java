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
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.tcp.BlockingNettyContext;

/**
 * @author Simon Baslé
 */
@FunctionalInterface
public interface HttpBinding extends NettyConnector<HttpServerRequest, HttpServerResponse> {

	@Override
	Mono<? extends NettyContext> newHandler(
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler);

	/**
	 * Define routes for the server through the provided {@link HttpServerRoutes} builder.
	 *
	 * @param routesBuilder provides a route builder to be mutated in order to define routes.
	 * @return a new {@link Mono} starting the router on subscribe
	 */
	default Mono<? extends NettyContext> newRouter(
			Consumer<? super HttpServerRoutes> routesBuilder) {
		Objects.requireNonNull(routesBuilder, "routeBuilder");
		HttpServerRoutes routes = HttpServerRoutes.newRoutes();
		routesBuilder.accept(routes);
		return newHandler(routes);
	}

	default BlockingNettyContext startRouter(Consumer<? super HttpServerRoutes> routesBuilder) {
		HttpServerRoutes routes = HttpServerRoutes.newRoutes();
		routesBuilder.accept(routes);
		return start(routes);
	}

	default void startRouterAndAwait(Consumer<? super HttpServerRoutes> routesBuilder,
			Consumer<BlockingNettyContext> onStart) {
		Objects.requireNonNull(routesBuilder, "routeBuilder");
		HttpServerRoutes routes = HttpServerRoutes.newRoutes();
		routesBuilder.accept(routes);
		startAndAwait(routes, onStart);
	}
}
