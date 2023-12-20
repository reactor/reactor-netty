/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.brave;

import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.test.http.ITHttpAsyncClient;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static brave.Span.Kind.CLIENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class ITTracingHttpClientDecoratorTest extends ITHttpAsyncClient<HttpClient> {
	private ChannelGroup group;
	private static final EventExecutor executor = new DefaultEventExecutor();

	@AfterAll
	public static void afterClass() throws Exception {
		executor.shutdownGracefully()
		        .get(5, TimeUnit.SECONDS);
	}

	@Override
	@SuppressWarnings("deprecation")
	protected HttpClient newClient(int port) {
		ReactorNettyHttpTracing reactorNettyHttpTracing = ReactorNettyHttpTracing.create(httpTracing, s -> null);

		group = new DefaultChannelGroup(executor);
		return reactorNettyHttpTracing.decorateHttpClient(
		        HttpClient.create()
		                  .host("127.0.0.1")
		                  .port(port)
		                  .wiretap(true)
		                  .followRedirect(true)
		                  .disableRetry(true)
		                  .channelGroup(group));
	}

	@Override
	protected void closeClient(HttpClient client) {
		if (group != null) {
			try {
				group.close()
				     .get(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException | ExecutionException | TimeoutException e) {
				fail(e.getMessage());
			}
		}
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
		client.doAfterResponseSuccess((res, conn) -> callback.accept(res.status().code(), null))
		      .doOnError(
		          (req, throwable) -> callback.accept(null, throwable),
		          (res, throwable) -> callback.accept(res.status().code(), throwable))
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
	@SuppressWarnings("try")
	public void currentSpanVisibleToUserHandler() {
		AtomicReference<HttpHeaders> headers = new AtomicReference<>();
		DisposableServer disposableServer = null;
		TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
		try (Scope scope = currentTraceContext.newScope(parent)) {
			disposableServer =
					HttpServer.create()
					          .port(0)
					          .handle((req, res) -> {
					              headers.set(req.requestHeaders());
					              return res.sendString(Mono.just("test"));
					          })
					          .bindNow();

			client.port(disposableServer.port())
			      .request(HttpMethod.GET)
			      .uri("/")
			      .send((req, out) -> {
			          req.header("test-id", currentTraceContext.get().traceIdString());
			          return out;
			      })
			      .responseContent()
			      .aggregate()
			      .block(Duration.ofSeconds(30));

			assertThat(headers.get()).isNotNull();
			assertThat(headers.get().get("x-b3-traceId")).isEqualTo(headers.get().get("test-id"));
		}
		finally {
			if (disposableServer != null) {
				disposableServer.disposeNow();
			}
		}
		testSpanHandler.takeRemoteSpan(CLIENT);
	}

	@Test
	@SuppressWarnings("try")
	public void testIssue1738() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
		try (Scope scope = currentTraceContext.newScope(parent)) {
			client.doOnRequestError((req, conn) -> latch.countDown())
			      .post()
			      .uri("/")
			      .send((req, out) -> {
			          throw new IllegalStateException("not ready");
			      })
			      .responseContent()
			      .aggregate()
			      .subscribe();
		}

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		testSpanHandler.takeRemoteSpanWithErrorMessage(CLIENT, "not ready");
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
}
