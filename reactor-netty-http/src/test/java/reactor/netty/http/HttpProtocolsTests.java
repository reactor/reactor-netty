/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufFlux;
import reactor.netty.Connection;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientConfig;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerConfig;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test a combination of {@link HttpServer} + {@link HttpProtocol}
 * with a combination of {@link HttpClient} + {@link HttpProtocol}
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
class HttpProtocolsTests extends BaseHttpTest {
	static final ConnectionProvider provider =
			ConnectionProvider.builder("HttpProtocolsTests")
			                  .maxConnections(1)
			                  .pendingAcquireMaxCount(10)
			                  .build();

	@AfterAll
	static void disposePool() {
		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	@ParameterizedTest(name = "{displayName}({0}, {1})")
	@MethodSource("dataAllCombinations")
	@interface ParameterizedAllCombinationsTest {
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	@ParameterizedTest(name = "{displayName}({0}, {1})")
	@MethodSource("dataCompatibleCombinations")
	@interface ParameterizedCompatibleCombinationsTest {
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	@ParameterizedTest(name = "{displayName}({0}, {1})")
	@MethodSource("dataCompatibleCombinations_NoPool")
	@interface ParameterizedCompatibleCombinationsNoPoolTest {
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	@ParameterizedTest(name = "{displayName}({0}, {1})")
	@MethodSource("dataCompatibleCombinations_CustomPool")
	@interface ParameterizedCompatibleCombinationsCustomPoolTest {
	}

	/**
	 * Returns all combinations servers/clients even when they are not compatible
	 * (e.g. the server supports only HTTP/1.1 and the client supports only HTTP/2).
	 *
	 * @return all combinations servers/clients even when they are not compatible
	 */
	static Object[][] dataAllCombinations() throws Exception {
		return data(false, false, false);
	}

	/**
	 * Returns all combinations of compatible servers/clients.
	 *
	 * @return all combinations of compatible servers/clients
	 */
	static Object[][] dataCompatibleCombinations() throws Exception {
		return data(true, false, false);
	}

	/**
	 * Returns all combinations of compatible servers/clients.
	 * The connection pool is disabled
	 *
	 * @return all combinations of compatible servers/clients
	 */
	static Object[][] dataCompatibleCombinations_NoPool() throws Exception {
		return data(true, true, false);
	}

	/**
	 * Returns all combinations of compatible servers/clients.
	 * The connection pool configuration is: maxConnections=1, unlimited pending requests.
	 *
	 * @return all combinations of compatible servers/clients
	 */
	static Object[][] dataCompatibleCombinations_CustomPool() throws Exception {
		return data(true, false, true);
	}

	static Object[][] data(boolean onlyCompatible, boolean disablePool, boolean useCustomPool) throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		Http11SslContextSpec serverCtxHttp11 = Http11SslContextSpec.forServer(cert.certificate(), cert.privateKey());
		Http11SslContextSpec clientCtxHttp11 =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		Http2SslContextSpec serverCtxHttp2 = Http2SslContextSpec.forServer(cert.certificate(), cert.privateKey());
		Http2SslContextSpec clientCtxHttp2 =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		HttpServer _server = createServer().httpRequestDecoder(spec -> spec.h2cMaxContentLength(256));

		HttpServer[] servers = new HttpServer[]{
				_server, // by default protocol is HTTP/1.1
				_server.protocol(HttpProtocol.H2C),
				_server.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C),
				_server.secure(spec -> spec.sslContext(serverCtxHttp11)), // by default protocol is HTTP/1.1
				_server.secure(spec -> spec.sslContext(serverCtxHttp2)).protocol(HttpProtocol.H2),
				_server.secure(spec -> spec.sslContext(serverCtxHttp2)).protocol(HttpProtocol.HTTP11, HttpProtocol.H2)
		};

		HttpClient _client;
		if (disablePool) {
			_client = HttpClient.newConnection();
		}
		else if (useCustomPool) {
			_client = HttpClient.create(provider);
		}
		else {
			_client = HttpClient.create();
		}

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
		               if (onlyCompatible) {
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
		                   return serverProtocols.containsAll(clientProtocols) ||
		                           clientProtocols.containsAll(serverProtocols);
		               }
		               return true;
		           })
		           .map(Tuple2::toArray)
		           .collectList()
		           .block(Duration.ofSeconds(30))
		           .toArray(new Object[0][2]);
	}

