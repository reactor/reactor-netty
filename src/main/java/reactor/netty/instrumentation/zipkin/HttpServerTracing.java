/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.netty.instrumentation.zipkin;

import brave.Span;
import brave.Tracing;
import brave.http.HttpServerHandler;
import brave.http.HttpTracing;
import brave.propagation.TraceContext.Extractor;
import io.netty.handler.codec.http.HttpHeaders;
import java.util.Objects;
import java.util.function.BiFunction;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.util.annotation.NonNull;

/**
 * Tracing adapter for HTTP servers.
 *
 * <p></p>
 *
 * <code><pre>
 *   Tracing tracing = Tracing.newBuilder()
 *         ...
 *         .build();
 *   HttpServerTracing serverTracing = HttpServerTracing.create(tracing);
 *
 *   DisposableServer c = HttpServer.create()
 *     .port(0)
 *     .handle(serverTracing.andThen(myHandler))
 *     .bindNow();
 * </pre></code>
 */
public class HttpServerTracing {
  private static final HttpCarrier GETTER = new HttpCarrier();

  private final Extractor<HttpHeaders> extractor;
  private final HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;

  /**
   * Create a new tracing adapter using preconfigured {@link HttpTracing} instance.
   */
  public HttpServerTracing(@NonNull HttpTracing httpTracing) {
    Objects.requireNonNull(httpTracing, "httpTracing cannot be null");

    this.extractor = httpTracing.tracing().propagation().extractor(GETTER);
    this.handler = HttpServerHandler.create(httpTracing, new HttpServerAdapter());
  }

  /**
   * Create a new tracing adapter.
   */
  public static HttpServerTracing create(@NonNull Tracing tracing) {
    Objects.requireNonNull(tracing, "tracing cannot be null");
    return new HttpServerTracing(HttpTracing.create(tracing));
  }

  /**
   * Add HTTP tracing functionality to the given handler.
   * @param next a HTTP handler
   */
  public BiFunction<
      ? super HttpServerRequest,
      ? super HttpServerResponse,
      ? extends Publisher<Void>
  > andThen(
      BiFunction<
          ? super HttpServerRequest,
          ? super HttpServerResponse,
          ? extends Publisher<Void>
      > next) {
    Objects.requireNonNull(next, "next cannot be null");

    return (request, response) -> {
      final Span span = handler.handleReceive(extractor, request.requestHeaders(), request);

      return Mono
          .defer(() -> Mono.from(next.apply(request, response)))
          .doOnCancel(() -> span.annotate("cancel").finish())
          .doOnSuccess(_void -> handler.handleSend(response, null, span))
          .doOnError(err -> handler.handleSend(response, err, span))
          .subscriberContext(ctx -> TracingContext.of(ctx).put(span));
    };
  }
}
