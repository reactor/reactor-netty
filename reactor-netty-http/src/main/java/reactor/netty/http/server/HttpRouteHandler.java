/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty.http.server;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.reactivestreams.Publisher;
import reactor.util.annotation.Nullable;

/**
 * @author Stephane Maldini
 * @author chentong
 */
public class HttpRouteHandler
		implements BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>>,
		Predicate<HttpServerRequest> {

	private final Predicate<? super HttpServerRequest> condition;
	private final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>>
			handler;
	private final Function<? super String, Map<String, String>> resolver;

	private final String path;

	public HttpRouteHandler(Predicate<? super HttpServerRequest> condition,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler,
			@Nullable Function<? super String, Map<String, String>> resolver,
			@Nullable String path) {
		this.condition = condition;
		this.handler = handler;
		this.resolver = resolver;
		this.path = path;
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

	public String getPath() {
		return path;
	}
}