	@ParameterizedAllCombinationsTest
	void testGetRequest(HttpServer server, HttpClient client) {
		HttpServerConfig serverConfig = server.configuration();
		HttpClientConfig clientConfig = client.configuration();
		List<HttpProtocol> serverProtocols = Arrays.asList(serverConfig.protocols());
		List<HttpProtocol> clientProtocols = Arrays.asList(clientConfig.protocols());

		disposableServer =
				server.handle((req, res) -> {
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

	@ParameterizedAllCombinationsTest
	void testPostRequest_1(HttpServer server, HttpClient client) {
		doTestPostRequest(server, client, false);
	}

	@ParameterizedAllCombinationsTest
	void testPostRequest_2(HttpServer server, HttpClient client) {
		doTestPostRequest(server, client, true);
	}

	private void doTestPostRequest(HttpServer server, HttpClient client, boolean externalThread) {
		HttpServerConfig serverConfig = server.configuration();
		HttpClientConfig clientConfig = client.configuration();
		List<HttpProtocol> serverProtocols = Arrays.asList(serverConfig.protocols());
		List<HttpProtocol> clientProtocols = Arrays.asList(clientConfig.protocols());

		disposableServer =
				server.handle((req, res) -> {
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

	@ParameterizedCompatibleCombinationsTest
	@SuppressWarnings("unchecked")
	void testAccessLog(HttpServer server, HttpClient client) throws Exception {
		disposableServer =
				server.handle((req, resp) -> {
				          resp.withConnection(conn -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
				              resp.header(NettyPipeline.AccessLogHandler, handler != null ? "FOUND" : "NOT FOUND");
				          });
				          return resp.send();
				      })
				      .accessLog(true)
				      .bindNow();

		Appender<ILoggingEvent> mockedAppender = (Appender<ILoggingEvent>) Mockito.mock(Appender.class);
		ArgumentCaptor<LoggingEvent> loggingEventArgumentCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
		Mockito.when(mockedAppender.getName()).thenReturn("MOCK");
		Logger accessLogger = (Logger) LoggerFactory.getLogger("reactor.netty.http.server.AccessLog");
		AtomicReference<String> protocol = new AtomicReference<>();
		try {
			accessLogger.addAppender(mockedAppender);
			client.port(disposableServer.port())
			      .get()
			      .uri("/")
			      .responseSingle((res, bytes) -> {
			          protocol.set(res.responseHeaders().get("x-http2-stream-id") != null ? "2.0" : "1.1");
			          return Mono.just(res.responseHeaders().get(NettyPipeline.AccessLogHandler));
			      })
			      .as(StepVerifier::create)
			      .expectNext("FOUND")
			      .expectComplete()
			      .verify(Duration.ofSeconds(5));
		}
		finally {
			Thread.sleep(20);
			Mockito.verify(mockedAppender, Mockito.times(1)).doAppend(loggingEventArgumentCaptor.capture());
			assertThat(loggingEventArgumentCaptor.getAllValues()).hasSize(1);
			LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(0);
			assertThat(relevantLog.getFormattedMessage()).contains("GET / HTTP/" + protocol.get() + "\" 200");
			accessLogger.detachAppender(mockedAppender);
		}
	}

	@ParameterizedCompatibleCombinationsTest
	void testResponseTimeout(HttpServer server, HttpClient client) throws Exception {
		disposableServer =
				server.handle((req, res) -> res.sendString(Mono.just("testProtocolVariationsResponseTimeout")))
				      .bindNow();

		HttpClient localClient =
				client.port(disposableServer.port())
				      .responseTimeout(Duration.ofMillis(100));
		doTestResponseTimeout(localClient, 100);

		localClient = localClient.doOnRequest((req, conn) -> req.responseTimeout(Duration.ofMillis(200)));
		doTestResponseTimeout(localClient, 200);
	}

	private void doTestResponseTimeout(HttpClient client, long expectedTimeout)
			throws Exception {
		AtomicBoolean onRequest = new AtomicBoolean();
		AtomicBoolean onResponse = new AtomicBoolean();
		AtomicBoolean onDisconnected = new AtomicBoolean();
		AtomicLong timeout = new AtomicLong();
		Predicate<Connection> handlerAvailable =
				conn -> conn.channel().pipeline().get(NettyPipeline.ResponseTimeoutHandler) != null;
		HttpClient localClient =
				client.doOnRequest((req, conn) -> onRequest.set(handlerAvailable.test(conn)))
				      .doOnResponse((req, conn) -> {
				          if (handlerAvailable.test(conn)) {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ResponseTimeoutHandler);
				              onResponse.set(true);
				              timeout.set(((ReadTimeoutHandler) handler).getReaderIdleTimeInMillis());
				          }
				      })
				      .doOnDisconnected(conn -> onDisconnected.set(handlerAvailable.test(conn)));

		Mono<String> response =
				localClient.get()
				           .uri("/")
				           .responseContent()
				           .aggregate()
				           .asString();

		StepVerifier.create(response)
		            .expectNext("testProtocolVariationsResponseTimeout")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(onRequest.get()).isFalse();
		assertThat(onResponse.get()).isTrue();
		assertThat(onDisconnected.get()).isFalse();
		assertThat(timeout.get()).isEqualTo(expectedTimeout);

		Thread.sleep(expectedTimeout + 50);

		StepVerifier.create(response)
		            .expectNext("testProtocolVariationsResponseTimeout")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(onRequest.get()).isFalse();
		assertThat(onResponse.get()).isTrue();
		assertThat(onDisconnected.get()).isFalse();
		assertThat(timeout.get()).isEqualTo(expectedTimeout);
	}

	@ParameterizedCompatibleCombinationsTest
	void testConcurrentRequests_DefaultPool(HttpServer server, HttpClient client) {
		disposableServer = server.handle((req, res) -> res.sendString(Mono.just("testConcurrentRequests_DefaultPool")))
		                         .bindNow();

		doTestConcurrentRequests(client.port(disposableServer.port()));
	}

	@ParameterizedCompatibleCombinationsNoPoolTest
	void testConcurrentRequests_NoPool(HttpServer server, HttpClient client) {
		disposableServer = server.handle((req, res) -> res.sendString(Mono.just("testConcurrentRequests_NoPool")))
		                         .bindNow();

		doTestConcurrentRequests(client.port(disposableServer.port()));
	}

	@ParameterizedCompatibleCombinationsCustomPoolTest
	void testConcurrentRequests_CustomPool(HttpServer server, HttpClient client) {
		disposableServer = server.handle((req, res) -> res.sendString(Mono.just("testConcurrentRequests_CustomPool")))
		                         .bindNow();

		doTestConcurrentRequests(client.port(disposableServer.port()));
	}

	private void doTestConcurrentRequests(HttpClient client) {
		List<String> responses =
				Flux.range(0, 10)
				    .flatMapDelayError(i -> client.get()
				                                  .uri("/")
				                                  .responseContent()
				                                  .aggregate()
				                                  .asString(),
				            256, 32)
				    .collectList()
				    .block(Duration.ofSeconds(30));

		assertThat(responses).isNotNull();
		assertThat(responses.size()).isEqualTo(10);
	}
}
