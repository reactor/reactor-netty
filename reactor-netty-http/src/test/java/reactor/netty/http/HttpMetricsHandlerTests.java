/*
 * Copyright (c) 2019-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufFlux;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.ContextAwareHttpClientMetricsRecorder;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.ContextAwareHttpServerMetricsRecorder;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerMetricsRecorder;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider.ProtocolSslContextSpec;
import reactor.netty.transport.AddressUtils;
import reactor.test.StepVerifier;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assumptions.assumeThat;
import static reactor.netty.Metrics.CONNECTIONS_ACTIVE;
import static reactor.netty.Metrics.CONNECTIONS_TOTAL;
import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.HTTP_CLIENT_PREFIX;
import static reactor.netty.Metrics.HTTP_SERVER_PREFIX;
import static reactor.netty.Metrics.LOCAL_ADDRESS;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.NA;
import static reactor.netty.Metrics.PROXY_ADDRESS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.STREAMS_ACTIVE;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.URI;
import static reactor.netty.Metrics.formatSocketAddress;
import static reactor.netty.micrometer.CounterAssert.assertCounter;
import static reactor.netty.micrometer.DistributionSummaryAssert.assertDistributionSummary;
import static reactor.netty.micrometer.GaugeAssert.assertGauge;
import static reactor.netty.micrometer.TimerAssert.assertTimer;

/**
 * This test class verifies HTTP metrics functionality.
 *
 * @author Violeta Georgieva
 */
class HttpMetricsHandlerTests extends BaseHttpTest {
	HttpServer httpServer;
	private ConnectionProvider provider;
	HttpClient httpClient;
	private MeterRegistry registry;

	final Flux<ByteBuf> body = ByteBufFlux.fromString(Flux.just("Hello", " ", "World", "!")).delayElements(Duration.ofMillis(10));

	static SelfSignedCertificate ssc;
	static Http11SslContextSpec serverCtx11;
	static Http2SslContextSpec serverCtx2;
	static Http11SslContextSpec clientCtx11;
	static Http2SslContextSpec clientCtx2;

	private ChannelGroup group;
	private static final EventExecutor executor = new DefaultEventExecutor();

