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
package reactor.netty.http;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

/**
 * @author Violeta Georgieva
 */
public class HttpCookieHandlingTests {

	@Test
	public void clientWillIgnoreMalformedCookies() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .route(r -> r.get("/test", (req, resp) ->
				                resp.addHeader("Set-Cookie", "name:with_colon=value")
				                    .send(req.receive()
				                             .log("server received"))))
				          .wiretap(true)
				          .bindNow();

		Mono<Map<CharSequence, Set<Cookie>>> cookieResponse =
				HttpClient.create()
				          .port(server.port())
				          .wiretap(true)
				          .get()
				          .uri("/test")
				          .responseSingle((res, buf) -> Mono.just(res.cookies()))
				          .doOnSuccess(System.out::println)
				          .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(cookieResponse)
		            .expectNextMatches(l -> !l.containsKey("name:with_colon"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server.disposeNow();
	}

	@Test
	public void clientWithoutCookieGetsANewOneFromServer() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .route(r -> r.get("/test", (req, resp) ->
				                            resp.addCookie(new DefaultCookie("cookie1", "test_value"))
				                                .send(req.receive()
				                                         .log("server received"))))
				          .wiretap(true)
				          .bindNow();

		Mono<Map<CharSequence, Set<Cookie>>> cookieResponse =
				HttpClient.create()
				          .port(server.port())
				          .wiretap(true)
				          .get()
				          .uri("/test")
				          .responseSingle((res, buf) -> Mono.just(res.cookies()))
				          .doOnSuccess(System.out::println)
				          .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(cookieResponse)
				    .expectNextMatches(l -> {
				        Set<Cookie> cookies = l.get("cookie1");
				        return cookies.stream().anyMatch(e -> e.value().equals("test_value"));
				    })
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		server.disposeNow();
	}

	@Test
	public void customCookieEncoderDecoder() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .cookieCodec(ServerCookieEncoder.LAX, ServerCookieDecoder.LAX)
				          .handle((req, res) -> res.addCookie(new DefaultCookie("cookie1", "test_value"))
				                                   .sendString(Mono.just("test")))
				          .wiretap(true)
				          .bindNow();

		Mono<Map<CharSequence, Set<Cookie>>> response =
				HttpClient.create()
				          .port(server.port())
				          .cookieCodec(ClientCookieEncoder.LAX, ClientCookieDecoder.LAX)
				          .wiretap(true)
				          .get()
				          .uri("/")
				          .responseSingle((res, bytes) -> Mono.just(res.cookies()))
				          .doOnSuccess(System.out::println)
				          .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(response)
		            .expectNextMatches(map -> {
		                Set<Cookie> cookies = map.get("cookie1");
		                return cookies.stream().anyMatch(e -> e.value().equals("test_value"));
		            })
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server.disposeNow();
	}
}