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
package reactor.netty.http.brave;

import brave.http.HttpTracing;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.util.function.Function;

/**
 * Brave instrumentation for Reactor Netty HTTP.
 *
 * <pre>
 * {@code
 *     ReactorNettyHttpTracing reactorNettyHttpTracing = ReactorNettyHttpTracing.create(httpTracing);
 *     HttpClient client = reactorNettyHttpTracing.decorateHttpClient(HttpClient.create().port(0)...);
 *     HttpServer server = reactorNettyHttpTracing.decorateHttpServer(HttpServer.create().port(0)...);
 * }
 * </pre>
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class ReactorNettyHttpTracing {

	/**
	 * Create a new {@link ReactorNettyHttpTracing} using a preconfigured {@link HttpTracing} instance.
	 *
	 * @param httpTracing a preconfigured {@link HttpTracing} instance
	 * @return a new {@link ReactorNettyHttpTracing}
	 */
	public static ReactorNettyHttpTracing create(HttpTracing httpTracing) {
		return create(httpTracing, Function.identity());
	}

	/**
	 * Create a new {@link ReactorNettyHttpTracing} using a preconfigured {@link HttpTracing} instance.
	 * <p>{@code uriMapping} function receives the actual uri and returns the target uri value
	 * that will be used for the tracing.
	 * For example instead of using the actual uri {@code "/users/1"} as uri value, templated uri
	 * {@code "/users/{id}"} can be used.
	 *
	 * @param httpTracing a preconfigured {@link HttpTracing} instance
	 * @param uriMapping a function that receives the actual uri and returns the target uri value
	 * that will be used for the tracing
	 * @return a new {@link ReactorNettyHttpTracing}
	 */
	public static ReactorNettyHttpTracing create(HttpTracing httpTracing, Function<String, String> uriMapping) {
		return new ReactorNettyHttpTracing(httpTracing, uriMapping);
	}

	final TracingHttpClientDecorator httpClientDecorator;
	final TracingHttpServerDecorator httpServerDecorator;

	ReactorNettyHttpTracing(HttpTracing httpTracing, Function<String, String> uriMapping) {
		this.httpClientDecorator = new TracingHttpClientDecorator(httpTracing, uriMapping);
		this.httpServerDecorator = new TracingHttpServerDecorator(httpTracing, uriMapping);
	}

	/**
	 * Returns a decorated {@link HttpClient} in order to enable Brave instrumentation.
	 *
	 * @param client a client to decorate
	 * @return a decorated {@link HttpClient}
	 */
	public HttpClient decorateHttpClient(HttpClient client) {
		return httpClientDecorator.decorate(client);
	}

	/**
	 * Returns a decorated {@link HttpServer} in order to enable Brave instrumentation.
	 *
	 * @param server a server to decorate
	 * @return a decorated {@link HttpServer}
	 */
	public HttpServer decorateHttpServer(HttpServer server) {
		return httpServerDecorator.decorate(server);
	}
}
