/*
 * Copyright (c) 2017-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.headers.DefaultHttpSetCookie;
import io.netty5.handler.codec.http.headers.HttpCookiePair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty5.BaseHttpTest;
import reactor.netty5.http.server.HttpServerInfos;
import reactor.netty5.http.server.HttpServerRequest;
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
				                             .transferOwnership()
				                             .log("server received"))))
				          .bindNow();

		Mono<Map<CharSequence, Set<HttpCookiePair>>> cookieResponse =
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
				                            resp.addCookie(new DefaultHttpSetCookie("cookie1", "test_value"))
				                                .send(req.receive()
				                                         .transferOwnership()
				                                         .log("server received"))))
				          .bindNow();

		Mono<Map<CharSequence, Set<HttpCookiePair>>> cookieResponse =
				createClient(disposableServer.port())
				          .get()
				          .uri("/test")
				          .responseSingle((res, buf) -> Mono.just(res.cookies()))
				          .doOnSuccess(System.out::println)
				          .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(cookieResponse)
				    .expectNextMatches(l -> {
				        // Suppressed "CollectionUndefinedEquality", the CharSequence is String
				        Set<HttpCookiePair> cookies = l.get("cookie1");
				        return cookies.stream().anyMatch(e -> e.value().toString().equals("test_value"));
				    })
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("CollectionUndefinedEquality")
	void customCookieEncoderDecoder() {
		disposableServer =
				createServer()
				          //.cookieCodec(ServerCookieEncoder.LAX, ServerCookieDecoder.LAX)
				          .handle((req, res) -> res.addCookie(new DefaultHttpSetCookie("cookie1", "test_value"))
				                                   .sendString(Mono.just("test")))
				          .bindNow();

		Mono<Map<CharSequence, Set<HttpCookiePair>>> response =
				createClient(disposableServer.port())
				          //.cookieCodec(ClientCookieEncoder.LAX, ClientCookieDecoder.LAX)
				          .get()
				          .uri("/")
				          .responseSingle((res, bytes) -> Mono.just(res.cookies()))
				          .doOnSuccess(System.out::println)
				          .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(response)
		            .expectNextMatches(map -> {
		                // Suppressed "CollectionUndefinedEquality", the CharSequence is String
		                Set<HttpCookiePair> cookies = map.get("cookie1");
		                return cookies.stream().anyMatch(e -> e.value().toString().equals("test_value"));
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
			Function<HttpServerRequest, Map<CharSequence, ? extends Collection<HttpCookiePair>>> cookies,
			String expectedResponse) {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				                res.sendString(Mono.just(cookies.apply(req)
				                                                // Suppressed "CollectionUndefinedEquality",
				                                                // the CharSequence is String
				                                                .get("test")
				                                                .stream()
				                                                .map(HttpCookiePair::value)
				                                                .map(charSequence -> charSequence.toString())
				                                                .reduce("", (a, b) -> a + " " + b))))
				        .bindNow();

		// the new parser seems to refuse to parse a Cookie header where multiple cookies are not space separated
		// (and indeed, from https://www.rfc-editor.org/rfc/rfc6265#section-4.2.1, cookie pairs should space separated)
		createClient(disposableServer.port())
		        .headers(h -> h.add(HttpHeaderNames.COOKIE, "test=value1; test=value2"))
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
}