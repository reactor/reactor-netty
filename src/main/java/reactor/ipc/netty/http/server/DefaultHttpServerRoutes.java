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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.reactivestreams.Publisher;

/**
 * @author Stephane Maldini
 */
final class DefaultHttpServerRoutes implements HttpServerRoutes {

	private final CopyOnWriteArrayList<HttpRouteHandler> handlers =
			new CopyOnWriteArrayList<>();

	@Override
	public HttpServerRoutes route(Predicate<? super HttpServerRequest> condition,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		if (condition instanceof HttpPredicate) {
			handlers.add(new HttpRouteHandler(condition,
					handler,
					(HttpPredicate) condition));
		}
		else {
			handlers.add(new HttpRouteHandler(condition, handler, null));
		}
		return this;
	}

	@Override
	public Iterable<? extends BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>>> apply(
			HttpServerRequest request,
			HttpServerResponse response) {
		return new IterableRoute(handlers, request);
	}

	/**
	 */
	static final class HttpRouteHandler
			implements BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>>,
			           Predicate<HttpServerRequest> {

		final Predicate<? super HttpServerRequest>          condition;
		final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>>
		                                                    handler;
		final Function<? super String, Map<String, Object>> resolver;

		HttpRouteHandler(Predicate<? super HttpServerRequest> condition,
				BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler,
				Function<? super String, Map<String, Object>> resolver) {
			this.condition = Objects.requireNonNull(condition, "condition");
			this.handler = Objects.requireNonNull(handler, "handler");
			this.resolver = resolver;
		}

		@Override
		public Publisher<Void> apply(HttpServerRequest request,
				HttpServerResponse response) {
			return handler.apply(request.paramsResolver(resolver), response);
		}

		@Override
		public boolean test(HttpServerRequest o) {
			return condition.test(o);
		}
	}

	static final class IterableRoute implements
	                                 Iterable<BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>>> {

		final Iterable<HttpRouteHandler> handlers;
		final HttpServerRequest          request;

		IterableRoute(Iterable<HttpRouteHandler> handlers, HttpServerRequest request) {
			this.handlers = handlers;
			this.request = request;
		}

		@Override
		public Iterator<BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>>> iterator() {
			final Iterator<HttpRouteHandler> iterator = handlers.iterator();
			return new Iterator<BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>>>() {

				HttpRouteHandler cached;

				@Override
				public boolean hasNext() {
					HttpRouteHandler cursor;
					while (iterator.hasNext()) {
						cursor = iterator.next();
						if (cursor.test(request)) {
							cached = cursor;
							return true;
						}
					}
					return false;
				}

				@Override
				public BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> next() {
					HttpRouteHandler cursor = cached;
					if (cursor != null) {
						cached = null;
						return cursor;
					}
					hasNext();
					return cached;
				}
			};
		}
	}
}
