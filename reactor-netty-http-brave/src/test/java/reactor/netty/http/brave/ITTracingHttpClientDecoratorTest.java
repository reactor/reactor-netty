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

import brave.internal.Nullable;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.test.http.ITHttpAsyncClient;
import io.netty.handler.codec.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.util.function.BiConsumer;

import static brave.Span.Kind.CLIENT;
import static org.assertj.core.api.Assertions.assertThat;

public class ITTracingHttpClientDecoratorTest extends ITHttpAsyncClient<HttpClient> {

	@Override
	protected HttpClient newClient(int port) {
		ReactorNettyHttpTracing reactorNettyHttpTracing = ReactorNettyHttpTracing.create(httpTracing, s -> null);

		return reactorNettyHttpTracing.decorateHttpClient(
		        HttpClient.create()
		                  .host("127.0.0.1")
		                  .port(port)
		                  .wiretap(true)
		                  .followRedirect(true)
		                  .disableRetry(true));
	}

	@Override
	protected void closeClient(HttpClient client) {
		// noop
	}

	@Override
	protected void options(HttpClient client, String path) {
		execute(client, HttpMethod.OPTIONS, path);
	}

	@Override
	protected void get(HttpClient client, String pathIncludingQuery) {
		execute(client, HttpMethod.GET, pathIncludingQuery);
	}

	@Override
	protected void get(HttpClient client, String path, BiConsumer<Integer, Throwable> callback) {
		client.doAfterResponseSuccess((res, conn) -> invokeCallback(callback, res.currentContextView(), res.status().code(), null))
		      .doOnError(
		          (req, throwable) -> invokeCallback(callback, req.currentContextView(), null, throwable),
		          (res, throwable) -> invokeCallback(callback, res.currentContextView(), res.status().code(), throwable))
		      .get()
		      .uri(path.isEmpty() ? "/" : path)
		      .responseContent()
		      .aggregate()
		      .subscribe();
	}

	@Override
	protected void post(HttpClient client, String pathIncludingQuery, String body) {
		execute(client, HttpMethod.POST, pathIncludingQuery, body);
	}

	@Test
	@Ignore("TODO: fix broken context")
	public void currentSpanVisibleToUserHandler() {
		server.enqueue(new MockResponse());

		TraceContext invocationContext = newTraceContext(SamplingFlags.SAMPLED);
		try (Scope ws = currentTraceContext.newScope(invocationContext)) {
			client.request(HttpMethod.GET)
					.uri("/")
					.send((req, out) -> {
						// currentTraceContext should either be the invocationContext or the
						// client span context depending on if the request has been sent or not.
						req.header("my-id", currentTraceContext.get().traceIdString());
						return out;
					})
					.responseContent()
					.aggregate()
					.block(Duration.ofSeconds(30));
		}

		RecordedRequest request = takeRequest();
		assertThat(request.getHeader("x-b3-traceId"))
				.isEqualTo(request.getHeader("my-id"));

		testSpanHandler.takeRemoteSpan(CLIENT);
	}

	void execute(HttpClient client, HttpMethod method, String pathIncludingQuery) {
		execute(client, method, pathIncludingQuery, null);
	}

	void execute(HttpClient client, HttpMethod method, String pathIncludingQuery, @Nullable String body) {
		client.request(method)
		      .uri(pathIncludingQuery.isEmpty() ? "/" : pathIncludingQuery)
		      .send((req, out) -> {
		          if (body != null) {
		              return out.sendString(Mono.just(body));
		          }
		          return out;
		      })
		      .responseContent()
		      .aggregate()
		      .block(Duration.ofSeconds(30));
	}

	void invokeCallback(BiConsumer<Integer, Throwable> callback, ContextView contextView, Integer status, Throwable throwable) {
		TraceContext traceContext = contextView.getOrDefault(TraceContext.class, null);
		if (traceContext != null) {
			try (Scope scope = currentTraceContext.maybeScope(traceContext)) {
				callback.accept(status, throwable);
			}
		}
		else {
			callback.accept(status, throwable);
		}
	}
}
