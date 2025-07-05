/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.netty.handler.codec.http.HttpHeaderNames;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosPrincipal;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

class SpnegoAuthProviderTest {

	private static final int TEST_PORT = 8080;

	@Test
	void negotiateSpnegoAuthenticationWithHttpClient() throws GSSException {
		DisposableServer server = HttpServer.create()
				.port(TEST_PORT)
				.route(routes -> routes
						.get("/", (request, response) -> {
							String authHeader = request.requestHeaders().get(HttpHeaderNames.AUTHORIZATION);
							if (authHeader != null && authHeader.startsWith("Negotiate ")) {
								return response.status(200).sendString(Mono.just("Authenticated"));
							}
							return response.status(401).sendString(Mono.just("Unauthorized"));
						}))
				.bindNow();

		try {
			GSSManager gssManager = mock(GSSManager.class);
			GSSContext gssContext = mock(GSSContext.class);
			GSSName gssName = mock(GSSName.class);
			Oid oid = new Oid("1.3.6.1.5.5.2");

			given(gssContext.initSecContext(any(byte[].class), anyInt(), anyInt()))
					.willReturn("spnego-negotiate-token".getBytes(StandardCharsets.UTF_8));
			given(gssManager.createName(any(String.class), eq(GSSName.NT_HOSTBASED_SERVICE)))
					.willReturn(gssName);
			given(gssManager.createContext(eq(gssName), eq(oid), isNull(), anyInt()))
					.willReturn(gssContext);

			HttpClient client = HttpClient.create()
					.port(TEST_PORT)
					.spnego(
							SpnegoAuthProvider.create(
									() -> {
										Set<Principal> principals = new HashSet<>();
										principals.add(new KerberosPrincipal("test@LOCALHOST"));
										return new Subject(true, principals, new HashSet<>(), new HashSet<>());
									},
									gssManager,
									401
							)
					)
					.wiretap(true)
					.disableRetry(true);

			StepVerifier.create(
							client.get()
									.uri("/")
									.responseContent()
									.aggregate()
									.asString()
					)
					.expectNext("Authenticated")
					.verifyComplete();
		}
		finally {
			server.disposeNow();
		}
	}

	@Test
	void automaticReauthenticateOn401Response() throws GSSException {
		AtomicInteger requestCount = new AtomicInteger(0);

		DisposableServer server = HttpServer.create()
				.port(0)
				.route(routes -> routes
						.get("/reauth", (request, response) -> {
							String authHeader = request.requestHeaders().get(HttpHeaderNames.AUTHORIZATION);
							int count = requestCount.incrementAndGet();

							if (count == 1) {
								return response.status(401)
										.header("WWW-Authenticate", "Negotiate")
										.sendString(Mono.just("Unauthorized"));
							}
							else if (authHeader != null && authHeader.startsWith("Negotiate ")) {
								return response.status(200).sendString(Mono.just("Reauthenticated"));
							}
							return response.status(401).sendString(Mono.just("Failed"));
						}))
				.bindNow();

		try {
			GSSManager gssManager = mock(GSSManager.class);
			GSSContext gssContext = mock(GSSContext.class);
			GSSName gssName = mock(GSSName.class);
			Oid oid = new Oid("1.3.6.1.5.5.2");

			given(gssContext.initSecContext(any(byte[].class), anyInt(), anyInt()))
					.willReturn("spnego-reauth-token".getBytes(StandardCharsets.UTF_8));
			given(gssManager.createName(any(String.class), eq(GSSName.NT_HOSTBASED_SERVICE)))
					.willReturn(gssName);
			given(gssManager.createContext(eq(gssName), eq(oid), isNull(), anyInt()))
					.willReturn(gssContext);

			HttpClient client = HttpClient.create()
					.port(server.port())
					.spnego(
							SpnegoAuthProvider.create(
									() -> {
										Set<Principal> principals = new HashSet<>();
										principals.add(new KerberosPrincipal("test@LOCALHOST"));
										return new Subject(true, principals, new HashSet<>(), new HashSet<>());
									},
									gssManager,
									401
							)
					)
					.wiretap(true)
					.disableRetry(true);

			StepVerifier.create(
							client.get()
									.uri("/reauth")
									.responseContent()
									.aggregate()
									.asString()
					)
					.expectNext("Reauthenticated")
					.verifyComplete();

			verify(gssContext, times(2)).initSecContext(any(byte[].class), anyInt(), anyInt());
		}
		finally {
			server.disposeNow();
		}
	}

