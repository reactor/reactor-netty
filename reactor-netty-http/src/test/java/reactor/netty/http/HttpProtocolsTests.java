/*
 * Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2SettingsAckFrame;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.concurrent.DefaultPromise;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Schedulers;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufFlux;
import reactor.netty.ByteBufMono;
import reactor.netty.Connection;
import reactor.netty.LogTracker;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientConfig;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.http.client.PrematureCloseException;
import reactor.netty.http.server.ConnectionInformation;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerConfig;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.logging.AccessLog;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.ConnectionObserver.State.CONNECTED;

/**
 * Test a combination of {@link HttpServer} + {@link HttpProtocol}
 * with a combination of {@link HttpClient} + {@link HttpProtocol}.
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
	@ParameterizedTest
	@MethodSource("dataAllCombinations")
	@interface ParameterizedAllCombinationsTest {
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	@ParameterizedTest
	@MethodSource("dataCompatibleCombinations")
	@interface ParameterizedCompatibleCombinationsTest {
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	@ParameterizedTest
	@MethodSource("dataCompatibleCombinations_NoPool")
	@interface ParameterizedCompatibleCombinationsNoPoolTest {
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	@ParameterizedTest
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

	@SuppressWarnings("deprecation")
	static Object[][] data(boolean onlyCompatible, boolean disablePool, boolean useCustomPool) throws Exception {
		X509Bundle cert = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
		Http11SslContextSpec serverCtxHttp11 = Http11SslContextSpec.forServer(cert.toTempCertChainPem(), cert.toTempPrivateKeyPem());
		Http11SslContextSpec clientCtxHttp11 =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		Http2SslContextSpec serverCtxHttp2 = Http2SslContextSpec.forServer(cert.toTempCertChainPem(), cert.toTempPrivateKeyPem());
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
	void testAccessLog(HttpServer server, HttpClient client) throws Exception {
		disposableServer =
				server.route(r -> r.get("/", (req, resp) -> {
				          resp.withConnection(conn -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
				              resp.header(NettyPipeline.AccessLogHandler, handler != null ? "FOUND" : "NOT FOUND");
				          });
				          return resp.send();
				      }))
				      .accessLog(true)
				      .bindNow();

		HttpProtocol[] serverProtocols = server.configuration().protocols();
		HttpProtocol[] clientProtocols = client.configuration().protocols();
		boolean isHttp11 = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11);
		String okMessage = "GET / HTTP/" + (isHttp11 ? "1.1" : "2.0") + "\" 200";
		String notFoundMessage = "GET /not_found HTTP/" + (isHttp11 ? "1.1" : "2.0") + "\" 404";
		try (LogTracker logTracker = new LogTracker("reactor.netty.http.server.AccessLog", okMessage, notFoundMessage)) {
			doTestAccessLog(client, "/",
					res -> Mono.just(res.responseHeaders().get(NettyPipeline.AccessLogHandler)), "FOUND");

			doTestAccessLog(client, "/not_found", res -> Mono.just(res.status().toString()), "404 Not Found");

			assertThat(logTracker.latch.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(logTracker.actualMessages).hasSize(2);
			List<String> actual = new ArrayList<>(2);
			logTracker.actualMessages.forEach(e -> {
				String msg = e.getFormattedMessage();
				int startInd = msg.indexOf('"') + 1;
				int endInd = msg.lastIndexOf('"') + 5;
				actual.add(e.getFormattedMessage().substring(startInd, endInd));
			});
			assertThat(actual).hasSameElementsAs(Arrays.asList(okMessage, notFoundMessage));
		}
	}

	private void doTestAccessLog(HttpClient client, String uri,
			Function<HttpClientResponse, Mono<String>> response, String expectation) {
		client.port(disposableServer.port())
		      .get()
		      .uri(uri)
		      .responseSingle((res, bytes) -> response.apply(res))
		      .as(StepVerifier::create)
		      .expectNext(expectation)
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));
	}

	@ParameterizedCompatibleCombinationsTest
	void testAccessLogWithForwardedHeader(HttpServer server, HttpClient client) throws Exception {
		Function<@Nullable SocketAddress, String> applyAddress = addr ->
				addr instanceof InetSocketAddress ? ((InetSocketAddress) addr).getHostString() : "-";

		disposableServer =
				server.handle((req, resp) -> {
				          resp.withConnection(conn -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
				              resp.header(NettyPipeline.AccessLogHandler, handler != null ? "FOUND" : "NOT FOUND");
				          });
				          return resp.send();
				      })
				      .forwarded(true)
				      .accessLog(true, args -> {
				          ConnectionInformation connectionInformation = args.connectionInformation();
				          return connectionInformation != null ?
				                  AccessLog.create(
				                          "{} {} {}",
				                          applyAddress.apply(connectionInformation.remoteAddress()),
				                          applyAddress.apply(connectionInformation.hostAddress()),
				                          connectionInformation.scheme()) :
				                  null;
				      })
				      .bindNow();

		String expectedLogRecord = "192.0.2.60 203.0.113.43 http";
		try (LogTracker logTracker = new LogTracker("reactor.netty.http.server.AccessLog", expectedLogRecord)) {
			client.port(disposableServer.port())
			      .doOnRequest((req, cnx) -> req.addHeader("Forwarded",
			              "for=192.0.2.60;proto=http;host=203.0.113.43"))
			      .get()
			      .uri("/")
			      .responseSingle((res, bytes) -> Mono.just(res.responseHeaders().get(NettyPipeline.AccessLogHandler)))
			      .as(StepVerifier::create)
			      .expectNext("FOUND")
			      .expectComplete()
			      .verify(Duration.ofSeconds(5));

			assertThat(logTracker.latch.await(5, TimeUnit.SECONDS)).isTrue();
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

	private static void doTestResponseTimeout(HttpClient client, long expectedTimeout) throws Exception {
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
				      .doOnDisconnected(conn -> onDisconnected.set(conn.channel().isActive() && handlerAvailable.test(conn)));

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

	private static void doTestConcurrentRequests(HttpClient client) {
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

	@ParameterizedCompatibleCombinationsTest
	void testTrailerHeadersChunkedResponse(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) ->
				          res.header(HttpHeaderNames.TRAILER, "foo")
				             .trailerHeaders(h -> h.set("foo", "bar"))
				             .sendString(Flux.just("testTrailerHeaders", "ChunkedResponse")))
				      .bindNow();
		doTestTrailerHeaders(client.port(disposableServer.port()), "bar", "testTrailerHeadersChunkedResponse");
	}

	@ParameterizedCompatibleCombinationsTest
	void testTrailerHeadersFailedChunkedResponse(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) ->
				          res.header(HttpHeaderNames.TRAILER, "foo")
				             .trailerHeaders(h -> h.set("foo", "bar"))
				             .sendString(Flux.range(0, 3)
				                             .delayElements(Duration.ofMillis(50))
				                             .flatMap(i -> i == 2 ? Mono.error(new RuntimeException()) : Mono.just(i + ""))
				                             .doOnError(t -> res.trailerHeaders(h -> h.set("foo", "error")))
				                             .onErrorResume(t -> Mono.empty())))
				      .bindNow();

		doTestTrailerHeaders(client.port(disposableServer.port()), "error", "01");
	}

	@ParameterizedCompatibleCombinationsTest
	void testDisallowedTrailerHeadersNotSent(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) ->
				          res.header(HttpHeaderNames.TRAILER, HttpHeaderNames.CONTENT_LENGTH)
				             .trailerHeaders(h -> h.set(HttpHeaderNames.CONTENT_LENGTH, "33"))
				             .sendString(Flux.just("testDisallowedTrailer", "HeadersNotSent")))
				      .bindNow();

		// Trailer header name [content-length] not declared with [Trailer] header, or it is not a valid trailer header name
		doTestTrailerHeaders(client.port(disposableServer.port()), "empty", "testDisallowedTrailerHeadersNotSent");
	}

	@ParameterizedCompatibleCombinationsTest
	void testTrailerHeadersNotSpecifiedUpfront(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) ->
				          res.header(HttpHeaderNames.TRAILER, "foo")
				             .trailerHeaders(h -> h.set(HttpHeaderNames.CONTENT_LENGTH, "33"))
				             .sendString(Flux.just("testTrailerHeaders", "NotSpecifiedUpfront")))
				      .bindNow();

		// Trailer header name [content-length] not declared with [Trailer] header, or it is not a valid trailer header name
		doTestTrailerHeaders(client.port(disposableServer.port()), "empty", "testTrailerHeadersNotSpecifiedUpfront");
	}

	@ParameterizedCompatibleCombinationsTest
	void testTrailerHeadersFullResponseSend(HttpServer server, HttpClient client) {
		disposableServer =
				server.route(r ->
				          r.get("/1", (req, res) ->
				               res.header(HttpHeaderNames.TRAILER, "foo")
				                  .trailerHeaders(h -> h.set("foo", "bar"))
				                  .send())
				           .get("/2", (req, res) -> res.send()))
				      .bindNow();

		HttpProtocol[] serverProtocols = server.configuration().protocols();
		HttpProtocol[] clientProtocols = client.configuration().protocols();
		boolean isHttp11 = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11);
		HttpClient localClient = client.port(disposableServer.port());
		doTestTrailerHeaders(localClient, "/1", isHttp11 ? "empty" : "bar", "empty");

		doTestTrailerHeaders(localClient, "/2", "empty", "empty");
	}

	@ParameterizedCompatibleCombinationsTest
	void testTrailerHeadersFullResponseSendFluxContentAlwaysEmpty(HttpServer server, HttpClient client) {
		disposableServer =
				server.route(r ->
				          r.get("/1", (req, res) ->
				               res.header(HttpHeaderNames.TRAILER, "foo")
				                  .trailerHeaders(h -> h.set("foo", "bar"))
				                  .status(HttpResponseStatus.NO_CONTENT)
				                  .sendString(Flux.just("test", "Trailer", "Headers", "Full", "Response")))
				           .get("/2", (req, res) ->
				               res.status(HttpResponseStatus.NO_CONTENT)
				                  .sendString(Flux.just("test", "Trailer", "Headers", "Full", "Response"))))
				      .bindNow();

		HttpProtocol[] serverProtocols = server.configuration().protocols();
		HttpProtocol[] clientProtocols = client.configuration().protocols();
		boolean isHttp11 = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11);
		doTestTrailerHeaders(client.port(disposableServer.port()), "/1", isHttp11 ? "empty" : "bar", "empty");

		doTestTrailerHeaders(client.port(disposableServer.port()), "/2", "empty", "empty");
	}

	@ParameterizedCompatibleCombinationsTest
	void testTrailerHeadersFullResponseSendFluxContentLengthZero(HttpServer server, HttpClient client) {
		disposableServer =
				server.route(r ->
				          r.get("/1", (req, res) ->
				               res.header(HttpHeaderNames.TRAILER, "foo")
				                  .header(HttpHeaderNames.CONTENT_LENGTH, "0")
				                  .trailerHeaders(h -> h.set("foo", "bar"))
				                  .sendString(Flux.just("test", "Trailer", "Headers", "Full", "Response")))
				           .get("/2", (req, res) ->
				               res.header(HttpHeaderNames.CONTENT_LENGTH, "0")
				                  .sendString(Flux.just("test", "Trailer", "Headers", "Full", "Response"))))
				      .bindNow();

		HttpProtocol[] serverProtocols = server.configuration().protocols();
		HttpProtocol[] clientProtocols = client.configuration().protocols();
		boolean isHttp11 = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11);
		doTestTrailerHeaders(client.port(disposableServer.port()), "/1", isHttp11 ? "empty" : "bar", "empty");

		doTestTrailerHeaders(client.port(disposableServer.port()), "/2", "empty", "empty");
	}

	@ParameterizedCompatibleCombinationsTest
	void testTrailerHeadersFullResponseSendHeaders(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) ->
				          res.header(HttpHeaderNames.TRAILER, "foo")
				             .trailerHeaders(h -> h.set("foo", "bar"))
				             .sendHeaders())
				      .bindNow();

		doTestTrailerHeaders(client.port(disposableServer.port()), "bar", "empty");
	}

	@ParameterizedCompatibleCombinationsTest
	void testTrailerHeadersFullResponseSendMono(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) ->
				          res.header(HttpHeaderNames.TRAILER, "foo")
				             .trailerHeaders(h -> h.set("foo", "bar"))
				             .sendString(Mono.just("testTrailerHeadersFullResponse")))
				      .bindNow();

		HttpProtocol[] serverProtocols = server.configuration().protocols();
		HttpProtocol[] clientProtocols = client.configuration().protocols();
		boolean isHttp11 = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11);
		doTestTrailerHeaders(client.port(disposableServer.port()), isHttp11 ? "empty" : "bar", "testTrailerHeadersFullResponse");
	}

	@ParameterizedCompatibleCombinationsTest
	void testTrailerHeadersFullResponseSendMonoEmpty(HttpServer server, HttpClient client) {
		disposableServer =
				server.route(r ->
				          r.get("/1", (req, res) -> {
				               res.header(HttpHeaderNames.TRAILER, "foo")
				                  .trailerHeaders(h -> h.set("foo", "bar"));
				               return Mono.empty();
				           })
				           .get("/2", (req, res) -> Mono.empty()))
				      .bindNow();

		HttpProtocol[] serverProtocols = server.configuration().protocols();
		HttpProtocol[] clientProtocols = client.configuration().protocols();
		boolean isHttp11 = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11);
		doTestTrailerHeaders(client.port(disposableServer.port()), "/1", isHttp11 ? "empty" : "bar", "empty");

		doTestTrailerHeaders(client.port(disposableServer.port()), "/2", "empty", "empty");
	}

	@ParameterizedCompatibleCombinationsTest
	void testTrailerHeadersFullResponseSendObject(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) ->
				          res.header(HttpHeaderNames.TRAILER, "foo")
				             .trailerHeaders(h -> h.set("foo", "bar"))
				             .sendObject(Unpooled.wrappedBuffer("testTrailerHeadersFullResponse".getBytes(Charset.defaultCharset()))))
				      .bindNow();

		HttpProtocol[] serverProtocols = server.configuration().protocols();
		HttpProtocol[] clientProtocols = client.configuration().protocols();
		boolean isHttp11 = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11);
		doTestTrailerHeaders(client.port(disposableServer.port()), isHttp11 ? "empty" : "bar", "testTrailerHeadersFullResponse");
	}

	private static void doTestTrailerHeaders(HttpClient client, String expectedHeaderValue, String expectedResponse) {
		doTestTrailerHeaders(client, "/", expectedHeaderValue, expectedResponse);
	}

	private static void doTestTrailerHeaders(HttpClient client, String uri, String expectedHeaderValue, String expectedResponse) {
		client.get()
		      .uri(uri)
		      .responseSingle((res, bytes) -> bytes.asString().defaultIfEmpty("empty").zipWith(res.trailerHeaders()))
		      .as(StepVerifier::create)
		      .expectNextMatches(t -> expectedResponse.equals(t.getT1()) &&
		              expectedHeaderValue.equals(t.getT2().get("foo", "empty")))
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));
	}

	@ParameterizedCompatibleCombinationsTest
	void testIdleTimeoutAddedCorrectly(HttpServer server, HttpClient client) {
		ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
		Logger logger = (Logger) LoggerFactory.getLogger(DefaultPromise.class);
		logger.addAppender(listAppender);
		listAppender.start();

		disposableServer =
				server.idleTimeout(Duration.ofSeconds(60))
				      .route(routes ->
				              routes.post("/echo", (req, res) -> res.send(req.receive().retain())))
				      .bindNow();

		StepVerifier.create(client.port(disposableServer.port())
		                          .post()
		                          .uri("/echo")
		                          .send(ByteBufFlux.fromString(Mono.just("Hello world!")))
		                          .responseContent()
		                          .aggregate()
		                          .asString()
		                          .timeout(Duration.ofSeconds(10)))
		            .expectNext("Hello world!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));

		try {
			// Wait till all logs are flushed
			Thread.sleep(200);
		}
		catch (InterruptedException e) {
			e.printStackTrace();
		}

		// ensure no WARN with error
		assertThat(listAppender.list).noneMatch(event -> event.getLevel() == Level.WARN);
	}

	@ParameterizedCompatibleCombinationsTest
	void testIdleTimeout(HttpServer server, HttpClient client) throws Exception {
		HttpProtocol[] serverProtocols = server.configuration().protocols();
		HttpProtocol[] clientProtocols = client.configuration().protocols();

		CountDownLatch latch = new CountDownLatch(1);
		IdleTimeoutTestChannelInboundHandler customHandler = new IdleTimeoutTestChannelInboundHandler();
		disposableServer =
				server.idleTimeout(Duration.ofMillis(500))
				      .doOnChannelInit((obs, ch, addr) -> {
				          if (((serverProtocols.length == 1 && serverProtocols[0] != HttpProtocol.HTTP11) ||
				                  (clientProtocols.length == 1 && clientProtocols[0] != HttpProtocol.HTTP11)) &&
				                  ch.pipeline().get(NettyPipeline.IdleTimeoutHandler) != null) {
				              ch.pipeline().addAfter(NettyPipeline.IdleTimeoutHandler, "testIdleTimeout", customHandler);
				          }
				          else if (serverProtocols.length == 2 && serverProtocols[1] == HttpProtocol.H2) {
				              ch.pipeline().addBefore(NettyPipeline.ReactiveBridge, "testIdleTimeout1",
				                      new IdleTimeoutTest1ChannelInboundHandler(customHandler));
				          }
				      })
				      .childObserve((conn, state) -> {
				          Channel channel = conn.channel();
				          if (state == CONNECTED &&
				                  !(channel instanceof Http2StreamChannel) &&
				                  channel.pipeline().get(NettyPipeline.IdleTimeoutHandler) != null &&
				                  channel.pipeline().get("testIdleTimeout") == null) {
				              channel.pipeline().addAfter(NettyPipeline.IdleTimeoutHandler, "testIdleTimeout", customHandler);
				          }
				      })
				      .route(routes ->
				          routes.post("/echo", (req, res) ->
				              res.withConnection(conn -> {
				                      Channel channel = conn.channel() instanceof Http2StreamChannel ?
				                              conn.channel().parent() : conn.channel();
				                      channel.closeFuture().addListener(f -> latch.countDown());
				                 })
				                 .send(req.receive().retain())))
				      .bindNow();

		CountDownLatch goAwayReceived = new CountDownLatch(1);
		client.doOnResponse((res, conn) -> {
		          if (!(conn.channel() instanceof Http2StreamChannel)) {
		              goAwayReceived.countDown();
		              return;
		          }

		          Http2FrameCodec http2FrameCodec = conn.channel().parent().pipeline().get(Http2FrameCodec.class);
		          Http2Connection.Listener goAwayFrameListener = Mockito.mock(Http2Connection.Listener.class);
		          Mockito.doAnswer(invocation -> {
		                     goAwayReceived.countDown();
		                     return null;
		                 })
		                 .when(goAwayFrameListener)
		                 .onGoAwayReceived(Mockito.anyInt(), Mockito.anyLong(), Mockito.any());
		          http2FrameCodec.connection().addListener(goAwayFrameListener);
		      })
		      .port(disposableServer.port())
		      .post()
		      .uri("/echo")
		      .send(ByteBufFlux.fromString(Mono.just("Hello world!")))
		      .responseContent()
		      .aggregate()
		      .asString()
		      .as(StepVerifier::create)
		      .expectNext("Hello world!")
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));

		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

		assertThat(goAwayReceived.await(10, TimeUnit.SECONDS)).isTrue();

		assertThat(customHandler.latch.await(10, TimeUnit.SECONDS)).isTrue();

		if ((serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11)) {
			assertThat(customHandler.list).hasSize(3);
			assertThat(customHandler.list.get(0)).isInstanceOf(HttpRequest.class);
			assertThat(customHandler.list.get(1)).isInstanceOf(HttpContent.class);
			assertThat(customHandler.list.get(2)).isInstanceOf(LastHttpContent.class);
		}
		else if (serverProtocols.length == 1 || clientProtocols.length == 1 ||
				(serverProtocols.length == 2 && clientProtocols.length == 2 &&
						serverProtocols[1] == HttpProtocol.H2 && clientProtocols[1] == HttpProtocol.H2)) {
			assertThat(customHandler.list).hasSize(5);
			assertThat(customHandler.list.get(0)).isInstanceOf(Http2SettingsFrame.class);
			assertThat(customHandler.list.get(1)).isInstanceOf(Http2SettingsAckFrame.class);
			assertThat(customHandler.list.get(2)).isInstanceOf(Http2HeadersFrame.class);
			assertThat(customHandler.list.get(3)).isInstanceOf(Http2DataFrame.class);
			assertThat(customHandler.list.get(4)).isInstanceOf(Http2DataFrame.class);
		}
		else if (clientProtocols.length == 2 && clientProtocols[1] == HttpProtocol.H2C) {
			assertThat(customHandler.list).hasSize(4);
			assertThat(customHandler.list.get(0)).isInstanceOf(Http2HeadersFrame.class);
			assertThat(customHandler.list.get(1)).isInstanceOf(Http2DataFrame.class);
			assertThat(customHandler.list.get(2)).isInstanceOf(Http2SettingsFrame.class);
			assertThat(customHandler.list.get(3)).isInstanceOf(Http2SettingsAckFrame.class);
		}
	}

	@ParameterizedCompatibleCombinationsTest
	void testRequestTimeout(HttpServer server, HttpClient client) throws Exception {
		HttpProtocol[] serverProtocols = server.configuration().protocols();
		HttpProtocol[] clientProtocols = client.configuration().protocols();
		AtomicReference<List<Boolean>> handlerAvailable = new AtomicReference<>(new ArrayList<>(3));
		AtomicReference<List<Boolean>> onTerminate = new AtomicReference<>(new ArrayList<>(3));
		AtomicReference<List<Long>> timeout = new AtomicReference<>(new ArrayList<>(3));
		CountDownLatch latch = new CountDownLatch(3);
		disposableServer =
				server.readTimeout(Duration.ofMillis(60))
				      .requestTimeout(Duration.ofMillis(150))
				      .doOnChannelInit((obs, ch, addr) -> {
				          if ((serverProtocols.length == 2 && serverProtocols[1] == HttpProtocol.H2C) &&
				                  (clientProtocols.length == 2 && clientProtocols[1] == HttpProtocol.H2C)) {
				              ChannelHandler httpServerCodec = ch.pipeline().get(HttpServerCodec.class);
				              if (httpServerCodec != null) {
				                  String name = ch.pipeline().context(httpServerCodec).name();
				                  ch.pipeline().addAfter(name, "testRequestTimeout",
				                          new RequestTimeoutTestChannelInboundHandler(handlerAvailable, onTerminate, timeout, latch));
				              }
				          }
				      })
				      .handle((req, res) ->
				          res.withConnection(conn -> {
				                  if (!((serverProtocols.length == 2 && serverProtocols[1] == HttpProtocol.H2C) &&
				                          (clientProtocols.length == 2 && clientProtocols[1] == HttpProtocol.H2C))) {
				                      ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ReadTimeoutHandler);
				                      if (handler != null) {
				                          handlerAvailable.get().add(true);
				                          timeout.get().add(((ReadTimeoutHandler) handler).getReaderIdleTimeInMillis());
				                      }
				                  }
				                  conn.onTerminate().subscribe(null, null, () -> {
				                      onTerminate.get().add(conn.channel().isActive() &&
				                              conn.channel().pipeline().get(NettyPipeline.ReadTimeoutHandler) != null);
				                      latch.countDown();
				                  });
				             })
				             .send(req.receive().retain()))
				      .bindNow();

		HttpClient localClient = client.port(disposableServer.port());

		Mono<String> response1 =
				localClient.post()
				           .uri("/")
				           .send(ByteBufFlux.fromString(Flux.just("test", "ProtocolVariations", "RequestTimeout")
				                                            .delayElements(Duration.ofMillis(80))))
				           .responseContent()
				           .aggregate()
				           .asString();

		Mono<String> response2 =
				localClient.post()
				           .uri("/")
				           .send(ByteBufFlux.fromString(Flux.just("test", "Protocol", "Variations", "Request", "Timeout")
				                                            .delayElements(Duration.ofMillis(40))))
				           .responseContent()
				           .aggregate()
				           .asString();

		Mono<String> response3 =
				localClient.post()
				           .uri("/")
				           .send(ByteBufFlux.fromString(Flux.just("test", "ProtocolVariations", "RequestTimeout")))
				           .responseContent()
				           .aggregate()
				           .asString();

		List<Signal<String>> result =
				Flux.concat(response1.materialize(), response2.materialize(), response3.materialize())
				    .collectList()
				    .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		assertThat(result).isNotNull();

		assertThat(handlerAvailable.get()).hasSize(3).allMatch(b -> b);
		assertThat(onTerminate.get()).hasSize(3).allMatch(b -> !b);
		assertThat(timeout.get()).hasSize(3).allMatch(l -> l == 60);

		int onNext = 0;
		int onError = 0;
		for (Signal<String> signal : result) {
			if (signal.isOnNext()) {
				onNext++;
				assertThat(signal.get()).isEqualTo("testProtocolVariationsRequestTimeout");
			}
			else if (signal.getThrowable() instanceof PrematureCloseException ||
					(signal.getThrowable() != null && signal.getThrowable().getMessage() != null &&
							signal.getThrowable().getMessage().contains("Connection reset by peer"))) {
				onError++;
			}
		}

		assertThat(onNext).isEqualTo(1);
		assertThat(onError).isEqualTo(2);
	}

	@ParameterizedCompatibleCombinationsTest
	void test100Continue(HttpServer server, HttpClient client) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				server.handle((req, res) -> req.receive()
				                               .aggregate()
				                               .asString()
				                               .flatMap(s -> {
				                                       latch.countDown();
				                                       return res.sendString(Mono.just(s))
				                                                 .then();
				                               }))
				      .bindNow();

		Tuple2<String, Integer> content =
				client.port(disposableServer.port())
				      .headers(h -> h.add(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE))
				      .post()
				      .uri("/")
				      .send(ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5")))
				      .responseSingle((res, bytes) -> bytes.asString()
				                                           .zipWith(Mono.just(res.status().code())))
				      .block(Duration.ofSeconds(5));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(content).isNotNull();
		assertThat(content.getT1()).isEqualTo("12345");
		assertThat(content.getT2()).isEqualTo(200);
	}

	@ParameterizedCompatibleCombinationsCustomPoolTest
	void test100ContinueConnectionClose(HttpServer server, HttpClient client) throws Exception {
		doTest100ContinueConnection(server, client,
				(req, res) -> res.status(400).sendString(Mono.just("ERROR")),
				ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5").delaySubscription(Duration.ofMillis(100))),
				false);
	}

	@ParameterizedCompatibleCombinationsCustomPoolTest
	void test100ContinueConnectionKeepAlive(HttpServer server, HttpClient client) throws Exception {
		doTest100ContinueConnection(server, client,
				(req, res) -> res.status(400).sendString(Mono.just("ERROR").delaySubscription(Duration.ofMillis(100))),
				ByteBufMono.fromString(Mono.just("12345")),
				true);
	}

	private void doTest100ContinueConnection(
			HttpServer server,
			HttpClient client,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> postHandler,
			Publisher<ByteBuf> sendBody,
			boolean isKeepAlive) throws Exception {
		HttpProtocol[] serverProtocols = server.configuration().protocols();
		HttpProtocol[] clientProtocols = client.configuration().protocols();

		CountDownLatch latch = new CountDownLatch(2);
		AtomicReference<List<Channel>> channels = new AtomicReference<>(new ArrayList<>(2));
		disposableServer =
				server.doOnConnection(conn -> {
				          channels.get().add(conn.channel());
				          conn.onTerminate().subscribe(null, t -> latch.countDown(), latch::countDown);
				      })
				      .route(r ->
				          r.post("/post", postHandler)
				           .get("/get", (req, res) -> res.sendString(Mono.just("OK"))))
				      .bindNow();

		Mono<Tuple2<String, HttpClientResponse>> content1 =
				client.port(disposableServer.port())
				      .headers(h -> h.add(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE))
				      .post()
				      .uri("/post")
				      .send(sendBody)
				      .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res)));

		Mono<Tuple2<String, HttpClientResponse>> content2 =
				client.port(disposableServer.port())
				      .get()
				      .uri("/get")
				      .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res)));

		List<Tuple2<String, HttpClientResponse>> responses =
				Flux.concat(content1, content2)
				    .collectList()
				    .block(Duration.ofSeconds(5));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(responses).isNotNull();
		assertThat(responses.size()).isEqualTo(2);
		assertThat(responses.get(0).getT1()).isEqualTo("ERROR");
		assertThat(responses.get(0).getT2().status().code()).isEqualTo(400);
		assertThat(responses.get(1).getT1()).isEqualTo("OK");
		assertThat(responses.get(1).getT2().status().code()).isEqualTo(200);

		assertThat(channels.get().size()).isEqualTo(2);
		if ((serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11)) {
			if (isKeepAlive) {
				assertThat(channels.get().get(0)).isEqualTo(channels.get().get(1));

				assertThat(responses.get(0).getT2().responseHeaders().get(HttpHeaderNames.CONNECTION))
						.isNull();
			}
			else {
				assertThat(channels.get()).doesNotHaveDuplicates();

				assertThat(responses.get(0).getT2().responseHeaders().get(HttpHeaderNames.CONNECTION))
						.isEqualTo(HttpHeaderValues.CLOSE.toString());
			}
		}
		else {
			assertThat(channels.get()).doesNotHaveDuplicates();

			assertThat(responses.get(0).getT2().responseHeaders().get(HttpHeaderNames.CONNECTION))
					.isNull();
		}
	}

	@ParameterizedCompatibleCombinationsTest
	void testProtocolVersion(HttpServer server, HttpClient client) {
		HttpProtocol[] serverProtocols = server.configuration().protocols();
		HttpProtocol[] clientProtocols = client.configuration().protocols();
		String configuredProtocol = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11) ? "HTTP/1.1" : "HTTP/2.0";

		disposableServer =
				server.handle((req, res) -> res.sendString(Mono.just(req.protocol())))
				      .bindNow();

		client.port(disposableServer.port())
		      .get()
		      .uri("/")
		      .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res.version().text())))
		      .as(StepVerifier::create)
		      .expectNextMatches(t -> t.getT1().equals(t.getT2()) && t.getT1().equals(configuredProtocol))
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));
	}

	@ParameterizedCompatibleCombinationsTest
	void testMonoRequestBodySentAsFullRequest_Flux(HttpServer server, HttpClient client) {
		// sends the message and then last http content
		testRequestBody(server, client, POST, sender -> sender.send(ByteBufFlux.fromString(Mono.just("test"))), 2, null, false);
	}

	@ParameterizedCompatibleCombinationsTest
	void testMonoRequestBodySentAsFullRequest_Mono(HttpServer server, HttpClient client) {
		// sends "full" request
		testRequestBody(server, client, sender -> sender.send(ByteBufMono.fromString(Mono.just("test"))), 1);
	}

	@ParameterizedCompatibleCombinationsTest
	void testMonoRequestBodySentAsFullRequest_MonoEmptyGet(HttpServer server, HttpClient client) {
		// sends "full" request
		testRequestBody(server, client, GET, sender -> sender.send(Mono.empty()), 1, null, true);
	}

	@ParameterizedCompatibleCombinationsTest
	void testMonoRequestBodySentAsFullRequest_MonoEmptyPost(HttpServer server, HttpClient client) {
		// sends "full" request
		testRequestBody(server, client, POST, sender -> sender.send(Mono.empty()), 1, "0", false);
	}

	@ParameterizedCompatibleCombinationsTest
	void testIssue3524FluxGet1(HttpServer server, HttpClient client) {
		// sends "full" request
		testRequestBody(server, client, GET, sender -> sender.send((req, out) -> out.send(Flux.just(EMPTY_BUFFER, EMPTY_BUFFER, EMPTY_BUFFER))), 1, null, true);
	}

	@ParameterizedCompatibleCombinationsTest
	void testIssue3524FluxGet2(HttpServer server, HttpClient client) {
		// sends "full" request
		testRequestBody(server, client, GET, sender -> sender.send((req, out) -> out.send(Flux.generate(SynchronousSink::complete))), 1, null, true);
	}

	@ParameterizedCompatibleCombinationsTest
	void testIssue3524FluxPost(HttpServer server, HttpClient client) {
		// sends the message and then last http content
		testRequestBody(server, client, POST, sender -> sender.send((req, out) -> out.sendString(Flux.just("te", "st"))), 3, null, false);
	}

	@ParameterizedCompatibleCombinationsTest
	void testIssue3524Mono(HttpServer server, HttpClient client) {
		// sends "full" request
		testRequestBody(server, client, sender -> sender.send((req, out) -> out.sendString(Mono.just("test"))), 1);
	}

	@ParameterizedCompatibleCombinationsTest
	void testIssue3524MonoEmptyGet(HttpServer server, HttpClient client) {
		// sends "full" request
		testRequestBody(server, client, GET, sender -> sender.send((req, out) -> Mono.empty()), 1, null, true);
	}

	@ParameterizedCompatibleCombinationsTest
	void testIssue3524MonoEmptyPost(HttpServer server, HttpClient client) {
		// sends "full" request
		testRequestBody(server, client, POST, sender -> sender.send((req, out) -> Mono.empty()), 1, "0", false);
	}

	@ParameterizedCompatibleCombinationsTest
	void testIssue3524NoBodyGet(HttpServer server, HttpClient client) {
		// sends "full" request
		testRequestBody(server, client, GET, sender -> sender.send((req, out) -> out), 1, null, true);
	}

	@ParameterizedCompatibleCombinationsTest
	void testIssue3524NoBodyPost(HttpServer server, HttpClient client) {
		// sends "full" request
		testRequestBody(server, client, POST, sender -> sender.send((req, out) -> out), 1, "0", false);
	}

	@ParameterizedCompatibleCombinationsTest
	void testIssue3524Object(HttpServer server, HttpClient client) {
		// sends "full" request
		testRequestBody(server, client,
				sender -> sender.send((req, out) -> out.sendObject(Unpooled.wrappedBuffer("test".getBytes(Charset.defaultCharset())))), 1);
	}

	private void testRequestBody(HttpServer server, HttpClient client,
			Function<HttpClient.RequestSender, HttpClient.ResponseReceiver<?>> sendFunction, int expectedMsg) {
		testRequestBody(server, client, POST, sendFunction, expectedMsg, "4", false);
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	private void testRequestBody(HttpServer server, HttpClient client, HttpMethod method,
			Function<HttpClient.RequestSender, HttpClient.ResponseReceiver<?>> sendFunction, int expectedMsg,
			@Nullable String contentLength, boolean contentHeadersDoNotExist) {
		disposableServer =
				server.handle((req, res) -> req.receive()
				                               .then(res.send()))
				      .bindNow(Duration.ofSeconds(30));

		AtomicInteger counter = new AtomicInteger();
		AtomicReference<HttpHeaders> requestHeaders = new AtomicReference<>();
		sendFunction.apply(
		                client.port(disposableServer.port())
		                      .doAfterRequest((req, conn) -> requestHeaders.set(req.requestHeaders()))
		                      .doOnRequest((req, conn) -> {
		                          ChannelPipeline pipeline = conn.channel() instanceof Http2StreamChannel ?
		                                  conn.channel().parent().pipeline() : conn.channel().pipeline();
		                          ChannelHandlerContext ctx = pipeline.context(NettyPipeline.HttpCodec);
		                          if (ctx == null) {
		                              ctx = pipeline.context(HttpClientCodec.class);
		                          }
		                          if (ctx != null) {
		                              pipeline.addAfter(ctx.name(), "testRequestBody",
		                                  new ChannelOutboundHandlerAdapter() {
		                                      boolean done;

		                                      @Override
		                                      public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		                                          if (!done) {
		                                              if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {
		                                                      done = true;
		                                                      counter.getAndIncrement();
		                                              }
		                                              else if (msg instanceof Http2DataFrame) {
		                                                  if (((Http2DataFrame) msg).isEndStream()) {
		                                                      done = true;
		                                                  }
		                                                  counter.getAndIncrement();
		                                              }
		                                              else if (msg instanceof LastHttpContent) {
		                                                  done = true;
		                                                  counter.getAndIncrement();
		                                              }
		                                              else if (msg instanceof ByteBuf) {
		                                                  counter.getAndIncrement();
		                                              }
		                                          }
		                                          //"FutureReturnValueIgnored" this is deliberate
		                                          ctx.write(msg, promise);
		                                      }
		                                  });
		                          }
		                      })
		                      .request(method)
		                      .uri("/"))
		            .responseContent()
		            .aggregate()
		            .asString()
		            .block(Duration.ofSeconds(30));

		assertThat(counter.get()).isEqualTo(expectedMsg);
		if (contentHeadersDoNotExist) {
			assertThat(requestHeaders.get().get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
			assertThat(requestHeaders.get().get(HttpHeaderNames.TRANSFER_ENCODING)).isNull();
		}
		else if (contentLength != null) {
			assertThat(requestHeaders.get().get(HttpHeaderNames.CONTENT_LENGTH)).isNotNull().isEqualTo(contentLength);
			assertThat(requestHeaders.get().get(HttpHeaderNames.TRANSFER_ENCODING)).isNull();
		}
		else {
			assertThat(requestHeaders.get().get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
			assertThat(requestHeaders.get().get(HttpHeaderNames.TRANSFER_ENCODING)).isNotNull();
		}
	}

	static final class IdleTimeoutTestChannelInboundHandler extends ChannelInboundHandlerAdapter {

		final CountDownLatch latch = new CountDownLatch(1);
		final List<Object> list = new ArrayList<>();

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			latch.countDown();
			ctx.fireChannelInactive();
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			list.add(msg);
			ctx.fireChannelRead(msg);
		}
	}

	static final class IdleTimeoutTest1ChannelInboundHandler extends ChannelInboundHandlerAdapter {

		final IdleTimeoutTestChannelInboundHandler channelHandler;

		IdleTimeoutTest1ChannelInboundHandler(IdleTimeoutTestChannelInboundHandler channelHandler) {
			this.channelHandler = channelHandler;
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
			ChannelPipeline pipeline = ctx.channel().pipeline();
			if (evt instanceof SslHandshakeCompletionEvent &&
					pipeline.get(NettyPipeline.IdleTimeoutHandler) != null &&
					pipeline.get("testIdleTimeout") == null) {
				pipeline.remove(this);
				pipeline.addAfter(NettyPipeline.IdleTimeoutHandler, "testIdleTimeout", channelHandler);
			}
			ctx.fireUserEventTriggered(evt);
		}
	}

	static final class RequestTimeoutTestChannelInboundHandler extends ChannelInboundHandlerAdapter {

		final AtomicReference<List<Boolean>> handlerAvailable;
		final AtomicReference<List<Boolean>> onTerminate;
		final AtomicReference<List<Long>> timeout;
		final CountDownLatch latch;

		RequestTimeoutTestChannelInboundHandler(
				AtomicReference<List<Boolean>> handlerAvailable,
				AtomicReference<List<Boolean>> onTerminate,
				AtomicReference<List<Long>> timeout,
				CountDownLatch latch) {
			this.handlerAvailable = handlerAvailable;
			this.onTerminate = onTerminate;
			this.timeout = timeout;
			this.latch = latch;
		}
		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			ctx.fireChannelRead(msg);

			if (msg instanceof HttpRequest) {
				ChannelHandler handler = ctx.channel().pipeline().get(NettyPipeline.ReadTimeoutHandler);
				if (handler != null) {
					handlerAvailable.get().add(true);
					timeout.get().add(((ReadTimeoutHandler) handler).getReaderIdleTimeInMillis());
				}
			}
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			onTerminate.get().add(ctx.channel().isActive() &&
					ctx.channel().pipeline().get(NettyPipeline.ReadTimeoutHandler) != null);
			latch.countDown();

			ctx.fireChannelInactive();
		}
	}
}