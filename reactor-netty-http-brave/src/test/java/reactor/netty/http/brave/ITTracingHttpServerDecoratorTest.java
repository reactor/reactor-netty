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

import brave.test.http.ITHttpServer;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRoutes;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static brave.Span.Kind.SERVER;
import static org.assertj.core.api.Assertions.assertThat;

public class ITTracingHttpServerDecoratorTest extends ITHttpServer {
	private DisposableServer disposableServer;
	private ChannelGroup group;
	private static final EventExecutor executor = new DefaultEventExecutor();

	@AfterAll
	public static void afterClass() throws Exception {
		executor.shutdownGracefully()
		        .get(5, TimeUnit.SECONDS);
	}

	@AfterEach
	@Override
	public void close() throws Exception {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
		if (group != null) {
			group.close()
			     .get(5, TimeUnit.SECONDS);
		}
		super.close();
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void init() {
		HttpServerRoutes routes =
				HttpServerRoutes.newRoutes()
				                .options("/", (req, res) -> res.send())
				                .get("/foo", (req, res) -> res.sendString(Mono.just("bar")))
				                .get("/baggage", (req, res) ->
				                        res.sendString(Mono.just(req.requestHeaders().getAsString(BAGGAGE_FIELD_KEY))))
				                .get("/exception", (req, res) -> Mono.error(NOT_READY_ISE))
				                .get("/badrequest", (req, res) -> res.status(400).send())
				                .get("/async", (req, res) ->
				                        res.sendString(Mono.just("body")
				                                           .publishOn(Schedulers.boundedElastic())))
				                .get("/exceptionAsync", (req, res) -> Mono.error(NOT_READY_ISE)
				                                                          .publishOn(Schedulers.boundedElastic())
				                                                          .then())
				                .get("/items/{itemId}", (req, res) -> res.sendString(Mono.justOrEmpty(req.param("itemId"))))
				                .get("/async_items/{itemId}", (req, res) ->
				                        res.sendString(Mono.justOrEmpty(req.param("itemId"))
				                                           .publishOn(Schedulers.boundedElastic())))
				                .get("/nested/items/{itemId}", (req, res) -> res.sendString(Mono.justOrEmpty(req.param("itemId"))))
				                .get("/child", (req, res) -> {
				                        httpTracing.tracing()
				                                   .tracer()
				                                   .nextSpan()
				                                   .name("child")
				                                   .start()
				                                   .finish();

				                        return res.send();
				                });

		ReactorNettyHttpTracing reactorNettyHttpTracing =
				ReactorNettyHttpTracing.create(
				        httpTracing,
				        s -> {
				            if ("/foo/bark".equals(s)) {
				                return "not_found";
				            }

				            int ind = s.lastIndexOf('/');
				            if (s.length() > 1 && ind > -1) {
				                return s.substring(0, ind);
				            }

				            return s;
				        });

		group = new DefaultChannelGroup(executor);
		disposableServer = reactorNettyHttpTracing.decorateHttpServer(
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .forwarded(true)
				          .channelGroup(group)
				          .handle(routes)).bindNow();
	}

	@Override
	protected String url(String path) {
		return "http://127.0.0.1:" + disposableServer.port() + path;
	}

	@Override
	@Disabled
	public void httpStatusCodeSettable_onUncaughtException() {
		// Reactor Netty always returns 500 ISE when an exception happens
	}

	@Override
	@Disabled
	public void httpStatusCodeSettable_onUncaughtException_async() {
		// Reactor Netty always returns 500 ISE when an exception happens
	}

	@Test
	@SuppressWarnings("deprecation")
	public void testBadRequest() throws IOException {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}

		ReactorNettyHttpTracing reactorNettyHttpTracing = ReactorNettyHttpTracing.create(httpTracing);
		disposableServer = reactorNettyHttpTracing.decorateHttpServer(
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .httpRequestDecoder(spec -> spec.maxInitialLineLength(10))
				          .handle((req, res) -> res.sendString(Mono.just("this code should not be reached")))).bindNow();

		this.get("/request_line_too_long");

		assertThat(testSpanHandler.takeRemoteSpanWithErrorTag(SERVER, "414").tags()).containsEntry("error", "414");
	}
}