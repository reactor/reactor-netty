/*
 * Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.server;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import io.netty.handler.codec.http.HttpMethod;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.util.annotation.Nullable;

/**
 * Server routes are unique and only the first matching in order of declaration will be invoked.
 *
 * @author Stephane Maldini
 */
final class DefaultHttpServerRoutes implements HttpServerRoutes {

	private final CopyOnWriteArrayList<HttpRouteHandler> handlers =
			new CopyOnWriteArrayList<>();

	private final List<HttpRouteHandler> initialOrderHandlers = new ArrayList<>();

	private Comparator<HttpRouteHandlerMetadata> comparator;

	@Override
	public HttpServerRoutes directory(String uri, Path directory,
			@Nullable Function<HttpServerResponse, HttpServerResponse> interceptor) {
		Objects.requireNonNull(directory, "directory");
		Path absPath = directory.toAbsolutePath().normalize();
		return route(HttpPredicate.prefix(uri), (req, resp) -> {

			String prefix = URI.create(req.uri())
			                   .getPath()
			                   .replaceFirst(uri, "");

			if (!prefix.isEmpty() && prefix.charAt(0) == '/') {
				prefix = prefix.substring(1);
			}

			Path p = absPath.resolve(prefix).toAbsolutePath().normalize();
			if (Files.isReadable(p) && p.startsWith(absPath)) {

				if (interceptor != null) {
					return interceptor.apply(resp)
					                  .sendFile(p);
				}
				return resp.sendFile(p);
			}

			return resp.sendNotFound();
		});
	}

	@Override
	public HttpServerRoutes removeIf(Predicate<? super HttpRouteHandlerMetadata> condition) {
		Objects.requireNonNull(condition, "condition");

		handlers.removeIf(condition);

		return this;
	}

	@Override
	public HttpServerRoutes route(Predicate<? super HttpServerRequest> condition,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(condition, "condition");
		Objects.requireNonNull(handler, "handler");

		if (condition instanceof HttpPredicate) {
			HttpPredicate predicate = (HttpPredicate) condition;
			HttpRouteHandler httpRouteHandler = new HttpRouteHandler(condition,
					handler, predicate, predicate.uri, predicate.method);

			handlers.add(httpRouteHandler);
			initialOrderHandlers.add(httpRouteHandler);

		}
		else {
			HttpRouteHandler httpRouteHandler = new HttpRouteHandler(condition, handler, null, null, null);
			handlers.add(httpRouteHandler);
			initialOrderHandlers.add(httpRouteHandler);
		}

		if (this.comparator != null) {
			handlers.sort(this.comparator);
		}

		return this;
	}

	@Override
	public HttpServerRoutes comparator(Comparator<HttpRouteHandlerMetadata> comparator) {
		Objects.requireNonNull(comparator, "comparator");
		this.comparator = comparator;
		handlers.sort(comparator);
		return this;
	}

	@Override
	public HttpServerRoutes noComparator() {
		handlers.clear();
		handlers.addAll(initialOrderHandlers);
		return this;
	}

	@Override
	public Publisher<Void> apply(HttpServerRequest request, HttpServerResponse response) {
		// find I/0 handler to process this request
		final Iterator<HttpRouteHandler> iterator = handlers.iterator();
		HttpRouteHandler cursor;

		try {
			while (iterator.hasNext()) {
				cursor = iterator.next();
				if (cursor.test(request)) {
					return cursor.apply(request, response);
				}
			}
		}
		catch (Throwable t) {
			Exceptions.throwIfJvmFatal(t);
			return Mono.error(t); //500
		}

		return response.sendNotFound();
	}

	static final class HttpRouteHandler
			implements BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>>,
			Predicate<HttpServerRequest>, HttpRouteHandlerMetadata {

		final Predicate<? super HttpServerRequest> condition;
		final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>>
				handler;
		final Function<? super String, Map<String, String>> resolver;

		final String path;

		final HttpMethod method;

		HttpRouteHandler(Predicate<? super HttpServerRequest> condition,
				BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler,
				@Nullable Function<? super String, Map<String, String>> resolver,
				@Nullable String path,
				@Nullable HttpMethod method) {
			this.condition = Objects.requireNonNull(condition, "condition");
			this.handler = Objects.requireNonNull(handler, "handler");
			this.resolver = resolver;
			this.path = path;
			this.method = method;
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

		@Override
		public String getPath() {
			return path;
		}

		@Override
		public HttpMethod getMethod() {
			return method;
		}
	}
}
