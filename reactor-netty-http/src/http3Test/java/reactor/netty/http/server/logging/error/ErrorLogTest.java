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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http3SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.server.logging.error.DefaultErrorLog.LOGGER;

class ErrorLogTest {

	static final Logger ROOT = (Logger) LoggerFactory.getLogger(LOGGER.getName());
	static final String CUSTOM_FORMAT = "method={}, uri={}";

	static SelfSignedCertificate ssc;

	DisposableServer disposableServer;

	private Appender<ILoggingEvent> mockedAppender;
	private ArgumentCaptor<LoggingEvent> loggingEventArgumentCaptor;

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		mockedAppender = (Appender<ILoggingEvent>) Mockito.mock(Appender.class);
		loggingEventArgumentCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
		Mockito.when(mockedAppender.getName()).thenReturn("MOCK");
		ROOT.addAppender(mockedAppender);
	}

	@AfterEach
	void tearDown() {
		ROOT.detachAppender(mockedAppender);
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
	}

	@Test
	void errorLogDefaultFormat() {
		testErrorLogDefaultFormat(
				server -> server.handle((req, res) -> {
					res.withConnection(conn -> conn.channel().pipeline().fireExceptionCaught(new RuntimeException()));
					return res.send();
				}));
	}

	@Test
	void errorLogDefaultFormatWhenReactivePipelineThrowsException() {
		testErrorLogDefaultFormat(server -> server.handle((req, res) -> Mono.error(new RuntimeException())));
	}

	@Test
	void errorLogDefaultFormatWhenUnhandledThrowsException() {
		testErrorLogDefaultFormat(
				server -> server.handle((req, res) -> {
					throw new RuntimeException();
				}));
	}

	@Test
	void errorLogDefaultFormatWhenReactivePipelineThrowsExceptionInRoute() {
		testErrorLogDefaultFormat(server -> server.route(r -> r.get("/example/test", (req, res) -> Mono.error(new RuntimeException()))));
	}

	@Test
	void errorLogDefaultFormatWhenUnhandledThrowsExceptionInRoute() {
		testErrorLogDefaultFormat(
				server -> server.route(r -> r.get("/example/test", (req, res) -> {
					throw new RuntimeException();
				})));
	}

	void testErrorLogDefaultFormat(Function<HttpServer, HttpServer> serverCustomizer) {
		disposableServer = serverCustomizer.apply(createServer()).errorLog(true).bindNow();

		getHttpClientResponse(createClient(disposableServer.port()), "/example/test");

		Mockito.verify(mockedAppender, Mockito.times(1)).doAppend(loggingEventArgumentCaptor.capture());
		assertThat(loggingEventArgumentCaptor.getAllValues()).hasSize(1);

		LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(0);
		assertThat(relevantLog.getMessage()).isEqualTo(BaseErrorLogHandler.DEFAULT_LOG_FORMAT);
		assertThat(relevantLog.getFormattedMessage())
				.matches("\\[(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})\\+\\d{4}] \\[pid (\\d+)] \\[client ([0-9a-fA-F:.]+)(:\\d)*] java.lang.RuntimeException");
	}

	@Test
	void errorLogCustomFormat() {
		disposableServer =
				createServer()
				        .handle((req, resp) -> {
				            resp.withConnection(conn -> conn.channel().pipeline().fireExceptionCaught(new RuntimeException()));
				            return resp.send();
				        })
				        .errorLog(true, args -> ErrorLog.create(CUSTOM_FORMAT, args.httpServerInfos().method(), args.httpServerInfos().uri()))
				        .bindNow();

		getHttpClientResponse(createClient(disposableServer.port()), "/example/test");

		Mockito.verify(mockedAppender, Mockito.times(1)).doAppend(loggingEventArgumentCaptor.capture());
		assertThat(loggingEventArgumentCaptor.getAllValues()).hasSize(1);

		LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(0);
		assertThat(relevantLog.getMessage()).isEqualTo(CUSTOM_FORMAT);
		assertThat(relevantLog.getFormattedMessage()).isEqualTo("method=GET, uri=/example/test");
	}

	@Test
	void secondCallToErrorLogOverridesPreviousOne() {
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

		Mockito.verify(mockedAppender, Mockito.times(0)).doAppend(loggingEventArgumentCaptor.capture());
		assertThat(loggingEventArgumentCaptor.getAllValues()).isEmpty();
	}

	@Test
	void errorLogFilteringAndFormatting() {
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

		Mockito.verify(mockedAppender, Mockito.times(1)).doAppend(loggingEventArgumentCaptor.capture());
		assertThat(loggingEventArgumentCaptor.getAllValues()).hasSize(1);

		final LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(0);
		assertThat(relevantLog.getMessage()).isEqualTo(CUSTOM_FORMAT);
		assertThat(relevantLog.getFormattedMessage()).isEqualTo("method=GET, uri=/filtered/test");
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

	static HttpServer createServer() {
		Http3SslContextSpec serverCtx = Http3SslContextSpec.forServer(ssc.key(), null, ssc.cert());
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
