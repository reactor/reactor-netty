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
package reactor.netty.http.server.logging.error;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.LogTracker;
import reactor.netty.http.Http3SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorLogTest {

	static final String CUSTOM_FORMAT = "method={}, uri={}";

	static X509Bundle ssc;

	@Nullable DisposableServer disposableServer;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
	}

	@AfterEach
	void tearDown() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
	}

	@Test
	void errorLogDefaultFormat() throws Exception {
		testErrorLogDefaultFormat(
				server -> server.handle((req, res) -> {
					res.withConnection(conn -> conn.channel().pipeline().fireExceptionCaught(new RuntimeException()));
					return res.send();
				}));
	}

	@Test
	void errorLogDefaultFormatWhenReactivePipelineThrowsException() throws Exception {
		testErrorLogDefaultFormat(server -> server.handle((req, res) -> Mono.error(new RuntimeException())));
	}

	@Test
	void errorLogDefaultFormatWhenUnhandledThrowsException() throws Exception {
		testErrorLogDefaultFormat(
				server -> server.handle((req, res) -> {
					throw new RuntimeException();
				}));
	}

	@Test
	void errorLogDefaultFormatWhenReactivePipelineThrowsExceptionInRoute() throws Exception {
		testErrorLogDefaultFormat(server -> server.route(r -> r.get("/example/test", (req, res) -> Mono.error(new RuntimeException()))));
	}

	@Test
	void errorLogDefaultFormatWhenUnhandledThrowsExceptionInRoute() throws Exception {
		testErrorLogDefaultFormat(
				server -> server.route(r -> r.get("/example/test", (req, res) -> {
					throw new RuntimeException();
				})));
	}

	void testErrorLogDefaultFormat(Function<HttpServer, HttpServer> serverCustomizer) throws Exception {
		try (LogTracker logTracker = new LogTracker("reactor.netty.http.server.ErrorLog", "java.lang.RuntimeException")) {
			disposableServer = serverCustomizer.apply(createServer()).errorLog(true).bindNow();

			getHttpClientResponse(createClient(disposableServer.port()), "/example/test");

			assertThat(logTracker.latch.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(logTracker.actualMessages).hasSize(1);
			logTracker.actualMessages.forEach(e -> {
				assertThat(e.getMessage()).isEqualTo(BaseErrorLogHandler.DEFAULT_LOG_FORMAT);
				assertThat(e.getFormattedMessage())
						.matches("\\[(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})[+-]\\d{4}] \\[pid (\\d+)] \\[client ([0-9a-fA-F:]+(?:%[a-zA-Z0-9]+)?|\\d+\\.\\d+\\.\\d+\\.\\d+)(?::\\d+)?] java.lang.RuntimeException");
			});
		}
	}

	@Test
	void errorLogCustomFormat() throws Exception {
		String msg = "method=GET, uri=/example/test";
		try (LogTracker logTracker = new LogTracker("reactor.netty.http.server.ErrorLog", msg)) {
			disposableServer =
					createServer()
					        .handle((req, resp) -> {
					            resp.withConnection(conn -> conn.channel().pipeline().fireExceptionCaught(new RuntimeException()));
					            return resp.send();
					        })
					        .errorLog(true, args -> ErrorLog.create(CUSTOM_FORMAT, args.httpServerInfos().method(), args.httpServerInfos().uri()))
					        .bindNow();

			getHttpClientResponse(createClient(disposableServer.port()), "/example/test");

			assertThat(logTracker.latch.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(logTracker.actualMessages).hasSize(1);
			logTracker.actualMessages.forEach(e -> {
				assertThat(e.getMessage()).isEqualTo(CUSTOM_FORMAT);
				assertThat(e.getFormattedMessage()).isEqualTo(msg);
			});
		}
	}

	@Test
	void secondCallToErrorLogOverridesPreviousOne() throws Exception {
		try (LogTracker logTracker = new LogTracker("reactor.netty.http.server.ErrorLog")) {
			disposableServer =
					createServer()
					        .handle((req, resp) -> {
					            resp.withConnection(conn -> conn.channel().pipeline().fireExceptionCaught(new RuntimeException()));
					            return resp.send();
					        })
					        .errorLog(true, args -> ErrorLog.create(CUSTOM_FORMAT, args.httpServerInfos().method(), args.httpServerInfos().uri()))
					        .errorLog(false)
					        .bindNow();

			getHttpClientResponse(createClient(disposableServer.port()), "/example/test");

			assertThat(logTracker.latch.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(logTracker.actualMessages).hasSize(0);
		}
	}

	@Test
	void errorLogFilteringAndFormatting() throws Exception {
		String msg = "method=GET, uri=/filtered/test";
		try (LogTracker logTracker = new LogTracker("reactor.netty.http.server.ErrorLog", msg)) {
			disposableServer =
					createServer()
					        .handle((req, resp) -> {
					            resp.withConnection(conn -> conn.channel().pipeline().fireExceptionCaught(new RuntimeException()));
					            return resp.send();
					        })
					        .errorLog(true, ErrorLogFactory.createFilter(
					                p -> p.httpServerInfos().uri().startsWith("/filtered"),
					                args -> ErrorLog.create(CUSTOM_FORMAT, args.httpServerInfos().method(), args.httpServerInfos().uri())))
					        .bindNow();

			HttpClient httpClient = createClient(disposableServer.port());
			getHttpClientResponse(httpClient, "/example/test");
			getHttpClientResponse(httpClient, "/filtered/test");

			assertThat(logTracker.latch.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(logTracker.actualMessages).hasSize(1);
			logTracker.actualMessages.forEach(e -> {
				assertThat(e.getMessage()).isEqualTo(CUSTOM_FORMAT);
				assertThat(e.getFormattedMessage()).isEqualTo(msg);
			});
		}
	}

	private static void getHttpClientResponse(HttpClient client, String uri) {
		try {
			client.get()
			      .uri(uri)
			      .response()
			      .block(Duration.ofSeconds(30));
		}
		catch (Exception e) {
			// ignore
		}
	}

	static HttpServer createServer() throws Exception {
		Http3SslContextSpec serverCtx = Http3SslContextSpec.forServer(ssc.toTempPrivateKeyPem(), null, ssc.toTempCertChainPem());
		return HttpServer.create()
		                 .port(0)
		                 .wiretap(true)
		                 .protocol(HttpProtocol.HTTP3)
		                 .secure(spec -> spec.sslContext(serverCtx))
		                 .http3Settings(spec -> spec.idleTimeout(Duration.ofSeconds(5))
		                                            .maxData(10000000)
		                                            .maxStreamDataBidirectionalLocal(1000000)
		                                            .maxStreamDataBidirectionalRemote(1000000)
		                                            .maxStreamsBidirectional(100)
		                                            .tokenHandler(InsecureQuicTokenHandler.INSTANCE));
	}

	static HttpClient createClient(int port) {
		Http3SslContextSpec clientCtx =
				Http3SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		return HttpClient.create()
		                 .port(port)
		                 .wiretap(true)
		                 .protocol(HttpProtocol.HTTP3)
		                 .secure(spec -> spec.sslContext(clientCtx))
		                 .http3Settings(spec -> spec.idleTimeout(Duration.ofSeconds(5))
		                                            .maxData(10000000)
		                                            .maxStreamDataBidirectionalLocal(1000000));
	}
}
