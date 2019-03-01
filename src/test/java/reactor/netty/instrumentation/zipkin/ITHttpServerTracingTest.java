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

import static org.assertj.core.api.Assertions.assertThat;

import brave.test.http.ITHttpServer;
import org.junit.After;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRoutes;

public class ITHttpServerTracingTest extends ITHttpServer {
  private DisposableServer server;

  @After
  public void after() {
    if (server != null) {
      server.disposeNow();
    }
  }

  @Override
  protected void init() {
    HttpServerTracing serverTracing = new HttpServerTracing(httpTracing);

    HttpServerRoutes routes = HttpServerRoutes.newRoutes()
        .options("/", (req, res) ->
            res.send()
        )
        .get("/foo", (req, res) ->
            res.sendString(Mono.just("bar"))
        )
        .get("/extra", (req, res) ->
            res.sendString(Mono.just(req.requestHeaders().getAsString(EXTRA_KEY)))
        )
        .get("/exception", (req, res) -> {
          throw new RuntimeException("boom");
        })
        .get("/badrequest", (req, res) ->
            res.status(400).send()
        )
        .get("/async", (req, res) -> {
          Mono<String> body = Mono
              .subscriberContext()
              .flatMap(ctx -> {
                assertThat(TracingContext.of(ctx).span()).isNotNull();
                return Mono.just("body");
              });

          return Mono.subscriberContext()
              .flatMap(ctx -> {
                assertThat(TracingContext.of(ctx).span()).isNotNull();
                return res.sendString(body).then();
              });
        })
        .get("/exceptionAsync", (req, res) ->
          Mono.error(new RuntimeException("boom"))
        )
        .get("/items/{itemId}", (req, res) -> {
          String itemId = req.param("itemId");
          return res.routeName("/items").sendString(Mono.just(itemId));
        })
        .get("/async_items/{itemId}", (req, res) -> {
          String itemId = req.param("itemId");
          return res.routeName("/async_items").sendString(Mono.just(itemId));
        })
        .get("/nested/items/{itemId}", (req, res) -> {
          String itemId = req.param("itemId");
          return res.routeName("/nested/items").sendString(Mono.just(itemId));
        })
        .get("/child", (req, res) ->
          Mono.subscriberContext()
              .flatMap(ctx -> {
                TracingContext.of(ctx).span(span ->
                    httpTracing.tracing().tracer()
                        .newChild(span.context())
                        .start()
                        .finish()
                );
                return res.send();
              })
        );

    server = HttpServer.create()
        .port(0)
        .handle(serverTracing.andThen(routes))
        .bindNow();
  }

  @Override
  protected String url(String path) {
    return "http://127.0.0.1:" + server.port() + path;
  }
}