	@Test
	void doesNotReauthenticateWhenMaxRetryReached() throws GSSException {
		AtomicInteger requestCount = new AtomicInteger(0);

		DisposableServer server = HttpServer.create()
				.port(0)
				.route(routes -> routes
						.get("/fail", (request, response) -> {
							requestCount.incrementAndGet();
							return response.status(401)
									.header("WWW-Authenticate", "Negotiate")
									.sendString(Mono.just("Always Unauthorized"));
						}))
				.bindNow();

		try {
			GSSManager gssManager = mock(GSSManager.class);
			GSSContext gssContext = mock(GSSContext.class);
			GSSName gssName = mock(GSSName.class);
			Oid oid = new Oid("1.3.6.1.5.5.2");

			given(gssContext.initSecContext(any(byte[].class), anyInt(), anyInt()))
					.willReturn("spnego-fail-token".getBytes(StandardCharsets.UTF_8));
			given(gssManager.createName(any(String.class), eq(GSSName.NT_HOSTBASED_SERVICE)))
					.willReturn(gssName);
			given(gssManager.createContext(eq(gssName), eq(oid), isNull(), anyInt()))
					.willReturn(gssContext);

			HttpClient client = HttpClient.create()
					.port(server.port())
					.spnego(
							SpnegoAuthProvider.create(
									() -> {
										Set<Principal> principals = new HashSet<>();
										principals.add(new KerberosPrincipal("test@LOCALHOST"));
										return new Subject(true, principals, new HashSet<>(), new HashSet<>());
									},
									gssManager,
									401
							)
					)
					.wiretap(true)
					.disableRetry(true);

			StepVerifier.create(
							client.get()
									.uri("/fail")
									.response()
									.map(response -> response.status().code())
					)
					.expectNext(401)
					.verifyComplete();

			verify(gssContext, times(2)).initSecContext(any(byte[].class), anyInt(), anyInt());
		}
		finally {
			server.disposeNow();
		}
	}

	@Test
	void doesNotReauthenticateWithoutWwwAuthenticateHeader() throws GSSException {
		DisposableServer server = HttpServer.create()
				.port(0)
				.route(routes -> routes
						.get("/noheader", (request, response) ->
								response.status(401).sendString(Mono.just("No WWW-Authenticate header"))))
				.bindNow();

		try {
			GSSManager gssManager = mock(GSSManager.class);
			GSSContext gssContext = mock(GSSContext.class);
			GSSName gssName = mock(GSSName.class);
			Oid oid = new Oid("1.3.6.1.5.5.2");

			given(gssContext.initSecContext(any(byte[].class), anyInt(), anyInt()))
					.willReturn("spnego-token".getBytes(StandardCharsets.UTF_8));
			given(gssManager.createName(any(String.class), eq(GSSName.NT_HOSTBASED_SERVICE)))
					.willReturn(gssName);
			given(gssManager.createContext(eq(gssName), eq(oid), isNull(), anyInt()))
					.willReturn(gssContext);

			HttpClient client = HttpClient.create()
					.port(server.port())
					.spnego(
							SpnegoAuthProvider.create(
									() -> {
										Set<Principal> principals = new HashSet<>();
										principals.add(new KerberosPrincipal("test@LOCALHOST"));
										return new Subject(true, principals, new HashSet<>(), new HashSet<>());
									},
									gssManager,
									401
							)
					)
					.wiretap(true)
					.disableRetry(true);

			StepVerifier.create(
							client.get()
									.uri("/noheader")
									.response()
									.map(response -> response.status().code())
					)
					.expectNext(401)
					.verifyComplete();

			verify(gssContext, times(1)).initSecContext(any(byte[].class), anyInt(), anyInt());
		}
		finally {
			server.disposeNow();
		}
	}

	@Test
	void successfulAuthenticationResetsRetryCount() throws GSSException {
		AtomicInteger requestCount = new AtomicInteger(0);

		DisposableServer server = HttpServer.create()
				.port(0)
				.route(routes -> routes
						.get("/reset", (request, response) -> {
							String authHeader = request.requestHeaders().get(HttpHeaderNames.AUTHORIZATION);
							int count = requestCount.incrementAndGet();

							if (count == 1) {
								return response.status(401)
										.header("WWW-Authenticate", "Negotiate")
										.sendString(Mono.just("First 401"));
							}
							else if (authHeader != null && authHeader.startsWith("Negotiate ")) {
								return response.status(200).sendString(Mono.just("Success"));
							}
							return response.status(401).sendString(Mono.just("Unexpected"));
						}))
				.bindNow();

		try {
			GSSManager gssManager = mock(GSSManager.class);
			GSSContext gssContext = mock(GSSContext.class);
			GSSName gssName = mock(GSSName.class);
			Oid oid = new Oid("1.3.6.1.5.5.2");

			given(gssContext.initSecContext(any(byte[].class), anyInt(), anyInt()))
					.willReturn("spnego-reset-token".getBytes(StandardCharsets.UTF_8));
			given(gssManager.createName(any(String.class), eq(GSSName.NT_HOSTBASED_SERVICE)))
					.willReturn(gssName);
			given(gssManager.createContext(eq(gssName), eq(oid), isNull(), anyInt()))
					.willReturn(gssContext);

			SpnegoAuthProvider provider = SpnegoAuthProvider.create(
					() -> {
						Set<Principal> principals = new HashSet<>();
						principals.add(new KerberosPrincipal("test@LOCALHOST"));
						return new Subject(true, principals, new HashSet<>(), new HashSet<>());
					},
					gssManager,
					401
			);

			HttpClient client = HttpClient.create()
					.port(server.port())
					.spnego(provider)
					.wiretap(true)
					.disableRetry(true);

			StepVerifier.create(
							client.get()
									.uri("/reset")
									.responseContent()
									.aggregate()
									.asString()
					)
					.expectNext("Success")
					.verifyComplete();

			requestCount.set(0);

			StepVerifier.create(
							client.get()
									.uri("/reset")
									.responseContent()
									.aggregate()
									.asString()
					)
					.expectNext("Success")
					.verifyComplete();

			verify(gssContext, times(3)).initSecContext(any(byte[].class), anyInt(), anyInt());
		}
		finally {
			server.disposeNow();
		}
	}
}
