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
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.test.StepVerifier;

/**
 * @author Violeta Georgieva
 */
class HttpCookieHandlingTests extends BaseHttpTest {

	@Test
	@SuppressWarnings("CollectionUndefinedEquality")
	void clientWillIgnoreMalformedCookies() {
		disposableServer =
				createServer()
				          .route(r -> r.get("/test", (req, resp) ->
				                resp.addHeader("Set-Cookie", "name:with_colon=value")
				                    .send(req.receive()
				                             .log("server received"))))
				          .bindNow();

		Mono<Map<CharSequence, Set<Cookie>>> cookieResponse =
				createClient(disposableServer.port())
				          .get()
				          .uri("/test")
				          .responseSingle((res, buf) -> Mono.just(res.cookies()))
				          .doOnSuccess(System.out::println)
				          .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(cookieResponse)
		            // Suppressed "CollectionUndefinedEquality", the CharSequence is String
		            .expectNextMatches(l -> !l.containsKey("name:with_colon"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("CollectionUndefinedEquality")
	void clientWithoutCookieGetsANewOneFromServer() {
		disposableServer =
				createServer()
				          .route(r -> r.get("/test", (req, resp) ->
				                            resp.addCookie(new DefaultCookie("cookie1", "test_value"))
				                                .send(req.receive()
				                                         .log("server received"))))
				          .bindNow();

		Mono<Map<CharSequence, Set<Cookie>>> cookieResponse =
				createClient(disposableServer.port())
				          .get()
				          .uri("/test")
				          .responseSingle((res, buf) -> Mono.just(res.cookies()))
				          .doOnSuccess(System.out::println)
				          .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(cookieResponse)
				    .expectNextMatches(l -> {
				        // Suppressed "CollectionUndefinedEquality", the CharSequence is String
				        Set<Cookie> cookies = l.get("cookie1");
				        return cookies.stream().anyMatch(e -> e.value().equals("test_value"));
				    })
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("CollectionUndefinedEquality")
	void customCookieEncoderDecoder() {
		disposableServer =
				createServer()
				          .cookieCodec(ServerCookieEncoder.LAX, ServerCookieDecoder.LAX)
				          .handle((req, res) -> res.addCookie(new DefaultCookie("cookie1", "test_value"))
				                                   .sendString(Mono.just("test")))
				          .bindNow();

		Mono<Map<CharSequence, Set<Cookie>>> response =
				createClient(disposableServer.port())
				          .cookieCodec(ClientCookieEncoder.LAX, ClientCookieDecoder.LAX)
				          .get()
				          .uri("/")
				          .responseSingle((res, bytes) -> Mono.just(res.cookies()))
				          .doOnSuccess(System.out::println)
				          .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(response)
		            .expectNextMatches(map -> {
		                // Suppressed "CollectionUndefinedEquality", the CharSequence is String
		                Set<Cookie> cookies = map.get("cookie1");
		                return cookies.stream().anyMatch(e -> e.value().equals("test_value"));
		            })
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}
}