	@AfterAll
	public static void afterClass() throws Exception {
		executor.shutdownGracefully()
		        .get(5, TimeUnit.SECONDS);
	}

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		Assertions.setMaxStackTraceElementsDisplayed(100);
		ssc = new SelfSignedCertificate();
		serverCtx11 = Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())
		                                  .configure(builder -> builder.sslProvider(SslProvider.JDK));
		serverCtx2 = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())
		                                .configure(builder -> builder.sslProvider(SslProvider.JDK));
		clientCtx11 = Http11SslContextSpec.forClient()
		                                  .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                                               .sslProvider(SslProvider.JDK));
		clientCtx2 = Http2SslContextSpec.forClient()
		                                .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                                             .sslProvider(SslProvider.JDK));
	}

	/**
	 * Initialization done before running each test.
	 * <ul>
	 *  <li> /1 is used by testExistingEndpoint test</li>
	 *  <li> /2 is used by testExistingEndpoint, and testUriTagValueFunctionNotSharedForClient tests</li>
	 *  <li> /3 does not exists but is used by testNonExistingEndpoint, checkExpectationsNonExisting tests</li>
	 *  <li> /4 is used by testServerConnectionsMicrometer test</li>
	 *  <li> /5 is used by testServerConnectionsRecorder test</li>
	 *  <li> /6 is used by testServerConnectionsMicrometerConnectionClose test</li>
	 *  <li> /7 is used by testServerConnectionsRecorderConnectionClose test</li>
	 *  <li> /8 is used by testServerConnectionsWebsocketMicrometer test</li>
	 *  <li> /9 is used by testServerConnectionsWebsocketRecorder test</li>
	 * </ul>
	 */
	@BeforeEach
	void setUp() {
		group = new DefaultChannelGroup(executor);
		httpServer = createServer()
				// Register a channel group, when invoking disposeNow()
				// it will close all remaining client sockets on the server, if any.
				.channelGroup(group)
				.host("127.0.0.1")
				.metrics(true, Function.identity())
				.httpRequestDecoder(spec -> spec.h2cMaxContentLength(256))
				.route(r -> r.post("/1", (req, res) -> res.header("Connection", "close")
				                                          .send(req.receive().retain().delayElements(Duration.ofMillis(10))))
				             .post("/2", (req, res) -> res.header("Connection", "close")
				                                          .send(req.receive().retain().delayElements(Duration.ofMillis(10))))
				             .post("/4", (req, res) -> res.header("Connection", "close")
				                                          .send(req.receive().retain().doOnNext(b ->
				                                                  checkServerConnectionsMicrometer(req))))
				             .post("/5", (req, res) -> res.header("Connection", "close")
				                                          .send(req.receive().retain().doOnNext(b ->
				                                                  checkServerConnectionsRecorder(req))))
				             .get("/6", (req, res) -> {
				                 checkServerConnectionsMicrometer(req);
				                 return Mono.delay(Duration.ofMillis(200)).then(res.send());
				             })
				             .get("/7", (req, res) -> {
				                 checkServerConnectionsRecorder(req);
				                 return Mono.delay(Duration.ofMillis(200)).then(res.send());
				             })
				             .get("/8", (req, res) -> res.sendWebsocket((in, out) ->
				                     out.sendString(Mono.just("Hello World!").doOnNext(b -> checkServerConnectionsMicrometer(req)))))
				             .get("/9", (req, res) -> res.sendWebsocket((in, out) ->
				                     out.sendString(Mono.just("Hello World!").doOnNext(b -> checkServerConnectionsRecorder(req)))))
				);

		provider = ConnectionProvider.create("HttpMetricsHandlerTests", 1);
		httpClient = createClient(provider, () -> disposableServer.address())
				.metrics(true, Function.identity());

		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() throws InterruptedException, ExecutionException, TimeoutException {
		// Dispose connection provider, unless a test already disposed it
		if (!provider.isDisposed()) {
			provider.disposeLater()
					.block(Duration.ofSeconds(30));
		}

		// In case the ServerCloseHandler is registered on the server, make sure client socket is closed on the server side
		assertThat(ServerCloseHandler.INSTANCE.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();

		if (disposableServer != null) {
			disposableServer.disposeNow();
			disposableServer = null; // avoid to dispose the server again from the BaseHttpTest.disposeServer method
		}

		if (group != null) {
			group.close()
			     .get(5, TimeUnit.SECONDS);
		}

		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testExistingEndpoint(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		CountDownLatch responseSent = new CountDownLatch(1); // response fully sent by the server
		AtomicReference<CountDownLatch> responseSentRef = new AtomicReference<>(responseSent);
		ResponseSentHandler responseSentHandler = ResponseSentHandler.INSTANCE;
		disposableServer = customizeServerOptions(httpServer, serverCtx, serverProtocols)
				.doOnConnection(cnx -> responseSentHandler.register(responseSentRef, cnx.channel().pipeline()))
				.bindNow();

		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		CountDownLatch clientCompleted = new CountDownLatch(1); // client received full response
		AtomicReference<CountDownLatch> clientCompletedRef = new AtomicReference<>(clientCompleted);
		httpClient = customizeClientOptions(httpClient, clientCtx, clientProtocols)
				.doAfterRequest((req, conn) -> serverAddress.set(conn.channel().remoteAddress()))
				.doAfterResponseSuccess((resp, conn) -> clientCompletedRef.get().countDown());

		StepVerifier.create(httpClient.post()
		                              .uri("/1")
		                              .send(body)
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(responseSentRef.get().await(30, TimeUnit.SECONDS)).as("responseSentRef latch await").isTrue();
		assertThat(clientCompletedRef.get().await(30, TimeUnit.SECONDS)).as("clientCompletedRef latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		int[] numWrites = new int[]{14, 25};
		int[] bytesWrite = new int[]{160, 243};
		int connIndex = 1;
		if ((serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11)) {
			numWrites = new int[]{14, 28};
			bytesWrite = new int[]{151, 310};
			connIndex = 2;
		}
		else if (clientProtocols.length == 2 &&
				Arrays.equals(clientProtocols, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11})) {
			numWrites = new int[]{17, 28};
			bytesWrite = new int[]{315, 435};
		}

		checkExpectationsExisting("/1", sa.getHostString() + ":" + sa.getPort(), 1, serverCtx != null,
				numWrites[0], bytesWrite[0]);

		responseSentRef.set(new CountDownLatch(1));
		clientCompletedRef.set(new CountDownLatch(1));

		StepVerifier.create(httpClient.post()
		                              .uri("/2?i=1&j=2")
		                              .send(body)
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(responseSentRef.get().await(30, TimeUnit.SECONDS)).as("responseSentRef latch await").isTrue();
		assertThat(clientCompletedRef.get().await(30, TimeUnit.SECONDS)).as("clientCompletedRef latch await").isTrue();

		sa = (InetSocketAddress) serverAddress.get();

		checkExpectationsExisting("/2", sa.getHostString() + ":" + sa.getPort(), connIndex, serverCtx != null,
				numWrites[1], bytesWrite[1]);
	}

	// https://github.com/reactor/reactor-netty/issues/2187
	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testRecordingFailsServerSide(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) {
		disposableServer = customizeServerOptions(httpServer, serverCtx, serverProtocols)
				.metrics(true, id -> {
					throw new IllegalArgumentException("Testcase injected Exception");
				})
				.bindNow();

		httpClient = customizeClientOptions(httpClient, clientCtx, clientProtocols);

		StepVerifier.create(httpClient.post()
		                              .uri("/1")
		                              .send(body)
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(2));
	}

	// https://github.com/reactor/reactor-netty/issues/2187
	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testRecordingFailsClientSide(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) {
		disposableServer = customizeServerOptions(httpServer, serverCtx, serverProtocols)
				.bindNow();

		httpClient = customizeClientOptions(httpClient, clientCtx, clientProtocols).metrics(true, id -> {
			throw new IllegalArgumentException("Testcase injected Exception");
		});

		StepVerifier.create(httpClient.post()
		                              .uri("/1")
		                              .send(body)
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(2));
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testNonExistingEndpoint(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		CountDownLatch responseSent = new CountDownLatch(1); // response fully sent by the server
		AtomicReference<CountDownLatch> responseSentRef = new AtomicReference<>(responseSent);
		ResponseSentHandler responseSentHandler = ResponseSentHandler.INSTANCE;
		CountDownLatch requestReceived = new CountDownLatch(1); // request fully received by the server
		AtomicReference<CountDownLatch> requestReceivedRef = new AtomicReference<>(requestReceived);
		RequestReceivedHandler requestReceivedHandler = RequestReceivedHandler.INSTANCE;

		// the requestReceivedHandler is used to detect when the server has received the last client request content
		// the responseSentHandler is used to detect when the server has sent the last response content
		disposableServer = customizeServerOptions(httpServer, serverCtx, serverProtocols)
				.doOnConnection(cnx -> {
					responseSentHandler.register(responseSentRef, cnx.channel().pipeline());
					requestReceivedHandler.register(requestReceivedRef, cnx.channel().pipeline());
				})
				.bindNow();

		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		CountDownLatch clientCompleted = new CountDownLatch(1);
		AtomicReference<CountDownLatch> clientCompletedRef = new AtomicReference<>(clientCompleted);
		httpClient = customizeClientOptions(httpClient, clientCtx, clientProtocols)
				.doAfterRequest((req, conn) -> serverAddress.set(conn.channel().remoteAddress()))
				.doAfterResponseSuccess((rsp, conn) -> clientCompletedRef.get().countDown());

		StepVerifier.create(httpClient.headers(h -> h.add("Connection", "close"))
		                              .get()
		                              .uri("/3")
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(requestReceivedRef.get().await(30, TimeUnit.SECONDS)).as("requestReceivedRef latch await").isTrue();
		assertThat(responseSentRef.get().await(30, TimeUnit.SECONDS)).as("responseSentRef latch await").isTrue();
		assertThat(clientCompletedRef.get().await(30, TimeUnit.SECONDS)).as("clientCompletedRef latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		List<HttpProtocol> protocols = Arrays.asList(clientProtocols);
		int[] numWrites = new int[]{5, 7};
		int[] numReads = new int[]{1, 2};
		int[] bytesWrite = new int[]{106, 122};
		int[] bytesRead = new int[]{37, 48};
		int connIndex = 1;
		if ((serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11)) {
			numWrites = new int[]{1, 2};
			bytesWrite = new int[]{123, 246};
			bytesRead = new int[]{64, 128};
			connIndex = 2;
		}
		else if (clientProtocols.length == 2 &&
				Arrays.equals(clientProtocols, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11})) {
			numWrites = new int[]{4, 6};
			numReads = new int[]{2, 3};
			bytesWrite = new int[]{287, 345};
			bytesRead = new int[]{108, 119};
		}
		else if (protocols.contains(HttpProtocol.H2) || protocols.contains(HttpProtocol.H2C)) {
			numReads = new int[]{2, 3};
		}

		checkExpectationsNonExisting(sa.getHostString() + ":" + sa.getPort(), 1, 1, serverCtx != null,
				numWrites[0], numReads[0], bytesWrite[0], bytesRead[0]);

		requestReceivedRef.set(new CountDownLatch(1));
		responseSentRef.set(new CountDownLatch(1));
		clientCompletedRef.set(new CountDownLatch(1));

		StepVerifier.create(httpClient.headers(h -> h.add("Connection", "close"))
		                              .get()
		                              .uri("/3")
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(requestReceivedRef.get().await(30, TimeUnit.SECONDS)).as("requestReceivedRef latch await").isTrue();
		assertThat(responseSentRef.get().await(30, TimeUnit.SECONDS)).as("responseSentRef latch await").isTrue();
		assertThat(clientCompletedRef.get().await(30, TimeUnit.SECONDS)).as("clientCompletedRef latch await").isTrue();
		sa = (InetSocketAddress) serverAddress.get();

		checkExpectationsNonExisting(sa.getHostString() + ":" + sa.getPort(), connIndex, 2, serverCtx != null,
				numWrites[1], numReads[1], bytesWrite[1], bytesRead[1]);
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testUriTagValueFunction(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		CountDownLatch responseSent = new CountDownLatch(1); // response fully sent by the server
		CountDownLatch clientCompleted = new CountDownLatch(1); // client received full response
		ResponseSentHandler responseSentHandler = ResponseSentHandler.INSTANCE;

		disposableServer = customizeServerOptions(httpServer, serverCtx, serverProtocols)
				.doOnConnection(cnx -> responseSentHandler.register(responseSent, cnx.channel().pipeline()))
				.metrics(true, s -> "testUriTagValueResolver")
				.bindNow();

		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = customizeClientOptions(httpClient, clientCtx, clientProtocols)
				.doAfterResponseSuccess((res, conn) -> clientCompleted.countDown())
				.doAfterRequest((req, conn) -> serverAddress.set(conn.channel().remoteAddress()));

		StepVerifier.create(httpClient.metrics(true, s -> "testUriTagValueResolver")
		                              .post()
		                              .uri("/1")
		                              .send(body)
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(responseSent.await(30, TimeUnit.SECONDS)).as("responseSent latch await").isTrue();
		assertThat(clientCompleted.await(30, TimeUnit.SECONDS)).as("clientCompleted latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		int numWrites = 14;
		int bytesWrite = 160;
		if ((serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11)) {
			bytesWrite = 151;
		}
		else if (clientProtocols.length == 2 &&
				Arrays.equals(clientProtocols, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11})) {
			numWrites = 17;
			bytesWrite = 315;
		}

		checkExpectationsExisting("testUriTagValueResolver", sa.getHostString() + ":" + sa.getPort(), 1,
				serverCtx != null, numWrites, bytesWrite);
	}

	/*
	 * https://github.com/reactor/reactor-netty/issues/1559
	 */
	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testUriTagValueFunctionNotSharedForClient(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		CountDownLatch responseSent = new CountDownLatch(1); // response fully sent by the server
		AtomicReference<CountDownLatch> responseSentRef = new AtomicReference<>(responseSent);
		ResponseSentHandler responseSentHandler = ResponseSentHandler.INSTANCE;
		disposableServer =
				customizeServerOptions(httpServer, serverCtx, serverProtocols)
						.doOnConnection(cnx -> responseSentHandler.register(responseSentRef, cnx.channel().pipeline()))
						.metrics(true,
								s -> {
									if ("/1".equals(s)) {
										return "testUriTagValueFunctionNotShared_1";
									}
									else {
										return "testUriTagValueFunctionNotShared_2";
									}
								})
						.bindNow();

		CountDownLatch clientCompleted = new CountDownLatch(1); // client received full response
		AtomicReference<CountDownLatch> clientCompletedRef = new AtomicReference<>(clientCompleted);
		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = customizeClientOptions(httpClient, clientCtx, clientProtocols)
				.doAfterRequest((req, conn) -> serverAddress.set(conn.channel().remoteAddress()))
				.doAfterResponseSuccess((resp, conn) -> clientCompletedRef.get().countDown());

		httpClient.metrics(true, s -> "testUriTagValueFunctionNotShared_1")
		          .post()
		          .uri("/1")
		          .send(body)
		          .responseContent()
		          .aggregate()
		          .asString()
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		assertThat(responseSentRef.get().await(30, TimeUnit.SECONDS)).as("responseSentRef latch await").isTrue();
		assertThat(clientCompletedRef.get().await(30, TimeUnit.SECONDS)).as("clientCompletedRef latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		int[] numWrites = new int[]{14, 28};
		int[] bytesWrite = new int[]{160, 320};
		if ((serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11)) {
			bytesWrite = new int[]{151, 302};
		}
		else if (clientProtocols.length == 2 &&
				Arrays.equals(clientProtocols, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11})) {
			numWrites = new int[]{17, 34};
			bytesWrite = new int[]{315, 630};
		}

		checkExpectationsExisting("testUriTagValueFunctionNotShared_1", sa.getHostString() + ":" + sa.getPort(),
				1, serverCtx != null, numWrites[0], bytesWrite[0]);

		responseSentRef.set(new CountDownLatch(1));
		clientCompletedRef.set(new CountDownLatch(1));

		httpClient.metrics(true, s -> "testUriTagValueFunctionNotShared_2")
		          .post()
		          .uri("/2")
		          .send(body)
		          .responseContent()
		          .aggregate()
		          .asString()
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		assertThat(responseSentRef.get().await(30, TimeUnit.SECONDS)).as("responseSentRef latch await").isTrue();
		assertThat(clientCompletedRef.get().await(30, TimeUnit.SECONDS)).as("clientCompletedRef await").isTrue();

		sa = (InetSocketAddress) serverAddress.get();

		checkExpectationsExisting("testUriTagValueFunctionNotShared_2", sa.getHostString() + ":" + sa.getPort(),
				2, serverCtx != null, numWrites[1], bytesWrite[1]);
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testContextAwareRecorderOnClient(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) {
		disposableServer = customizeServerOptions(httpServer, serverCtx, serverProtocols).bindNow();

		ClientContextAwareRecorder recorder = ClientContextAwareRecorder.INSTANCE;
		customizeClientOptions(httpClient, clientCtx, clientProtocols)
		        .metrics(true, () -> recorder)
		        .post()
		        .uri("/1")
		        .send(body)
		        .responseContent()
		        .aggregate()
		        .asString()
		        .contextWrite(Context.of("testContextAwareRecorder", "OK"))
		        .as(StepVerifier::create)
		        .expectNext("Hello World!")
		        .expectComplete()
		        .verify(Duration.ofSeconds(30));

		assertThat(recorder.onDataReceivedContextView).isTrue();
		assertThat(recorder.onDataSentContextView).isTrue();
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testContextAwareRecorderOnServer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		CountDownLatch responseSent = new CountDownLatch(1); // response fully sent by the server
		ServerContextAwareRecorder recorder = ServerContextAwareRecorder.INSTANCE;
		ResponseSentHandler responseSentHandler = ResponseSentHandler.INSTANCE;
		disposableServer =
				customizeServerOptions(httpServer, serverCtx, serverProtocols).metrics(true, () -> recorder)
						.doOnConnection(cnx -> responseSentHandler.register(responseSent, cnx.channel().pipeline()))
						.mapHandle((mono, conn) -> mono.contextWrite(Context.of("testContextAwareRecorder", "OK")))
						.bindNow();

		customizeClientOptions(httpClient, clientCtx, clientProtocols)
		        .post()
		        .uri("/1")
		        .send(body)
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext("Hello World!")
		        .expectComplete()
		        .verify(Duration.ofSeconds(30));

		assertThat(responseSent.await(30, TimeUnit.SECONDS)).as("responseSent latch await").isTrue();

		assertThat(recorder.onDataReceivedContextView).isTrue();
		assertThat(recorder.onDataSentContextView).isTrue();
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testServerConnectionsMicrometer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		CountDownLatch responseSent = new CountDownLatch(1); // response fully sent by the server
		boolean isHttp11 = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11);
		HttpServer server = customizeServerOptions(httpServer, serverCtx, serverProtocols)
				.metrics(true, Function.identity())
				.doOnConnection(cnx -> {
					ResponseSentHandler.INSTANCE.register(responseSent, cnx.channel().pipeline());
					ServerCloseHandler.INSTANCE.register(cnx.channel());
				});

		disposableServer = server.forwarded(true).bindNow();

		AtomicReference<SocketAddress> clientAddress = new AtomicReference<>();
		httpClient = httpClient
				.doAfterRequest((req, conn) -> clientAddress.set(conn.channel().localAddress()));

		String uri = "/4";
		String address = formatSocketAddress(disposableServer.address());

		customizeClientOptions(httpClient, clientCtx, clientProtocols)
		        .metrics(true, Function.identity())
		        .headers(h -> h.add("X-Forwarded-Host", "192.168.0.1"))
		        .post()
		        .uri(uri)
		        .send(body)
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext("Hello World!")
		        .expectComplete()
		        .verify(Duration.ofSeconds(30));

		assertThat(responseSent.await(30, TimeUnit.SECONDS)).as("responseSent latch await").isTrue();

		// now check the server counters
		if (isHttp11) {
			// make sure the client socket is closed on the server side before checking server metrics
			assertThat(ServerCloseHandler.INSTANCE.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();
			assertGauge(registry, SERVER_CONNECTIONS_TOTAL, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(0);
			assertGauge(registry, SERVER_CONNECTIONS_ACTIVE, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(0);
		}
		else {
			assertGauge(registry, SERVER_CONNECTIONS_TOTAL, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(1);
			assertGauge(registry, SERVER_STREAMS_ACTIVE, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(0);
			// in case of H2, the tearDown method will ensure client socket is closed on the server side
		}

		// These metrics are meant only for the servers,
		// connections metrics for the clients are available from the connection pool
		address = formatSocketAddress(clientAddress.get());
		assertGauge(registry, CLIENT_CONNECTIONS_TOTAL, URI, HTTP, LOCAL_ADDRESS, address).isNull();
		assertGauge(registry, CLIENT_CONNECTIONS_ACTIVE, URI, HTTP, LOCAL_ADDRESS, address).isNull();
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testServerConnectionsMicrometerConnectionClose(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		boolean isHttp11 = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11);

		disposableServer = customizeServerOptions(httpServer, serverCtx, serverProtocols)
				.metrics(true, Function.identity())
				.doOnConnection(cnx -> {
					ServerCloseHandler.INSTANCE.register(cnx.channel());
					if (!isHttp11) {
						StreamCloseHandler.INSTANCE.register(cnx.channel());
					}
				})
				.bindNow();

		customizeClientOptions(httpClient, clientCtx, clientProtocols)
		        .metrics(true, Function.identity())
		        .responseTimeout(Duration.ofMillis(50))
		        .get()
		        .uri("/6")
		        .responseContent()
		        .as(StepVerifier::create)
		        .expectError(ReadTimeoutException.class)
		        .verify(Duration.ofSeconds(30));

		String address = formatSocketAddress(disposableServer.address());
		if (isHttp11) {
			// make sure the client socket is closed on the server side before checking server metrics
			assertThat(ServerCloseHandler.INSTANCE.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();
			assertGauge(registry, SERVER_CONNECTIONS_TOTAL, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(0);
			assertGauge(registry, SERVER_CONNECTIONS_ACTIVE, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(0);
			// https://github.com/reactor/reactor-netty/issues/3060
			assertCounter(registry, CLIENT_ERRORS, REMOTE_ADDRESS, address, PROXY_ADDRESS, NA, URI, "/6").hasCountGreaterThanOrEqualTo(1);
		}
		else {
			// make sure the client stream is closed on the server side before checking server metrics
			assertThat(StreamCloseHandler.INSTANCE.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();
			assertGauge(registry, SERVER_CONNECTIONS_TOTAL, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(1);
			assertGauge(registry, SERVER_STREAMS_ACTIVE, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(0);
			// https://github.com/reactor/reactor-netty/issues/3060
			assertCounter(registry, CLIENT_ERRORS, REMOTE_ADDRESS, address, PROXY_ADDRESS, NA, URI, "/6").hasCountGreaterThanOrEqualTo(1);
			// in case of H2, the tearDown method will ensure client socket is closed on the server side
		}
	}

	@Test
	void testServerConnectionsWebsocketMicrometer() throws Exception {
		disposableServer = httpServer
				.doOnConnection(cnx -> ServerCloseHandler.INSTANCE.register(cnx.channel()))
				.bindNow();

		String address = formatSocketAddress(disposableServer.address());

		httpClient.websocket()
		          .uri("/8")
		          .handle((in, out) -> in.receive().aggregate().asString())
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		// make sure the client socket is closed on the server side before checking server metrics
		assertThat(ServerCloseHandler.INSTANCE.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();
		assertGauge(registry, SERVER_CONNECTIONS_TOTAL, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(0);
		assertGauge(registry, SERVER_CONNECTIONS_ACTIVE, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(0);
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testServerConnectionsRecorder(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		// Invoke ServerRecorder.INSTANCE.reset() here as disposableServer.dispose (AfterEach) might be invoked after
		// ServerRecorder.INSTANCE.reset() (AfterEach) and thus leave ServerRecorder.INSTANCE in a bad state
		ServerRecorder.INSTANCE.reset();
		boolean isHttp11 = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11);

		disposableServer = customizeServerOptions(httpServer, serverCtx, serverProtocols)
				.doOnConnection(cnx -> ServerCloseHandler.INSTANCE.register(cnx.channel()))
				.metrics(true, ServerRecorder.supplier(), Function.identity())
				.bindNow();
		String address = formatSocketAddress(disposableServer.address());

		customizeClientOptions(httpClient, clientCtx, clientProtocols)
		        .metrics(true, Function.identity())
		        .post()
		        .uri("/5")
		        .send(body)
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext("Hello World!")
		        .expectComplete()
		        .verify(Duration.ofSeconds(30));

		// now we can assert test expectations
		assertThat(ServerRecorder.INSTANCE.error.get()).isNull();

		if (isHttp11) {
			// wait for the AbstractHttpServerMetricsHandlerServer to be called in recordServerConnectionClosed before asserting test expectations
			assertThat(ServerCloseHandler.INSTANCE.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();

			assertThat(ServerRecorder.INSTANCE.onServerConnectionsAmount.get()).isEqualTo(0);
			assertThat(ServerRecorder.INSTANCE.onActiveConnectionsAmount.get()).isEqualTo(0);
			assertThat(ServerRecorder.INSTANCE.onActiveConnectionsLocalAddr.get()).isEqualTo(address);
			assertThat(ServerRecorder.INSTANCE.onInactiveConnectionsLocalAddr.get()).isEqualTo(address);
		}
		else {
			assertThat(ServerRecorder.INSTANCE.onServerConnectionsAmount.get()).isEqualTo(1);
			// in case of H2, the tearDown method will ensure client socket is closed on the server side
		}
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testServerConnectionsRecorderConnectionClose(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		// Invoke ServerRecorder.INSTANCE.reset() here as disposableServer.dispose (AfterEach) might be invoked after
		// ServerRecorder.INSTANCE.reset() (AfterEach) and thus leave ServerRecorder.INSTANCE in a bad state
		ServerRecorder.INSTANCE.reset();
		boolean isHttp11 = (serverProtocols.length == 1 && serverProtocols[0] == HttpProtocol.HTTP11) ||
				(clientProtocols.length == 1 && clientProtocols[0] == HttpProtocol.HTTP11);

		disposableServer = customizeServerOptions(httpServer, serverCtx, serverProtocols)
				.metrics(true, ServerRecorder.supplier(), Function.identity())
				.doOnConnection(cnx -> {
					ServerCloseHandler.INSTANCE.register(cnx.channel());
					if (!isHttp11) {
						StreamCloseHandler.INSTANCE.register(cnx.channel());
					}
				})
				.bindNow();

		customizeClientOptions(httpClient, clientCtx, clientProtocols)
		        .metrics(true, Function.identity())
		        .responseTimeout(Duration.ofMillis(50))
		        .get()
		        .uri("/7")
		        .responseContent()
		        .as(StepVerifier::create)
		        .expectError(ReadTimeoutException.class)
		        .verify(Duration.ofSeconds(30));

		// now we can assert test expectations
		assertThat(ServerRecorder.INSTANCE.error.get()).isNull();

		String address = formatSocketAddress(disposableServer.address());
		if (isHttp11) {
			// wait for the AbstractHttpServerMetricsHandlerServer to be called in recordServerConnectionClosed before asserting test expectations
			assertThat(ServerCloseHandler.INSTANCE.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();
			assertThat(ServerRecorder.INSTANCE.onServerConnectionsAmount.get()).isEqualTo(0);
			assertThat(ServerRecorder.INSTANCE.onActiveConnectionsAmount.get()).isEqualTo(0);
			assertThat(ServerRecorder.INSTANCE.onActiveConnectionsLocalAddr.get()).isEqualTo(address);
			assertThat(ServerRecorder.INSTANCE.onInactiveConnectionsLocalAddr.get()).isEqualTo(address);
			// https://github.com/reactor/reactor-netty/issues/3060
			assertCounter(registry, CLIENT_ERRORS, PROXY_ADDRESS, NA, REMOTE_ADDRESS, address, URI, "/7").hasCountGreaterThanOrEqualTo(1);
		}
		else {
			assertThat(StreamCloseHandler.INSTANCE.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();
			assertThat(ServerRecorder.INSTANCE.onServerConnectionsAmount.get()).isEqualTo(1);
			assertThat(ServerRecorder.INSTANCE.onActiveConnectionsAmount.get()).isEqualTo(0);
			assertThat(ServerRecorder.INSTANCE.onActiveConnectionsLocalAddr.get()).isEqualTo(address);
			assertThat(ServerRecorder.INSTANCE.onInactiveConnectionsLocalAddr.get()).isEqualTo(address);
			// https://github.com/reactor/reactor-netty/issues/3060
			assertCounter(registry, CLIENT_ERRORS, REMOTE_ADDRESS, address, PROXY_ADDRESS, NA, URI, "/7").hasCountGreaterThanOrEqualTo(1);
			// in case of H2, the tearDown method will ensure client socket is closed on the server side
		}
	}

	@Test
	void testServerConnectionsWebsocketRecorder() throws Exception {
		ServerRecorder.INSTANCE.reset();
		disposableServer = httpServer.metrics(true, ServerRecorder.supplier(), Function.identity())
				.doOnConnection(cnx -> ServerCloseHandler.INSTANCE.register(cnx.channel()))
				.bindNow();

		String address = formatSocketAddress(disposableServer.address());

		httpClient.websocket()
		          .uri("/9")
		          .handle((in, out) -> in.receive().aggregate().asString())
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		// make sure the client socket is closed on the server side before checking server metrics
		assertThat(ServerCloseHandler.INSTANCE.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();
		assertThat(ServerRecorder.INSTANCE.error.get()).isNull();
		assertThat(ServerRecorder.INSTANCE.onServerConnectionsAmount.get()).isEqualTo(0);
		assertThat(ServerRecorder.INSTANCE.onActiveConnectionsAmount.get()).isEqualTo(0);
		assertThat(ServerRecorder.INSTANCE.onActiveConnectionsLocalAddr.get()).isEqualTo(address);
		assertThat(ServerRecorder.INSTANCE.onInactiveConnectionsLocalAddr.get()).isEqualTo(address);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue896() throws Exception {
		disposableServer = httpServer.noSSL()
		                             .bindNow();

		// The client should get two errors: NotSSLRecordException, and DecoderException.
		CountDownLatch latch = new CountDownLatch(2);
		httpClient.doOnChannelInit((o, c, address) -> ClientExceptionHandler.INSTANCE.register(c, latch))
		          .secure(spec -> spec.sslContext(clientCtx11))
		          .post()
		          .uri("/1")
		          .send(ByteBufFlux.fromString(Mono.just("hello")))
		          .responseContent()
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) disposableServer.channel().localAddress();
		String serverAddress = sa.getHostString() + ":" + sa.getPort();
		String[] summaryTags = new String[]{REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, "unknown"};
		assertCounter(registry, CLIENT_ERRORS, summaryTags).hasCountGreaterThanOrEqualTo(2);
	}

	// https://github.com/reactor/reactor-netty/issues/2145
	@ParameterizedTest
	@MethodSource("http11CompatibleProtocols")
	void testBadRequest(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		disposableServer = customizeServerOptions(httpServer, serverCtx, serverProtocols)
				.doOnChannelInit((cobs, ch, addr) -> ServerCloseHandler.INSTANCE.register(ch))
				.httpRequestDecoder(spec -> spec.maxHeaderSize(32))
				.bindNow();

		CountDownLatch clientCompleted = new CountDownLatch(1);
		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = customizeClientOptions(httpClient, clientCtx, clientProtocols)
				.doAfterRequest((req, conn) -> serverAddress.set(conn.channel().remoteAddress()))
				.doAfterResponseSuccess((resp, conn) -> clientCompleted.countDown());

		httpClient.get()
		          .uri("/max_header_size")
		          .responseSingle((res, byteBufMono) -> Mono.just(res.status().code()))
		          .as(StepVerifier::create)
		          .expectNext(431)
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		// Ensure client socket is closed on the server, to make sure that server metrics are up-to-date.
		assertThat(ServerCloseHandler.INSTANCE.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();

		// Ensure client has fully received the response before asserting test expectations
		assertThat(clientCompleted.await(30, TimeUnit.SECONDS)).as("clientCompleted latch await").isTrue();
		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		checkExpectationsBadRequest(sa.getHostString() + ":" + sa.getPort(), serverCtx != null);
	}

	@Test
	void smokeTestNoContextPropagation() {
		assertThatExceptionOfType(ClassNotFoundException.class)
				.isThrownBy(() -> Class.forName("io.micrometer.context.ContextRegistry"));
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testServerConnectionsRecorderBadUri(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx,
			@Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		testServerConnectionsRecorderBadUri(serverProtocols, clientProtocols, serverCtx, clientCtx, null, -1, false,
				Function.identity(), Function.identity());
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testServerConnectionsRecorderBadUriUDS(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx,
			@Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		assumeThat(LoopResources.hasNativeSupport()).isTrue();
		testServerConnectionsRecorderBadUri(serverProtocols, clientProtocols, serverCtx, clientCtx, null, -1, false,
				client -> client.bindAddress(() -> new DomainSocketAddress("/tmp/test.sockclient"))
						.remoteAddress(() -> new DomainSocketAddress("/tmp/test.sock")),
				server -> server.bindAddress(() -> new DomainSocketAddress("/tmp/test.sock")));
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testServerConnectionsRecorderBadUriUDSContextAware(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx,
			@Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		assumeThat(LoopResources.hasNativeSupport()).isTrue();
		testServerConnectionsRecorderBadUri(serverProtocols, clientProtocols, serverCtx, clientCtx, null, -1, true,
				client -> client.bindAddress(() -> new DomainSocketAddress("/tmp/test.sockclient"))
						.remoteAddress(() -> new DomainSocketAddress("/tmp/test.sock")),
				server -> server.bindAddress(() -> new DomainSocketAddress("/tmp/test.sock")));
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testServerConnectionsRecorderBadUriForwarded(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx,
			@Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		testServerConnectionsRecorderBadUri(serverProtocols, clientProtocols, serverCtx, clientCtx,
				"192.168.0.1", 8080, false,
				Function.identity(),
				Function.identity());
	}


	@ParameterizedTest
	@MethodSource("combinationsIssue2956")
	@SuppressWarnings("deprecation")
	void testIssue2956(boolean isCustomRecorder, boolean isHttp2) throws Exception {
		HttpServer server =
				httpServer.secure(spec -> spec.sslContext(isHttp2 ? serverCtx2 : serverCtx11))
				          .protocol(isHttp2 ? HttpProtocol.H2 : HttpProtocol.HTTP11)
				          .doOnConnection(conn ->  ServerCloseHandler.INSTANCE.register(conn.channel()));

		if (isCustomRecorder) {
			server = server.metrics(true, ServerRecorder.supplier());
		}

		disposableServer = server.bindNow();

		httpClient.protocol(isHttp2 ? HttpProtocol.H2C : HttpProtocol.HTTP11)
		          .post()
		          .uri("/1")
		          .send(ByteBufFlux.fromString(Mono.just("hello")))
		          .responseContent()
		          .aggregate()
		          .asString()
		          .as(StepVerifier::create)
		          .expectError()
		          .verify(Duration.ofSeconds(5));

		assertThat(ServerCloseHandler.INSTANCE.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();

		if (isCustomRecorder) {
			assertThat(ServerRecorder.INSTANCE.onServerConnectionsAmount.get()).isEqualTo(0);
		}
		else {
			InetSocketAddress sa = (InetSocketAddress) disposableServer.channel().localAddress();
			String serverAddress = sa.getHostString() + ":" + sa.getPort();
			String[] tags = new String[]{URI, HTTP, LOCAL_ADDRESS, serverAddress};
			assertGauge(registry, SERVER_CONNECTIONS_TOTAL, tags).isNull();
		}
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testIssue3060ConnectTimeoutException(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		customizeClientOptions(httpClient, clientCtx, clientProtocols)
		        .remoteAddress(() -> new InetSocketAddress("1.1.1.1", 11111))
		        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10)
		        .doOnChannelInit((o, c, address) -> c.closeFuture().addListener(f -> latch.countDown()))
		        .post()
		        .uri("/1")
		        .send(ByteBufFlux.fromString(Mono.just("hello")))
		        .responseContent()
		        .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		String[] summaryTags = new String[]{REMOTE_ADDRESS, "1.1.1.1:11111", PROXY_ADDRESS, NA, STATUS, ERROR};
		assertTimer(registry, CLIENT_CONNECT_TIME, summaryTags).hasCountEqualTo(1);
	}

	static Stream<Arguments> combinationsIssue2956() {
		return Stream.of(
				// isCustomRecorder, isHttp2
				Arguments.of(false, false),
				Arguments.of(false, true),
				Arguments.of(true, false),
				Arguments.of(true, true)
		);
	}

	private void testServerConnectionsRecorderBadUri(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx,
			@Nullable String xForwardedFor, int xForwardedPort, boolean contextAware,
			Function<HttpClient, HttpClient> bindClient,
			Function<HttpServer, HttpServer> bindServer) throws Exception {
		if (contextAware) {
			ContextAwareServerRecorderBadUri.INSTANCE.init();
		}
		else {
			ServerRecorderBadUri.INSTANCE.init();
		}

		AtomicReference<SocketAddress> clientSA = new AtomicReference<>();
		disposableServer = bindServer.apply(customizeServerOptions(httpServer, serverCtx, serverProtocols))
				.metrics(true, () -> contextAware ? ContextAwareServerRecorderBadUri.INSTANCE : ServerRecorderBadUri.INSTANCE, Function.identity())
				.forwarded(xForwardedFor != null || xForwardedPort != -1)
				.childObserve((conn, state) -> {
					if (state == ConnectionObserver.State.CONNECTED) {
						if (xForwardedFor != null && xForwardedPort != -1) {
							clientSA.set(AddressUtils.createUnresolved(xForwardedFor, xForwardedPort));
						}
						else {
							clientSA.set(conn.address());
						}
					}
				})
				.bindNow();

		bindClient.apply(customizeClientOptions(httpClient, clientCtx, clientProtocols))
				.doOnRequest((req, conn) -> {
					conn.addHandlerFirst("bad_uri", new ChannelOutboundHandlerAdapter() {
						@Override
						@SuppressWarnings("FutureReturnValueIgnored")
						public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
							if (msg instanceof HttpRequest) {
								((HttpRequest) msg).setUri("/s=set&_method=__construct&method=*&filter[]=system");
							}
							ctx.write(msg, promise);
						}
					});
					if (xForwardedFor != null) {
						req.addHeader("X-Forwarded-For", xForwardedFor + ":" + xForwardedPort);
					}
				})
				.metrics(true, Function.identity())
				.post()
				.uri("/")
				.send(body)
				.responseContent()
				.aggregate()
				.asString()
				.as(StepVerifier::create)
				.expectComplete()
				.verify(Duration.ofSeconds(30));

		// dispose connection provider now, in order to ensure connection is closed on the server
		provider.disposeLater()
				.block(Duration.ofSeconds(30));

		if (contextAware) {
			assertThat(ContextAwareServerRecorderBadUri.INSTANCE.closed.await(30, TimeUnit.SECONDS))
					.as("awaitClose timeout")
					.isTrue();

			assertThat(ContextAwareServerRecorderBadUri.INSTANCE.nullMethodParams.isEmpty())
					.as("some method got null parameters: %s", ContextAwareServerRecorderBadUri.INSTANCE.nullMethodParams)
					.isTrue();

			SocketAddress recordedClientSA = ContextAwareServerRecorderBadUri.INSTANCE.clientAddr;
			assertThat(recordedClientSA)
					.as("recorded client remote socket address %s is different from expected client socket address %s", recordedClientSA, clientSA.get())
					.isEqualTo(clientSA.get());
		}
		else {
			assertThat(ServerRecorderBadUri.INSTANCE.closed.await(30, TimeUnit.SECONDS))
					.as("awaitClose timeout")
					.isTrue();

			assertThat(ServerRecorderBadUri.INSTANCE.nullMethodParams.isEmpty())
					.as("some method got null parameters: %s", ServerRecorderBadUri.INSTANCE.nullMethodParams)
					.isTrue();

			SocketAddress recordedClientSA = ServerRecorderBadUri.INSTANCE.clientAddr;
			assertThat(recordedClientSA)
					.as("recorded client remote socket address %s is different from expected client socket address %s", recordedClientSA, clientSA.get())
					.isEqualTo(clientSA.get());
		}
	}

	private void checkServerConnectionsMicrometer(HttpServerRequest request) {
		String address = formatSocketAddress(request.connectionHostAddress());
		boolean isHttp2 = request.requestHeaders().contains(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text());
		assertGauge(registry, SERVER_CONNECTIONS_TOTAL, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(1);
		if (isHttp2) {
			assertGauge(registry, SERVER_STREAMS_ACTIVE, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(1);
		}
		else {
			assertGauge(registry, SERVER_CONNECTIONS_ACTIVE, URI, HTTP, LOCAL_ADDRESS, address).hasValueEqualTo(1);
		}
	}

	private void checkServerConnectionsRecorder(HttpServerRequest request) {
		try {
			String address = formatSocketAddress(request.hostAddress());
			boolean isHttp2 = request.requestHeaders().contains(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text());
			assertThat(ServerRecorder.INSTANCE.onServerConnectionsAmount.get()).isEqualTo(1);
			assertThat(ServerRecorder.INSTANCE.onServerConnectionsLocalAddr.get()).isEqualTo(address);
			if (!isHttp2) {
				assertThat(ServerRecorder.INSTANCE.onActiveConnectionsAmount.get()).isEqualTo(1);
				assertThat(ServerRecorder.INSTANCE.onActiveConnectionsLocalAddr.get()).isEqualTo(address);
			}
			assertThat(ServerRecorder.INSTANCE.onInactiveConnectionsLocalAddr.get()).isNull();
		}
		catch (Throwable error) {
			ServerRecorder.INSTANCE.error.set(error);
		}
	}

	private void checkExpectationsExisting(String uri, String serverAddress, int connIndex, boolean checkTls,
			int numWrites, double expectedSentAmount) {
		String[] timerTags1 = new String[] {URI, uri, METHOD, "POST", STATUS, "200"};
		String[] timerTags2 = new String[] {URI, uri, METHOD, "POST"};
		String[] summaryTags1 = new String[] {URI, uri};

		assertTimer(registry, SERVER_RESPONSE_TIME, timerTags1)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, SERVER_DATA_SENT_TIME, timerTags1)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, SERVER_DATA_RECEIVED_TIME, timerTags2)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertDistributionSummary(registry, SERVER_DATA_SENT, summaryTags1)
				.hasCountGreaterThanOrEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(12);
		assertDistributionSummary(registry, SERVER_DATA_RECEIVED, summaryTags1)
				.hasCountGreaterThanOrEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(12);
		assertCounter(registry, SERVER_ERRORS, summaryTags1).isNull();

		timerTags1 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, uri, METHOD, "POST", STATUS, "200"};
		timerTags2 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, uri, METHOD, "POST"};
		String[] timerTags3 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, STATUS, "SUCCESS"};
		summaryTags1 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, uri};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, "http"};

		assertTimer(registry, CLIENT_RESPONSE_TIME, timerTags1)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, CLIENT_DATA_SENT_TIME, timerTags2)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, CLIENT_DATA_RECEIVED_TIME, timerTags1)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, CLIENT_CONNECT_TIME, timerTags3)
				.hasCountEqualTo(connIndex)
				.hasTotalTimeGreaterThan(0);
		if (checkTls) {
			assertTimer(registry, CLIENT_TLS_HANDSHAKE_TIME, timerTags3)
					.hasCountEqualTo(connIndex)
					.hasTotalTimeGreaterThan(0);
		}
		assertDistributionSummary(registry, CLIENT_DATA_SENT, summaryTags1)
				.hasCountGreaterThanOrEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(12);
		assertDistributionSummary(registry, CLIENT_DATA_RECEIVED, summaryTags1)
				.hasCountGreaterThanOrEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(12);
		assertCounter(registry, CLIENT_ERRORS, summaryTags1).isNull();
		assertDistributionSummary(registry, CLIENT_DATA_SENT, summaryTags2)
				.hasCountGreaterThanOrEqualTo(numWrites)
				.hasTotalAmountGreaterThanOrEqualTo(expectedSentAmount);
		// the following is commented because the number of reads may vary depending on the OS used
		//checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags2, true, 3*index, 84*index);
		assertCounter(registry, CLIENT_ERRORS, summaryTags2).isNull();
	}

	private void checkExpectationsNonExisting(String serverAddress, int connIndex, int index, boolean checkTls,
			int numWrites, @SuppressWarnings("unused")int numReads, double expectedSentAmount,
			@SuppressWarnings("unused") double expectedReceivedAmount) {
		String uri = "/3";
		String[] timerTags1 = new String[] {URI, uri, METHOD, "GET", STATUS, "404"};
		String[] timerTags2 = new String[] {URI, uri, METHOD, "GET"};
		String[] summaryTags1 = new String[] {URI, uri};

		assertTimer(registry, SERVER_RESPONSE_TIME, timerTags1)
				.hasCountEqualTo(index)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, SERVER_DATA_SENT_TIME, timerTags1)
				.hasCountEqualTo(index)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, SERVER_DATA_RECEIVED_TIME, timerTags2)
				.hasCountEqualTo(index)
				.hasTotalTimeGreaterThan(0);
		assertDistributionSummary(registry, SERVER_DATA_SENT, summaryTags1)
				.hasCountGreaterThanOrEqualTo(index)
				.hasTotalAmountGreaterThanOrEqualTo(0);
		assertCounter(registry, SERVER_ERRORS, summaryTags1).isNull();

		timerTags1 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, uri, METHOD, "GET", STATUS, "404"};
		timerTags2 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, uri, METHOD, "GET"};
		String[] timerTags3 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, STATUS, "SUCCESS"};
		summaryTags1 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, uri};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, "http"};

		assertTimer(registry, CLIENT_RESPONSE_TIME, timerTags1)
				.hasCountEqualTo(index)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, CLIENT_DATA_SENT_TIME, timerTags2)
				.hasCountEqualTo(index)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, CLIENT_DATA_RECEIVED_TIME, timerTags1)
				.hasCountEqualTo(index)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, CLIENT_CONNECT_TIME, timerTags3)
				.hasCountEqualTo(connIndex)
				.hasTotalTimeGreaterThan(0);
		if (checkTls) {
			assertTimer(registry, CLIENT_TLS_HANDSHAKE_TIME, timerTags3)
					.hasCountEqualTo(connIndex)
					.hasTotalTimeGreaterThan(0);
		}
		assertDistributionSummary(registry, CLIENT_DATA_RECEIVED, summaryTags1)
				.hasCountGreaterThanOrEqualTo(index)
				.hasTotalAmountGreaterThanOrEqualTo(0);
		assertCounter(registry, CLIENT_ERRORS, summaryTags1).isNull();
		assertDistributionSummary(registry, CLIENT_DATA_SENT, summaryTags2)
				.hasCountGreaterThanOrEqualTo(numWrites)
				.hasTotalAmountGreaterThanOrEqualTo(expectedSentAmount);
		// the following is commented because the number of reads may vary depending on the OS used
		//checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags2, numReads, expectedReceivedAmount);
		assertCounter(registry, CLIENT_ERRORS, summaryTags2).isNull();
	}

	private void checkExpectationsBadRequest(String serverAddress, boolean checkTls) {
		String uri = "/max_header_size";
		String[] timerTags1 = new String[] {URI, uri, METHOD, "GET", STATUS, "431"};
		String[] summaryTags1 = new String[] {URI, uri};

		assertTimer(registry, SERVER_RESPONSE_TIME, timerTags1)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, SERVER_DATA_SENT_TIME, timerTags1)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertDistributionSummary(registry, SERVER_DATA_SENT, summaryTags1)
				.hasCountGreaterThanOrEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(0);
		assertCounter(registry, SERVER_ERRORS, summaryTags1).isNull();

		timerTags1 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, uri, METHOD, "GET", STATUS, "431"};
		String[] timerTags2 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, uri, METHOD, "GET"};
		String[] timerTags3 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, STATUS, "SUCCESS"};
		summaryTags1 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, uri};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, "http"};

		assertTimer(registry, CLIENT_RESPONSE_TIME, timerTags1)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, CLIENT_DATA_SENT_TIME, timerTags2)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, CLIENT_DATA_RECEIVED_TIME, timerTags1)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertTimer(registry, CLIENT_CONNECT_TIME, timerTags3)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		if (checkTls) {
			assertTimer(registry, CLIENT_TLS_HANDSHAKE_TIME, timerTags3)
					.hasCountEqualTo(1)
					.hasTotalTimeGreaterThan(0);
		}
		assertDistributionSummary(registry, CLIENT_DATA_RECEIVED, summaryTags1)
				.hasCountGreaterThanOrEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(0);
		assertCounter(registry, CLIENT_ERRORS, summaryTags1).isNull();
		assertDistributionSummary(registry, CLIENT_DATA_SENT, summaryTags2)
				.hasCountGreaterThanOrEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(118);
		assertCounter(registry, CLIENT_ERRORS, summaryTags2).isNull();
	}

	@SuppressWarnings("deprecation")
	HttpServer customizeServerOptions(HttpServer httpServer, @Nullable ProtocolSslContextSpec ctx, HttpProtocol[] protocols) {
		return ctx == null ? httpServer.protocol(protocols) : httpServer.protocol(protocols).secure(spec -> spec.sslContext(ctx));
	}

	@SuppressWarnings("deprecation")
	HttpClient customizeClientOptions(HttpClient httpClient, @Nullable ProtocolSslContextSpec ctx, HttpProtocol[] protocols) {
		return ctx == null ? httpClient.protocol(protocols) : httpClient.protocol(protocols).secure(spec -> spec.sslContext(ctx));
	}

	static Stream<Arguments> http11CompatibleProtocols() {
		return Stream.of(
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11},
						Named.of("Http11SslContextSpec", serverCtx11), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11}, null, null)
		);
	}

	static Stream<Arguments> httpCompatibleProtocols() {
		return Stream.of(
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11},
						Named.of("Http11SslContextSpec", serverCtx11), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http11SslContextSpec", serverCtx11), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C}, new HttpProtocol[]{HttpProtocol.H2C}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, null, null)
		);
	}

	private static final String HTTP = "http";

	private static final String SERVER_CONNECTIONS_ACTIVE = HTTP_SERVER_PREFIX + CONNECTIONS_ACTIVE;
	private static final String SERVER_CONNECTIONS_TOTAL = HTTP_SERVER_PREFIX + CONNECTIONS_TOTAL;
	private static final String SERVER_STREAMS_ACTIVE = HTTP_SERVER_PREFIX + STREAMS_ACTIVE;
	private static final String SERVER_RESPONSE_TIME = HTTP_SERVER_PREFIX + RESPONSE_TIME;
	private static final String SERVER_DATA_SENT_TIME = HTTP_SERVER_PREFIX + DATA_SENT_TIME;
	private static final String SERVER_DATA_RECEIVED_TIME = HTTP_SERVER_PREFIX + DATA_RECEIVED_TIME;
	private static final String SERVER_DATA_SENT = HTTP_SERVER_PREFIX + DATA_SENT;
	private static final String SERVER_DATA_RECEIVED = HTTP_SERVER_PREFIX + DATA_RECEIVED;
	private static final String SERVER_ERRORS = HTTP_SERVER_PREFIX + ERRORS;

	private static final String CLIENT_CONNECTIONS_ACTIVE = HTTP_CLIENT_PREFIX + CONNECTIONS_ACTIVE;
	private static final String CLIENT_CONNECTIONS_TOTAL = HTTP_CLIENT_PREFIX + CONNECTIONS_TOTAL;
	private static final String CLIENT_RESPONSE_TIME = HTTP_CLIENT_PREFIX + RESPONSE_TIME;
	private static final String CLIENT_DATA_SENT_TIME = HTTP_CLIENT_PREFIX + DATA_SENT_TIME;
	private static final String CLIENT_DATA_RECEIVED_TIME = HTTP_CLIENT_PREFIX + DATA_RECEIVED_TIME;
	private static final String CLIENT_DATA_SENT = HTTP_CLIENT_PREFIX + DATA_SENT;
	private static final String CLIENT_DATA_RECEIVED = HTTP_CLIENT_PREFIX + DATA_RECEIVED;
	static final String CLIENT_ERRORS = HTTP_CLIENT_PREFIX + ERRORS;
	private static final String CLIENT_CONNECT_TIME = HTTP_CLIENT_PREFIX + CONNECT_TIME;
	private static final String CLIENT_TLS_HANDSHAKE_TIME = HTTP_CLIENT_PREFIX + TLS_HANDSHAKE_TIME;

	static final class ClientContextAwareRecorder extends ContextAwareHttpClientMetricsRecorder {

		static final ClientContextAwareRecorder INSTANCE = new ClientContextAwareRecorder();

		final AtomicBoolean onDataReceivedContextView = new AtomicBoolean();
		final AtomicBoolean onDataSentContextView = new AtomicBoolean();

		@Override
		public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress, String uri) {
		}

		@Override
		public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
		}

		@Override
		public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
		}

		@Override
		public void recordDataReceivedTime(ContextView contextView, SocketAddress remoteAddress, String uri,
				String method, String status, Duration time) {
			onDataReceivedContextView.set("OK".equals(contextView.getOrDefault("testContextAwareRecorder", "KO")));
		}

		@Override
		public void recordDataSentTime(ContextView contextView, SocketAddress remoteAddress, String uri, String method,
				Duration time) {
			onDataSentContextView.set("OK".equals(contextView.getOrDefault("testContextAwareRecorder", "KO")));
		}

		@Override
		public void recordResponseTime(ContextView contextView, SocketAddress remoteAddress, String uri,
				String method, String status, Duration time) {
		}

		@Override
		public void incrementErrorsCount(ContextView contextView, SocketAddress socketAddress) {
		}

		@Override
		public void recordConnectTime(ContextView contextView, SocketAddress socketAddress, Duration duration, String s) {
		}

		@Override
		public void recordDataReceived(ContextView contextView, SocketAddress socketAddress, long l) {
		}

		@Override
		public void recordDataSent(ContextView contextView, SocketAddress socketAddress, long l) {
		}

		@Override
		public void recordTlsHandshakeTime(ContextView contextView, SocketAddress socketAddress, Duration duration, String s) {
		}

		@Override
		public void recordResolveAddressTime(SocketAddress socketAddress, Duration duration, String s) {
		}
	}

	static final class ServerContextAwareRecorder extends ContextAwareHttpServerMetricsRecorder {

		static final ServerContextAwareRecorder INSTANCE = new ServerContextAwareRecorder();

		final AtomicBoolean onDataReceivedContextView = new AtomicBoolean();
		final AtomicBoolean onDataSentContextView = new AtomicBoolean();

		@Override
		public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress, String uri) {
		}

		@Override
		public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
		}

		@Override
		public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
		}

		@Override
		public void recordDataReceivedTime(ContextView contextView, String uri, String method, Duration time) {
			onDataReceivedContextView.set("OK".equals(contextView.getOrDefault("testContextAwareRecorder", "KO")));
		}

		@Override
		public void recordDataSentTime(ContextView contextView, String uri, String method, String status, Duration time) {
			onDataSentContextView.set("OK".equals(contextView.getOrDefault("testContextAwareRecorder", "KO")));
		}

		@Override
		public void recordResponseTime(ContextView contextView, String uri, String method, String status, Duration time) {
		}

		@Override
		public void incrementErrorsCount(ContextView contextView, SocketAddress socketAddress) {
		}

		@Override
		public void recordConnectTime(ContextView contextView, SocketAddress socketAddress, Duration duration, String s) {
		}

		@Override
		public void recordDataReceived(ContextView contextView, SocketAddress socketAddress, long l) {
		}

		@Override
		public void recordDataSent(ContextView contextView, SocketAddress socketAddress, long l) {
		}

		@Override
		public void recordTlsHandshakeTime(ContextView contextView, SocketAddress socketAddress, Duration duration, String s) {
		}

		@Override
		public void recordResolveAddressTime(SocketAddress socketAddress, Duration duration, String s) {
		}
	}

	static final class ServerRecorder implements HttpServerMetricsRecorder {

		static final ServerRecorder INSTANCE = new ServerRecorder();
		static final Supplier<ServerRecorder> SUPPLIER = () -> INSTANCE;
		private final AtomicReference<Throwable> error = new AtomicReference<>();
		private final AtomicInteger onServerConnectionsAmount = new AtomicInteger();
		private final AtomicReference<String> onServerConnectionsLocalAddr = new AtomicReference<>();
		private final AtomicReference<String> onActiveConnectionsLocalAddr = new AtomicReference<>();
		private final AtomicReference<String> onInactiveConnectionsLocalAddr = new AtomicReference<>();
		private final AtomicInteger onActiveConnectionsAmount = new AtomicInteger();

		static Supplier<ServerRecorder> supplier() {
			return SUPPLIER;
		}

		void reset() {
			error.set(null);
			onServerConnectionsAmount.set(0);
			onServerConnectionsLocalAddr.set(null);
			onActiveConnectionsLocalAddr.set(null);
			onInactiveConnectionsLocalAddr.set(null);
			onActiveConnectionsAmount.set(0);
		}

		@Override
		public void recordServerConnectionOpened(SocketAddress localAddress) {
			onServerConnectionsLocalAddr.set(formatSocketAddress(localAddress));
			onServerConnectionsAmount.addAndGet(1);
		}

		@Override
		public void recordServerConnectionClosed(SocketAddress localAddress) {
			onServerConnectionsLocalAddr.set(formatSocketAddress(localAddress));
			onServerConnectionsAmount.addAndGet(-1);
		}

		@Override
		public void recordServerConnectionActive(SocketAddress localAddress) {
			onActiveConnectionsLocalAddr.set(formatSocketAddress(localAddress));
			onActiveConnectionsAmount.addAndGet(1);
		}

		@Override
		public void recordServerConnectionInactive(SocketAddress localAddress) {
			onInactiveConnectionsLocalAddr.set(formatSocketAddress(localAddress));
			onActiveConnectionsAmount.addAndGet(-1);
		}

		@Override
		public void recordStreamOpened(SocketAddress localAddress) {
			onActiveConnectionsLocalAddr.set(formatSocketAddress(localAddress));
			onActiveConnectionsAmount.addAndGet(1);
		}

		@Override
		public void recordStreamClosed(SocketAddress localAddress) {
			onInactiveConnectionsLocalAddr.set(formatSocketAddress(localAddress));
			onActiveConnectionsAmount.addAndGet(-1);
		}

		@Override
		public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		}

		@Override
		public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		}

		@Override
		public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		}

		@Override
		public void recordDataReceivedTime(String uri, String method, Duration time) {
		}

		@Override
		public void recordDataSentTime(String uri, String method, String status, Duration time) {
		}

		@Override
		public void recordResponseTime(String uri, String method, String status, Duration time) {
		}

		@Override
		public void recordDataReceived(SocketAddress socketAddress, long l) {
		}

		@Override
		public void recordDataSent(SocketAddress socketAddress, long l) {
		}

		@Override
		public void incrementErrorsCount(SocketAddress socketAddress) {
		}

		@Override
		public void recordTlsHandshakeTime(SocketAddress socketAddress, Duration duration, String s) {
		}

		@Override
		public void recordConnectTime(SocketAddress socketAddress, Duration duration, String s) {
		}

		@Override
		public void recordResolveAddressTime(SocketAddress socketAddress, Duration duration, String s) {
		}
	}

	/**
	 * Server metrics recorder used to verify that HttpServerMetricsRecorder method parameters
	 * are not null when a bad request URI is received.
	 */
	static final class ServerRecorderBadUri implements HttpServerMetricsRecorder {

		static final ServerRecorderBadUri INSTANCE = new ServerRecorderBadUri();
		final ConcurrentLinkedQueue<String> nullMethodParams = new ConcurrentLinkedQueue<>();
		volatile CountDownLatch closed;
		volatile SocketAddress clientAddr;

		void init() {
			nullMethodParams.clear();
			closed = new CountDownLatch(1);
			clientAddr = null;
		}

		void checkNullParam(String method, Object... params) {
			if (Arrays.stream(params).anyMatch(Objects::isNull)) {
				nullMethodParams.add(method);
			}
		}

		@Override
		public void recordServerConnectionOpened(SocketAddress localAddress) {
			checkNullParam("recordServerConnectionOpened", localAddress);
		}

		@Override
		public void recordServerConnectionClosed(SocketAddress localAddress) {
			checkNullParam("recordServerConnectionClosed", localAddress);
			closed.countDown();
		}

		@Override
		public void recordServerConnectionActive(SocketAddress localAddress) {
			checkNullParam("recordServerConnectionActive", localAddress);
		}

		@Override
		public void recordServerConnectionInactive(SocketAddress localAddress) {
			checkNullParam("recordServerConnectionInactive", localAddress);
		}

		@Override
		public void recordStreamOpened(SocketAddress localAddress) {
			checkNullParam("recordStreamOpened", localAddress);
		}

		@Override
		public void recordStreamClosed(SocketAddress localAddress) {
			checkNullParam("recordStreamClosed", localAddress);
		}

		@Override
		public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
			checkNullParam("recordDataReceived", remoteAddress, uri);
		}

		@Override
		public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
			checkNullParam("recordDataSent", remoteAddress, uri);
			clientAddr = remoteAddress;
		}

		@Override
		public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
			checkNullParam("incrementErrorsCount", remoteAddress, uri);
		}

		@Override
		public void recordDataReceivedTime(String uri, String method, Duration time) {
			checkNullParam("recordDataReceivedTime", uri, method, time);
		}

		@Override
		public void recordDataSentTime(String uri, String method, String status, Duration time) {
			checkNullParam("recordDataSentTime", uri, method, status, time);
		}

		@Override
		public void recordResponseTime(String uri, String method, String status, Duration time) {
			checkNullParam("recordResponseTime", uri, method, status, time);
		}

		@Override
		public void recordDataReceived(SocketAddress socketAddress, long l) {
			checkNullParam("recordDataReceived", socketAddress);
		}

		@Override
		public void recordDataSent(SocketAddress socketAddress, long l) {
			checkNullParam("recordDataSent", socketAddress);
		}

		@Override
		public void incrementErrorsCount(SocketAddress socketAddress) {
			checkNullParam("incrementErrorsCount", socketAddress);
		}

		@Override
		public void recordTlsHandshakeTime(SocketAddress socketAddress, Duration duration, String s) {
			checkNullParam("recordTlsHandshakeTime", socketAddress, duration, s);
		}

		@Override
		public void recordConnectTime(SocketAddress socketAddress, Duration duration, String s) {
			checkNullParam("recordConnectTime", socketAddress, duration, s);
		}

		@Override
		public void recordResolveAddressTime(SocketAddress socketAddress, Duration duration, String s) {
			checkNullParam("recordResolveAddressTime", socketAddress, duration, s);
		}
	}

	/**
	 * Server metrics recorder used to verify that HttpServerMetricsRecorder method parameters
	 * are not null when a bad request URI is received.
	 */
	static final class ContextAwareServerRecorderBadUri extends ContextAwareHttpServerMetricsRecorder {

		static final ContextAwareServerRecorderBadUri INSTANCE = new ContextAwareServerRecorderBadUri();
		final ConcurrentLinkedQueue<String> nullMethodParams = new ConcurrentLinkedQueue<>();
		volatile CountDownLatch closed;
		volatile SocketAddress clientAddr;

		void init() {
			nullMethodParams.clear();
			closed = new CountDownLatch(1);
			clientAddr = null;
		}

		void checkNullParam(String method, Object... params) {
			if (Arrays.stream(params).anyMatch(Objects::isNull)) {
				nullMethodParams.add(method);
			}
		}

		@Override
		public void recordServerConnectionOpened(SocketAddress localAddress) {
			checkNullParam("recordServerConnectionOpened", localAddress);
		}

		@Override
		public void recordServerConnectionClosed(SocketAddress localAddress) {
			checkNullParam("recordServerConnectionClosed", localAddress);
			closed.countDown();
		}

		@Override
		public void recordServerConnectionActive(SocketAddress localAddress) {
			checkNullParam("recordServerConnectionActive", localAddress);
		}

		@Override
		public void recordServerConnectionInactive(SocketAddress localAddress) {
			checkNullParam("recordServerConnectionInactive", localAddress);
		}

		@Override
		public void recordStreamOpened(SocketAddress localAddress) {
			checkNullParam("recordStreamOpened", localAddress);
		}

		@Override
		public void recordStreamClosed(SocketAddress localAddress) {
			checkNullParam("recordStreamClosed", localAddress);
		}

		@Override
		public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
			checkNullParam("recordDataReceived", contextView, remoteAddress, uri);
		}

		@Override
		public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
			checkNullParam("recordDataSent", contextView, remoteAddress, uri);
			clientAddr = remoteAddress;
		}

		@Override
		public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress, String uri) {
			checkNullParam("incrementErrorsCount", contextView, remoteAddress, uri);
		}

		@Override
		public void recordDataReceivedTime(ContextView contextView, String uri, String method, Duration time) {
			checkNullParam("recordDataReceivedTime", contextView, uri, method, time);
		}

		@Override
		public void recordDataSentTime(ContextView contextView, String uri, String method, String status, Duration time) {
			checkNullParam("recordDataSentTime", contextView, uri, method, status, time);
		}

		@Override
		public void recordResponseTime(ContextView contextView, String uri, String method, String status, Duration time) {
			checkNullParam("recordResponseTime", contextView, uri, method, status, time);
		}

		@Override
		public void recordDataReceived(ContextView contextView, SocketAddress socketAddress, long l) {
			checkNullParam("recordDataReceived", contextView, socketAddress);
		}

		@Override
		public void recordDataSent(ContextView contextView, SocketAddress socketAddress, long l) {
			checkNullParam("recordDataSent", contextView, socketAddress);
		}

		@Override
		public void incrementErrorsCount(ContextView contextView, SocketAddress socketAddress) {
			checkNullParam("incrementErrorsCount", contextView, socketAddress);
		}

		@Override
		public void recordTlsHandshakeTime(ContextView contextView, SocketAddress socketAddress, Duration duration, String s) {
			checkNullParam("recordTlsHandshakeTime", contextView, socketAddress, duration, s);
		}

		@Override
		public void recordConnectTime(ContextView contextView, SocketAddress socketAddress, Duration duration, String s) {
			checkNullParam("recordConnectTime", contextView, socketAddress, duration, s);
		}

		@Override
		public void recordResolveAddressTime(SocketAddress socketAddress, Duration duration, String s) {
			checkNullParam("recordResolveAddressTime", socketAddress, duration, s);
		}
	}

	/**
	 * Server Handler used to detect when the last http response content has been sent to the client.
	 * Handler placed before the HttpMetricsHandler on the Server pipeline.
	 * Metrics are up-to-date when the latch is counted down.
	 */
	static final class ResponseSentHandler extends ChannelOutboundHandlerAdapter {
		static final String HANDLER_NAME = "ServerCompletedHandler.handler";
		static final ResponseSentHandler INSTANCE = new ResponseSentHandler();
		AtomicReference<CountDownLatch> latchRef;

		void register(AtomicReference<CountDownLatch> latchRef, ChannelPipeline pipeline) {
			this.latchRef = latchRef;
			pipeline.addBefore(NettyPipeline.HttpMetricsHandler, HANDLER_NAME, this);
		}

		void register(CountDownLatch latch, ChannelPipeline pipeline) {
			register(new AtomicReference<>(latch), pipeline);
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			if (msg instanceof LastHttpContent) {
				promise.addListener(future -> latchRef.get().countDown());
			}

			ctx.write(msg, promise);
		}

		@Override
		public boolean isSharable() {
			return true; // A server may accept multiple connections, hence this handler must be sharable
		}
	}

	/**
	 * Server Handler used to detect when the last http client request content has been received by the server.
	 * Handler placed after the HttpMetricsHandler on the Server pipeline.
	 * Metrics are up-to-date when the latch is counted down.
	 */
	static final class RequestReceivedHandler extends ChannelInboundHandlerAdapter {
		static final RequestReceivedHandler INSTANCE = new RequestReceivedHandler();
		static final String HANDLER_NAME = "ServerReceivedHandler.handler";
		AtomicReference<CountDownLatch> latchRef;

		void register(AtomicReference<CountDownLatch> latchRef, ChannelPipeline pipeline) {
			this.latchRef = latchRef;
			pipeline.addAfter(NettyPipeline.HttpMetricsHandler, HANDLER_NAME, this);
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			if (msg instanceof LastHttpContent) {
				latchRef.get().countDown();
			}
			ctx.fireChannelRead(msg);
		}

		@Override
		public boolean isSharable() {
			return true;
		}
	}

	/**
	 * Server handler used to wait until the client socket is closed on the server side.
	 * For HTTP1.1, the handler is placed before the ReactorBridge, so all previous handlers will see
	 * the close before this handler. For HTTP2, the handler is placed lastly on the pipeline.
	 */
	static class ServerCloseHandler extends ChannelInboundHandlerAdapter {
		static final ServerCloseHandler INSTANCE = new ServerCloseHandler();
		static final String HANDLER_NAME = "ServerCloseHandler.handler";
		private CountDownLatch latch;
		private boolean registered;

		void register(Channel channel) {
			this.latch = new CountDownLatch(1);
			registerInternal(channel);
			registered = true;
		}

		void registerInternal(Channel channel) {
			if (channel instanceof Http2StreamChannel) {
				if (channel.parent().pipeline().get(HANDLER_NAME) == null) {
					channel.parent().pipeline().addLast(HANDLER_NAME, this);
				}
			}
			else {
				channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, HANDLER_NAME, this);
			}
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			latch.countDown();
			ctx.fireChannelInactive();
		}

		@Override
		public boolean isSharable() {
			return true;
		}

		public boolean awaitClientClosedOnServer() throws InterruptedException {
			if (registered) {
				try {
					return latch.await(30, TimeUnit.SECONDS);
				}
				finally {
					registered = false;
				}
			}
			return true;
		}
	}

	static final class StreamCloseHandler extends ServerCloseHandler {
		static final StreamCloseHandler INSTANCE = new StreamCloseHandler();

		@Override
		void registerInternal(Channel channel) {
			if (channel.pipeline().get(HANDLER_NAME) == null) {
				channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, HANDLER_NAME, this);
			}
		}
	}

	/**
	 * Handler used to get notified when an exception occurs on the HttpClientMetricsHandler. This handler is placed
	 * after the reactor.left.httpMetricsHandler.
	 */
	static final class ClientExceptionHandler extends ChannelDuplexHandler {
		static final ClientExceptionHandler INSTANCE = new ClientExceptionHandler();
		static final String HANDLER_NAME = "ExceptionHandler.handler";
		private CountDownLatch latch;

		void register(Channel channel, CountDownLatch latch) {
			this.latch = latch;
			channel.pipeline().addAfter(NettyPipeline.HttpMetricsHandler, HANDLER_NAME, this);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			latch.countDown();
			ctx.fireExceptionCaught(cause);
		}
	}
}
