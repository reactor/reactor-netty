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

import io.netty.buffer.ByteBuf;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.ByteBufFlux;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientConfig;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerConfig;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test a combination of {@link HttpServer} + {@link HttpProtocol}
 * with a combination of {@link HttpClient} + {@link HttpProtocol}
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
@RunWith(Parameterized.class)
public class HttpProtocolsTests {

	@Parameter
	public HttpServer server;

	@Parameter(1)
	public HttpClient client;

	@Parameters(name = "{index}: {0}, {1}")
	public static Object[][] data() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverCtx = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		SslContextBuilder clientCtx = SslContextBuilder.forClient()
		                                               .trustManager(InsecureTrustManagerFactory.INSTANCE);

		HttpServer _server = HttpServer.create()
		                               .wiretap(true)
		                               .httpRequestDecoder(spec -> spec.h2cMaxContentLength(256));
		HttpServer securedServer = _server.secure(spec -> spec.sslContext(serverCtx));

		HttpServer[] servers = new HttpServer[]{
				_server, // by default protocol is HTTP/1.1
				_server.protocol(HttpProtocol.H2C),
				_server.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C),
				securedServer, // by default protocol is HTTP/1.1
				securedServer.protocol(HttpProtocol.H2),
				securedServer.protocol(HttpProtocol.HTTP11, HttpProtocol.H2)
		};

		HttpClient _client = HttpClient.create()
		                               .wiretap(true);
		HttpClient securedClient = _client.secure(spec -> spec.sslContext(clientCtx));

		HttpClient[] clients = new HttpClient[]{
				_client, // by default protocol is HTTP/1.1
				_client.protocol(HttpProtocol.H2C),
				_client.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C),
				securedClient, // by default protocol is HTTP/1.1
				securedClient.protocol(HttpProtocol.H2),
				securedClient.protocol(HttpProtocol.HTTP11, HttpProtocol.H2)
		};

		Flux<HttpServer> f1 = Flux.fromArray(servers).concatMap(o -> Flux.just(o).repeat(clients.length - 1));
		Flux<HttpClient> f2 = Flux.fromArray(clients).repeat(servers.length - 1);

		return Flux.zip(f1, f2)
		           .map(Tuple2::toArray)
		           .collectList()
		           .block(Duration.ofSeconds(30))
		           .toArray(new Object[servers.length * clients.length][2]);
	}

	@Test
	public void testProtocolVariationsGetRequest() {
		HttpServerConfig serverConfig = server.configuration();
		HttpClientConfig clientConfig = client.configuration();
		List<HttpProtocol> serverProtocols = Arrays.asList(serverConfig.protocols());
		List<HttpProtocol> clientProtocols = Arrays.asList(clientConfig.protocols());

		DisposableServer disposableServer = null;
		try {
			disposableServer =
					server.port(0)
					      .handle((req, res) -> {
					          boolean secure = "https".equals(req.scheme());
					          if (serverConfig.isSecure() != secure) {
					              return res.status(400).send();
					          }
					          return res.sendString(Mono.just("Hello"));
					      })
					      .bindNow();

			Mono<String> response =
					client.port(disposableServer.port())
					      .get()
					      .uri("/")
					      .responseContent()
					      .aggregate()
					      .asString();

			if (serverConfig.isSecure() != clientConfig.isSecure()) {
				StepVerifier.create(response)
				            .expectError()
				            .verify(Duration.ofSeconds(30));
			}
			else if (serverProtocols.size() == 1 && serverProtocols.get(0) == HttpProtocol.H2C && clientProtocols.size() == 2) {
				StepVerifier.create(response)
				            .expectError()
				            .verify(Duration.ofSeconds(30));
			}
			else if (serverProtocols.containsAll(clientProtocols) || clientProtocols.containsAll(serverProtocols)) {
				StepVerifier.create(response)
				            .expectNext("Hello")
				            .expectComplete()
				            .verify(Duration.ofSeconds(30));
			}
			else {
				StepVerifier.create(response)
				            .expectError()
				            .verify(Duration.ofSeconds(30));
			}
		}
		finally {
			assertThat(disposableServer).isNotNull();
			disposableServer.disposeNow();
		}
	}

	@Test
	public void testProtocolVariationsPostRequest_1() {
		doTestProtocolVariationsPostRequest(false);
	}

	@Test
	public void testProtocolVariationsPostRequest_2() {
		doTestProtocolVariationsPostRequest(true);
	}

	public void doTestProtocolVariationsPostRequest(boolean externalThread) {
		HttpServerConfig serverConfig = server.configuration();
		HttpClientConfig clientConfig = client.configuration();
		List<HttpProtocol> serverProtocols = Arrays.asList(serverConfig.protocols());
		List<HttpProtocol> clientProtocols = Arrays.asList(clientConfig.protocols());

		DisposableServer disposableServer = null;
		try {
			disposableServer =
					server.port(0)
					      .handle((req, res) -> {
					          boolean secure = "https".equals(req.scheme());
					          if (serverConfig.isSecure() != secure) {
					              return res.status(400).send();
					          }
					          Flux<ByteBuf> publisher = req.receive().retain();
					          if (externalThread) {
					              publisher = publisher.subscribeOn(Schedulers.boundedElastic());
					          }
					          return res.send(publisher);
					      })
					      .bindNow();

			Mono<String> response =
					client.port(disposableServer.port())
					      .post()
					      .uri("/")
					      .send(ByteBufFlux.fromString(Mono.just("Hello")))
					      .responseContent()
					      .aggregate()
					      .asString();

			if (serverConfig.isSecure() != clientConfig.isSecure()) {
					StepVerifier.create(response)
					            .expectError()
					            .verify(Duration.ofSeconds(30));
			}
			else if (serverProtocols.size() == 1 && serverProtocols.get(0) == HttpProtocol.H2C && clientProtocols.size() == 2) {
				StepVerifier.create(response)
				            .expectError()
				            .verify(Duration.ofSeconds(30));
			}
			else if (serverProtocols.containsAll(clientProtocols) || clientProtocols.containsAll(serverProtocols)) {
				StepVerifier.create(response)
				            .expectNext("Hello")
				            .expectComplete()
				            .verify(Duration.ofSeconds(30));
			}
			else {
				StepVerifier.create(response)
				            .expectError()
				            .verify(Duration.ofSeconds(30));
			}
		}
		finally {
			assertThat(disposableServer).isNotNull();
			disposableServer.disposeNow();
		}
	}
}
