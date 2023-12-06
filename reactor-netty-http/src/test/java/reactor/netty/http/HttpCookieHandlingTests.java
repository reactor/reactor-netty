/*
 * Copyright (c) 2017-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.http.server.HttpServerInfos;
import reactor.netty.http.server.HttpServerRequest;
import reactor.test.StepVerifier;

/**
 * This test class verifies HTTP cookie handling.
 *
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
	@SuppressWarnings({"CollectionUndefinedEquality", "deprecation"})
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

	@Test
	void testServerCookiesDecodingMultipleCookiesSameName_Cookies() {
		doTestServerCookiesDecodingMultipleCookiesSameName(HttpServerInfos::cookies, " value1");
	}

	@Test
	void testServerCookiesDecodingMultipleCookiesSameName_AllCookies() {
		doTestServerCookiesDecodingMultipleCookiesSameName(HttpServerInfos::allCookies, " value1 value2");
	}

	@SuppressWarnings("CollectionUndefinedEquality")
	private void doTestServerCookiesDecodingMultipleCookiesSameName(
			Function<HttpServerRequest, Map<CharSequence, ? extends Collection<Cookie>>> cookies,
			String expectedResponse) {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				                res.sendString(Mono.just(cookies.apply(req)
				                                                // Suppressed "CollectionUndefinedEquality",
				                                                // the CharSequence is String
				                                                .get("test")
				                                                .stream()
				                                                .map(Cookie::value)
				                                                .reduce("", (a, b) -> a + " " + b))))
				        .bindNow();

		createClient(disposableServer.port())
		        .headers(h -> h.add(HttpHeaderNames.COOKIE, "test=value1;test=value2"))
		        .get()
		        .uri("/")
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext(expectedResponse)
		        .expectComplete()
		        .verify(Duration.ofSeconds(5));
	}

	@Test
	void testIssue2983() {
		disposableServer =
				createServer()
				        .handle((req, res) -> {
				            List<String> cookies = req.requestHeaders().getAll(HttpHeaderNames.COOKIE);
				            return cookies.size() == 1 ? res.sendString(Mono.just(cookies.get(0))) :
				                    res.sendString(Mono.just("ERROR"));
				        })
				        .bindNow();

		createClient(disposableServer.port())
		        .request(HttpMethod.GET)
		        .uri("/")
		        .send((req, out) -> {
		            Cookie cookie1 = new DefaultCookie("testIssue2983_1", "1");
		            cookie1.setPath("/");
		            Cookie cookie2 = new DefaultCookie("testIssue2983_2", "2");
		            cookie2.setPath("/2");
		            Cookie cookie3 = new DefaultCookie("testIssue2983_3", "3");
		            req.addCookie(cookie1)
		               .addCookie(cookie2)
		               .addCookie(cookie3);
		            return out;
		        })
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext("testIssue2983_3=3; testIssue2983_2=2; testIssue2983_1=1")
		        .expectComplete()
		        .verify(Duration.ofSeconds(5));
	}
}