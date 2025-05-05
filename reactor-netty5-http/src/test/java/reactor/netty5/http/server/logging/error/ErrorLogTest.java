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
package reactor.netty5.http.server.logging.error;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import io.netty5.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty5.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty5.BaseHttpTest;
import reactor.netty5.http.Http11SslContextSpec;
import reactor.netty5.http.Http2SslContextSpec;
import reactor.netty5.http.HttpProtocol;
import reactor.netty5.http.client.HttpClient;
import reactor.netty5.http.client.HttpClientConfig;
import reactor.netty5.http.server.HttpServer;
import reactor.netty5.http.server.HttpServerConfig;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty5.http.server.logging.error.DefaultErrorLog.LOGGER;

/**
 * This test class verifies {@link DefaultErrorLogHandler}.
 *
 * @author raccoonback
 */
class ErrorLogTest extends BaseHttpTest {

	static final Logger ROOT = (Logger) LoggerFactory.getLogger(LOGGER.getName());
	static final String CUSTOM_FORMAT = "method={}, uri={}";

	private Appender<ILoggingEvent> mockedAppender;
	private ArgumentCaptor<LoggingEvent> loggingEventArgumentCaptor;

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
	}

	@ParameterizedTest
	@MethodSource("httpProtocolsCompatibleCombinations")
	void errorLogDefaultFormat(HttpServer server, HttpClient client) {
		testErrorLogDefaultFormat(
				server.handle((req, res) -> {
					res.withConnection(conn -> conn.channel().pipeline().fireChannelExceptionCaught(new RuntimeException()));
					return res.send();
				}),
				client);
	}

	@ParameterizedTest
	@MethodSource("httpProtocolsCompatibleCombinations")
	void errorLogDefaultFormatWhenReactivePipelineThrowsException(HttpServer server, HttpClient client) {
		testErrorLogDefaultFormat(
				server.handle((req, res) -> Mono.error(new RuntimeException())),
				client);
	}

	@ParameterizedTest
	@MethodSource("httpProtocolsCompatibleCombinations")
	void errorLogDefaultFormatWhenUnhandledThrowsException(HttpServer server, HttpClient client) {
		testErrorLogDefaultFormat(
				server.handle((req, res) -> {
					throw new RuntimeException();
				}),
				client);
	}

	@ParameterizedTest
	@MethodSource("httpProtocolsCompatibleCombinations")
	void errorLogDefaultFormatWhenReactivePipelineThrowsExceptionInRoute(HttpServer server, HttpClient client) {
		testErrorLogDefaultFormat(
				server.route(r -> r.get("/example/test", (req, res) -> Mono.error(new RuntimeException()))),
				client);
	}

	@ParameterizedTest
	@MethodSource("httpProtocolsCompatibleCombinations")
	void errorLogDefaultFormatWhenUnhandledThrowsExceptionInRoute(HttpServer server, HttpClient client) {
		testErrorLogDefaultFormat(
				server.route(r -> r.get("/example/test", (req, res) -> {
					throw new RuntimeException();
				})),
				client);
	}

	void testErrorLogDefaultFormat(HttpServer server, HttpClient client) {
		disposableServer = server.errorLog(true).bindNow();

		getHttpClientResponse(client.port(disposableServer.port()), "/example/test");

		Mockito.verify(mockedAppender, Mockito.times(1)).doAppend(loggingEventArgumentCaptor.capture());
		assertThat(loggingEventArgumentCaptor.getAllValues()).hasSize(1);

		LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(0);
		assertThat(relevantLog.getMessage()).isEqualTo(BaseErrorLogHandler.DEFAULT_LOG_FORMAT);
		assertThat(relevantLog.getFormattedMessage())
				.matches("\\[(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})\\+\\d{4}] \\[pid (\\d+)] \\[client ([0-9a-fA-F:]+(?:%[a-zA-Z0-9]+)?|\\d+\\.\\d+\\.\\d+\\.\\d+)(?::\\d+)?] java.lang.RuntimeException");
	}

	@ParameterizedTest
	@MethodSource("httpProtocolsCompatibleCombinations")
	void errorLogCustomFormat(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, resp) -> {
				          resp.withConnection(conn -> conn.channel().pipeline().fireChannelExceptionCaught(new RuntimeException()));
				          return resp.send();
				      })
				      .errorLog(true, args -> ErrorLog.create(CUSTOM_FORMAT, args.httpServerInfos().method(), args.httpServerInfos().uri()))
				      .bindNow();

		getHttpClientResponse(client.port(disposableServer.port()), "/example/test");

		Mockito.verify(mockedAppender, Mockito.times(1)).doAppend(loggingEventArgumentCaptor.capture());
		assertThat(loggingEventArgumentCaptor.getAllValues()).hasSize(1);

		LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(0);
		assertThat(relevantLog.getMessage()).isEqualTo(CUSTOM_FORMAT);
		assertThat(relevantLog.getFormattedMessage()).isEqualTo("method=GET, uri=/example/test");
	}

	@ParameterizedTest
	@MethodSource("httpProtocolsCompatibleCombinations")
	void secondCallToErrorLogOverridesPreviousOne(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, resp) -> {
				          resp.withConnection(conn -> conn.channel().pipeline().fireChannelExceptionCaught(new RuntimeException()));
				          return resp.send();
				      })
				      .errorLog(true, args -> ErrorLog.create(CUSTOM_FORMAT, args.httpServerInfos().method(), args.httpServerInfos().uri()))
				      .errorLog(false)
				      .bindNow();

		getHttpClientResponse(client.port(disposableServer.port()), "/example/test");

		Mockito.verify(mockedAppender, Mockito.times(0)).doAppend(loggingEventArgumentCaptor.capture());
		assertThat(loggingEventArgumentCaptor.getAllValues()).isEmpty();
	}

	@ParameterizedTest
	@MethodSource("httpProtocolsCompatibleCombinations")
	void errorLogFilteringAndFormatting(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, resp) -> {
				          resp.withConnection(conn -> conn.channel().pipeline().fireChannelExceptionCaught(new RuntimeException()));
				          return resp.send();
				      })
				      .errorLog(true, ErrorLogFactory.createFilter(
				          p -> p.httpServerInfos().uri().startsWith("/filtered"),
				          args -> ErrorLog.create(CUSTOM_FORMAT, args.httpServerInfos().method(), args.httpServerInfos().uri())))
				.bindNow();

		HttpClient httpClient = client.port(disposableServer.port());
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

	@SuppressWarnings("deprecation")
	static Object[][] httpProtocolsCompatibleCombinations() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		Http11SslContextSpec serverCtxHttp11 = Http11SslContextSpec.forServer(cert.certificate(), cert.privateKey());
		Http11SslContextSpec clientCtxHttp11 =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		Http2SslContextSpec serverCtxHttp2 = Http2SslContextSpec.forServer(cert.certificate(), cert.privateKey());
		Http2SslContextSpec clientCtxHttp2 =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		HttpServer _server = createServer();

		HttpServer[] servers = new HttpServer[]{
				_server, // by default protocol is HTTP/1.1
				_server.protocol(HttpProtocol.H2C),
				_server.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C),
				_server.secure(spec -> spec.sslContext(serverCtxHttp11)), // by default protocol is HTTP/1.1
				_server.secure(spec -> spec.sslContext(serverCtxHttp2)).protocol(HttpProtocol.H2),
				_server.secure(spec -> spec.sslContext(serverCtxHttp2)).protocol(HttpProtocol.HTTP11, HttpProtocol.H2)
		};

		HttpClient _client = HttpClient.create();
		_client = _client.wiretap(true);

		HttpClient[] clients = new HttpClient[]{
				_client, // by default protocol is HTTP/1.1
				_client.protocol(HttpProtocol.H2C),
				_client.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C),
				_client.secure(spec -> spec.sslContext(clientCtxHttp11)), // by default protocol is HTTP/1.1
				_client.secure(spec -> spec.sslContext(clientCtxHttp2)).protocol(HttpProtocol.H2),
				_client.secure(spec -> spec.sslContext(clientCtxHttp2)).protocol(HttpProtocol.HTTP11, HttpProtocol.H2)
		};

		Flux<HttpServer> f1 = Flux.fromArray(servers).concatMap(o -> Flux.just(o).repeat(clients.length - 1));
		Flux<HttpClient> f2 = Flux.fromArray(clients).repeat(servers.length - 1);

		return Flux.zip(f1, f2)
		           .filter(tuple2 -> {
		               HttpServerConfig serverConfig = tuple2.getT1().configuration();
		               HttpClientConfig clientConfig = tuple2.getT2().configuration();
		               List<HttpProtocol> serverProtocols = Arrays.asList(serverConfig.protocols());
		               List<HttpProtocol> clientProtocols = Arrays.asList(clientConfig.protocols());
		               if (serverConfig.isSecure() != clientConfig.isSecure()) {
		                   return false;
		               }
		               else if (serverProtocols.size() == 1 && serverProtocols.get(0) == HttpProtocol.H2C &&
		                       clientProtocols.size() == 2) {
		                   return false;
		               }
		               return serverProtocols.containsAll(clientProtocols) || clientProtocols.containsAll(serverProtocols);
		           })
		           .map(Tuple2::toArray)
		           .collectList()
		           .block(Duration.ofSeconds(30))
		           .toArray(new Object[0][2]);
	}
}
