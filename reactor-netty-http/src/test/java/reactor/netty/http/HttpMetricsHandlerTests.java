/*
 * Copyright (c) 2019-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.HTTP_CLIENT_PREFIX;
import static reactor.netty.Metrics.HTTP_SERVER_PREFIX;
import static reactor.netty.Metrics.LOCAL_ADDRESS;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;
import static reactor.netty.Metrics.URI;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.ContextAwareHttpClientMetricsRecorder;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.ContextAwareHttpServerMetricsRecorder;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerMetricsRecorder;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * <b>Note (Pierre): the testServerConnectionsContextAwareRecorder is partially commented because of the existing
 * https://github.com/reactor/reactor-netty/issues/1854 issue ("ContextView in default implementations before ContextAwareHttpServerMetricsRecorder").
 * Once #1854 is fixed, we can then uncomment some checks from testServerConnectionsContextAwareRecorder test.
 * </b>
 *  @author Violeta Georgieva
 *  @author Pierre De Rop
 */
class HttpMetricsHandlerTests extends BaseHttpTest {
	HttpServer httpServer;
	private ConnectionProvider provider;
	HttpClient httpClient;
	private MeterRegistry registry;

	final Flux<ByteBuf> body = ByteBufFlux.fromString(Flux.just("Hello", " ", "World", "!")).delayElements(Duration.ofMillis(10));
	private volatile double currentTotalConnections;
	private volatile double currentActiveConnections;

	private static final String SERVER_TOTAL_CONNECTIONS = HTTP_SERVER_PREFIX + TOTAL_CONNECTIONS;
	private static final String SERVER_ACTIVE_CONNECTIONS = HTTP_SERVER_PREFIX + ACTIVE_CONNECTIONS;
	private static final String MYTAG = "myuritag";
	private final static String HTTP = "http";
	private final static String POST = "POST";

	/**
	 * Initialization done before running each test.
	 * <ul>
	 *  <li> /1 is used by testExistingEndpoint test</li>
	 *  <li> /2 is used by testExistingEndpoint, and testUriTagValueFunctionNotSharedForClient tests</li>
	 *  <li> /3 does not exists but is used by testNonExistingEndpoint, checkExpectationsNonExisting tests</li>
	 *  <li> /4 is used by testServerConnectionsMicrometer test where no uriTagValue mapper function is used</li>
	 *  <li> /5 is used by testServerConnectionsMicrometer test where an uriTagValue mapper function is used</li>
	 *  <li> /6 is used by testServerConnectionsRecorder test where no uriTagValue mapper function is used</li>
	 *  <li> /7 is used by testServerConnectionsRecorder test where an uriTagValue mapper function is used</li>
	 *  <li> /8 is used by testServerConnectionsContextAwareRecorder test where no uriTagValue mapper function is used</li>
	 *  <li> /9 is used by testServerConnectionsContextAwareRecorder test where an uriTagValue mapper function is used</li>
	 * </ul>
	 */
	@BeforeEach
	void setUp() {
		httpServer = customizeServerOptions(createServer()
				.host("127.0.0.1")
				.metrics(true, Function.identity())
				.route(r -> r.post("/1", (req, res) -> res.header("Connection", "close")
								.send(req.receive().retain().delayElements(Duration.ofMillis(10))))
						.post("/2", (req, res) -> res.header("Connection", "close")
								.send(req.receive().retain().delayElements(Duration.ofMillis(10))))
						.post("/4", (req, res) -> res.header("Connection", "close")
								.send(req.receive().retain().doOnNext(b ->
										checkServerConnectionsMicrometer(req.hostAddress(), req.uri()))))
						.post("/5", (req, res) -> res.header("Connection", "close")
								.send(req.receive().retain().doOnNext(b ->
										checkServerConnectionsMicrometer(req.hostAddress(), MYTAG))))
						.post("/6", (req, res) -> res.header("Connection", "close")
								.send(req.receive().retain().doOnNext(b ->
										checkServerConnectionsRecorder(req.hostAddress(), req.uri()))))
						.post("/7", (req, res) -> res.header("Connection", "close")
								.send(req.receive().retain().doOnNext(b ->
										checkServerConnectionsRecorder(req.hostAddress(), MYTAG))))
						.post("/8", (req, res) -> res.header("Connection", "close")
								.send(req.receive().retain()
										.contextWrite(Context.of("testContextAwareRecorder", "OK"))
										.doOnNext(b -> checkServerConnectionsContextAwareRecorder(req.hostAddress(), req.uri()))))
						.post("/9", (req, res) -> res.header("Connection", "close")
								.send(req.receive().retain()
										.contextWrite(Context.of("testContextAwareRecorder", "OK"))
										.doOnNext(b -> checkServerConnectionsContextAwareRecorder(req.hostAddress(), MYTAG))))));

		provider = ConnectionProvider.create("HttpMetricsHandlerTests", 1);
		httpClient = customizeClientOptions(createClient(provider, () -> disposableServer.address())
				.metrics(true, Function.identity()));

		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() {
		provider.disposeLater()
		        .block(Duration.ofSeconds(30));

		if (disposableServer != null) {
			disposableServer.disposeNow();
		}

		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();

		ServerRecorder.INSTANCE.reset();
		ContextAwareServerRecorder.INSTANCE.reset();
	}

	@Test
	void testExistingEndpoint() throws Exception {
		disposableServer = httpServer.bindNow();

		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = httpClient.doAfterRequest((req, conn) ->
			serverAddress.set(conn.channel().remoteAddress())
		);

		CountDownLatch latch1 = new CountDownLatch(1);
		StepVerifier.create(httpClient.doOnResponse((res, conn) ->
		                                  conn.channel()
		                                      .closeFuture()
		                                      .addListener(f -> latch1.countDown()))
		                              .post()
		                              .uri("/1")
		                              .send(body)
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch1.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsExisting("/1", sa.getHostString() + ":" + sa.getPort(), 1);

		CountDownLatch latch2 = new CountDownLatch(1);
		StepVerifier.create(httpClient.doOnResponse((res, conn) ->
		                                  conn.channel()
		                                      .closeFuture()
		                                      .addListener(f -> latch2.countDown()))
		                              .post()
		                              .uri("/2?i=1&j=2")
		                              .send(body)
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch2.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsExisting("/2", sa.getHostString() + ":" + sa.getPort(), 2);
	}

	@Test
	void testNonExistingEndpoint() throws Exception {
		disposableServer = httpServer.bindNow();

		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = httpClient.doAfterRequest((req, conn) ->
			serverAddress.set(conn.channel().remoteAddress())
		);

		CountDownLatch latch1 = new CountDownLatch(1);
		StepVerifier.create(httpClient.doOnResponse((res, conn) ->
		                                  conn.channel()
		                                      .closeFuture()
		                                      .addListener(f -> latch1.countDown()))
		                              .headers(h -> h.add("Connection", "close"))
		                              .get()
		                              .uri("/3")
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch1.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsNonExisting(sa.getHostString() + ":" + sa.getPort(), 1);

		CountDownLatch latch2 = new CountDownLatch(1);
		StepVerifier.create(httpClient.doOnResponse((res, conn) ->
		                                  conn.channel()
		                                      .closeFuture()
		                                      .addListener(f -> latch2.countDown()))
		                              .headers(h -> h.add("Connection", "close"))
		                              .get()
		                              .uri("/3")
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch2.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsNonExisting(sa.getHostString() + ":" + sa.getPort(), 2);
	}

	@Test
	void testUriTagValueFunction() throws Exception {
		disposableServer = httpServer.metrics(true, s -> "testUriTagValueResolver").bindNow();

		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = httpClient.doAfterRequest((req, conn) ->
			serverAddress.set(conn.channel().remoteAddress())
		);

		CountDownLatch latch1 = new CountDownLatch(1);
		StepVerifier.create(httpClient.doOnResponse((res, conn) ->
		                                  conn.channel()
		                                      .closeFuture()
		                                      .addListener(f -> latch1.countDown()))
		                              .metrics(true, s -> "testUriTagValueResolver")
		                              .post()
		                              .uri("/1")
		                              .send(body)
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch1.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsExisting("testUriTagValueResolver", sa.getHostString() + ":" + sa.getPort(), 1);
	}

	/**
	 * https://github.com/reactor/reactor-netty/issues/1559
	 */
	@Test
	void testUriTagValueFunctionNotSharedForClient() throws Exception {
		disposableServer =
				httpServer.metrics(true,
				                s -> {
				                    if ("/1".equals(s)) {
				                        return "testUriTagValueFunctionNotShared_1";
				                    }
				                    else {
				                        return "testUriTagValueFunctionNotShared_2";
				                    }
				                })
				          .bindNow();

		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = httpClient.doAfterRequest((req, conn) ->
				serverAddress.set(conn.channel().remoteAddress())
		);

		CountDownLatch latch1 = new CountDownLatch(1);
		httpClient.doOnResponse((res, conn) -> conn.channel()
		                                           .closeFuture()
		                                           .addListener(f -> latch1.countDown()))
		          .metrics(true, s -> "testUriTagValueFunctionNotShared_1")
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

		assertThat(latch1.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsExisting("testUriTagValueFunctionNotShared_1", sa.getHostString() + ":" + sa.getPort(), 1);

		CountDownLatch latch2 = new CountDownLatch(1);
		httpClient.doOnResponse((res, conn) -> conn.channel()
		                                           .closeFuture()
		                                           .addListener(f -> latch2.countDown()))
		          .metrics(true, s -> "testUriTagValueFunctionNotShared_2")
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

		assertThat(latch1.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsExisting("testUriTagValueFunctionNotShared_2", sa.getHostString() + ":" + sa.getPort(), 2);
	}

	@Test
	void testContextAwareRecorder() throws Exception {
		disposableServer = httpServer.bindNow();

		ContextAwareRecorder recorder = ContextAwareRecorder.INSTANCE;
		CountDownLatch latch = new CountDownLatch(1);
		httpClient.doOnResponse((res, conn) -> conn.channel()
		                                           .closeFuture()
		                                           .addListener(f -> latch.countDown()))
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

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(recorder.onDataReceivedContextView).isTrue();
		assertThat(recorder.onDataSentContextView).isTrue();
	}

	@Test
	void testServerConnectionsMicrometer() throws Exception {
		testServerConnectionsMicrometer(false);
	}

	@Test
	void testServerConnectionsMicrometerUriTagMapper() throws Exception {
		testServerConnectionsMicrometer(true);
	}

	@Test
	void testServerConnectionsRecorder() throws Exception {
		testServerConnectionsRecorder(false);
	}

	@Test
	void testServerConnectionsRecorderUriTagMapper() throws Exception {
		testServerConnectionsRecorder(true);
	}

	@Test
	void testServerConnectionsContextAwareRecorder() throws Exception {
		testServerConnectionsContextAwareRecorder(false);
	}

	@Test
	void testServerConnectionsContextAwareRecorderUriTagMapper() throws Exception {
		testServerConnectionsContextAwareRecorder(true);
	}

	private void testServerConnectionsMicrometer(boolean uriTagMapper) throws Exception {
		disposableServer = uriTagMapper ?
				httpServer.metrics(true, u -> "/5".equals(u) ? MYTAG : u).bindNow() :
				httpServer.metrics(true, Function.identity()).bindNow();

		String uri = uriTagMapper ? MYTAG : "/4";
		String address = reactor.netty.Metrics.formatSocketAddress(disposableServer.address());
		currentTotalConnections = getCounter(SERVER_TOTAL_CONNECTIONS, 0, URI, HTTP, LOCAL_ADDRESS, address);
		currentActiveConnections = getCounter(SERVER_ACTIVE_CONNECTIONS, 0, URI, uri, METHOD, POST);
		CountDownLatch latch = new CountDownLatch(1);

		httpClient.doOnResponse((res, conn) ->
						conn.channel()
								.closeFuture()
								.addListener(f -> latch.countDown()))
				.metrics(true, Function.identity())
				.post()
				.uri(uriTagMapper ? "/5" : "/4")
				.send(body)
				.responseContent()
				.aggregate()
				.asString()
				.as(StepVerifier::create)
				.expectNext("Hello World!")
				.expectComplete()
				.verify(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		checkCounter(SERVER_TOTAL_CONNECTIONS, true, currentTotalConnections, URI, HTTP, LOCAL_ADDRESS, address);
		checkCounter(SERVER_ACTIVE_CONNECTIONS, true, currentActiveConnections, URI, uri, METHOD, POST);

		disposableServer.disposeNow();
	}

	private void testServerConnectionsRecorder(boolean uriTagMapper) throws Exception {
		disposableServer = uriTagMapper ?
				httpServer.metrics(true, () -> ServerRecorder.INSTANCE, u -> "/7".equals(u) ? MYTAG : u).bindNow() :
				httpServer.metrics(true, () -> ServerRecorder.INSTANCE, Function.identity()).bindNow();

		currentTotalConnections = 0;
		currentActiveConnections = 0;
		CountDownLatch latch = new CountDownLatch(1);

		httpClient.doOnResponse((res, conn) ->
						conn.channel()
								.closeFuture()
								.addListener(f -> latch.countDown()))
				.metrics(true, Function.identity())
				.post()
				.uri(uriTagMapper ? "/7" : "/6")
				.send(body)
				.responseContent()
				.aggregate()
				.asString()
				.as(StepVerifier::create)
				.expectNext("Hello World!")
				.expectComplete()
				.verify(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(ServerRecorder.INSTANCE.onServerConnectionsAmount.get()).isEqualTo(0);
		assertThat(ServerRecorder.INSTANCE.onActiveConnectionsAmount.get()).isEqualTo(0);

		disposableServer.disposeNow();
	}

	private void testServerConnectionsContextAwareRecorder(boolean uriTagMapper) throws Exception {
		disposableServer = uriTagMapper ?
				httpServer.metrics(true, () -> ContextAwareServerRecorder.INSTANCE, u -> "/9".equals(u) ? MYTAG : u).bindNow() :
				httpServer.metrics(true, () -> ContextAwareServerRecorder.INSTANCE, Function.identity()).bindNow();

		currentTotalConnections = 0;
		currentActiveConnections = 0;
		CountDownLatch latch = new CountDownLatch(1);

		httpClient.doOnResponse((res, conn) ->
						conn.channel()
								.closeFuture()
								.addListener(f -> latch.countDown()))
				.metrics(false, Function.identity())
				.post()
				.uri(uriTagMapper ? "/9" : "/8")
				.send(body)
				.responseContent()
				.aggregate()
				.asString()
				.as(StepVerifier::create)
				.expectNext("Hello World!")
				.expectComplete()
				.verify(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(ContextAwareServerRecorder.INSTANCE.onServerConnectionsAmount.get()).isEqualTo(0);
		assertThat(ContextAwareServerRecorder.INSTANCE.onActiveConnectionsAmount.get()).isEqualTo(0);

		// Currently, the two following checks can't work until the #1854 is fixed ("ContextView in default implementations before ContextAwareHttpServerMetricsRecorder")
		// Once #1854 is fixed, we can then uncomment the two following checks.

		/*
		assertThat(ContextAwareServerRecorder.INSTANCE.onServerConnectionsContextView.get()).isTrue();
		assertThat(ContextAwareServerRecorder.INSTANCE.onActiveConnectionsContextView.get()).isTrue();
		*/

		disposableServer.disposeNow();
	}

	private void checkServerConnectionsMicrometer(InetSocketAddress localAddress, String uri) {
		String address = reactor.netty.Metrics.formatSocketAddress(localAddress);
		checkCounter(SERVER_TOTAL_CONNECTIONS, true, currentTotalConnections + 1, URI, HTTP, LOCAL_ADDRESS, address);
		checkCounter(SERVER_ACTIVE_CONNECTIONS, true, currentActiveConnections + 1, URI, uri, METHOD, POST);
	}

	private void checkServerConnectionsRecorder(InetSocketAddress localAddress, String uri) {
		String address = reactor.netty.Metrics.formatSocketAddress(localAddress);
		assertThat(ServerRecorder.INSTANCE.onServerConnectionsAmount.get() >= currentTotalConnections + 1).isTrue();
		assertThat(ServerRecorder.INSTANCE.onServerConnectionsLocalAddr.get()).isEqualTo(address);
		assertThat(ServerRecorder.INSTANCE.onActiveConnectionsAmount.get() >= currentActiveConnections + 1).isTrue();
		assertThat(ServerRecorder.INSTANCE.onActiveConnectionsMethod.get()).isEqualTo(POST);
		assertThat(ServerRecorder.INSTANCE.onActiveConnectionsUri.get()).isEqualTo(uri);
	}

	private void checkServerConnectionsContextAwareRecorder(InetSocketAddress localAddress, String uri) {
		String address = reactor.netty.Metrics.formatSocketAddress(localAddress);
		assertThat(ContextAwareServerRecorder.INSTANCE.onServerConnectionsAmount.get() >= currentTotalConnections + 1).isTrue();
		assertThat(ContextAwareServerRecorder.INSTANCE.onServerConnectionsLocalAddr.get()).isEqualTo(address);
		assertThat(ContextAwareServerRecorder.INSTANCE.onActiveConnectionsAmount.get() >= currentActiveConnections + 1).isTrue();
		assertThat(ContextAwareServerRecorder.INSTANCE.onActiveConnectionsMethod.get()).isEqualTo(POST);
		assertThat(ContextAwareServerRecorder.INSTANCE.onActiveConnectionsUri.get()).isEqualTo(uri);
	}

	private void checkExpectationsExisting(String uri, String serverAddress, int index) {
		String[] timerTags1 = new String[] {URI, uri, METHOD, "POST", STATUS, "200"};
		String[] timerTags2 = new String[] {URI, uri, METHOD, "POST"};
		String[] summaryTags1 = new String[] {URI, uri};

		checkTimer(SERVER_RESPONSE_TIME, timerTags1, 1);
		checkTimer(SERVER_DATA_SENT_TIME, timerTags1, 1);
		checkTimer(SERVER_DATA_RECEIVED_TIME, timerTags2, 1);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags1, 1, 12);
		checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags1, 1, 12);
		checkCounter(SERVER_ERRORS, false, 0, summaryTags1);

		timerTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "POST", STATUS, "200"};
		timerTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "POST"};
		String[] timerTags3 = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "SUCCESS"};
		summaryTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, "http"};

		checkTimer(CLIENT_RESPONSE_TIME, timerTags1, 1);
		checkTimer(CLIENT_DATA_SENT_TIME, timerTags2, 1);
		checkTimer(CLIENT_DATA_RECEIVED_TIME, timerTags1, 1);
		checkTimer(CLIENT_CONNECT_TIME, timerTags3, index);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags3, index);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags1, 1, 12);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags1, 1, 12);
		checkCounter(CLIENT_ERRORS, false, 0, summaryTags1);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags2, 14 * index, 151 * index);
		//checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags2, true, 3*index, 84*index);
		checkCounter(CLIENT_ERRORS, false, 0, summaryTags2);
	}

	private void checkExpectationsNonExisting(String serverAddress, int index) {
		String uri = "/3";
		String[] timerTags1 = new String[] {URI, uri, METHOD, "GET", STATUS, "404"};
		String[] timerTags2 = new String[] {URI, uri, METHOD, "GET"};
		String[] summaryTags1 = new String[] {URI, uri};

		checkTimer(SERVER_RESPONSE_TIME, timerTags1, index);
		checkTimer(SERVER_DATA_SENT_TIME, timerTags1, index);
		checkTimer(SERVER_DATA_RECEIVED_TIME, timerTags2, index);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags1, index, 0);
		checkCounter(SERVER_ERRORS, false, 0, summaryTags1);

		timerTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "GET", STATUS, "404"};
		timerTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "GET"};
		String[] timerTags3 = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "SUCCESS"};
		summaryTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, "http"};

		checkTimer(CLIENT_RESPONSE_TIME, timerTags1, index);
		checkTimer(CLIENT_DATA_SENT_TIME, timerTags2, index);
		checkTimer(CLIENT_DATA_RECEIVED_TIME, timerTags1, index);
		checkTimer(CLIENT_CONNECT_TIME, timerTags3, index);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags3, index);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags1, index, 0);
		checkCounter(CLIENT_ERRORS, false, 0, summaryTags1);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags2, index, 123 * index);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags2, index, 64 * index);
		checkCounter(CLIENT_ERRORS, false, 0, summaryTags2);
	}


	protected HttpServer customizeServerOptions(HttpServer httpServer) {
		return httpServer;
	}

	protected HttpClient customizeClientOptions(HttpClient httpClient) {
		return httpClient;
	}

	protected void checkTlsTimer(String name, String[] tags, long expectedCount) {
		//no-op
	}

	void checkTimer(String name, String[] tags, long expectedCount) {
		Timer timer = registry.find(name).tags(tags).timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(expectedCount);
		assertThat(timer.totalTime(TimeUnit.NANOSECONDS) > 0).isTrue();
	}

	private void checkDistributionSummary(String name, String[] tags, long expectedCount, double expectedAmount) {
		DistributionSummary summary = registry.find(name).tags(tags).summary();
		assertThat(summary).isNotNull();
		assertThat(summary.count()).isEqualTo(expectedCount);
		assertThat(summary.totalAmount() >= expectedAmount).isTrue();
	}

	void checkCounter(String name, boolean exists, double expectedCount, String... tags) {
		Counter counter = registry.find(name).tags(tags).counter();
		if (exists) {
			assertThat(counter).isNotNull();
			assertThat(counter.count() >= expectedCount).isTrue();
		}
		else {
			assertThat(counter).isNull();
		}
	}

	double getCounter(String name, double defaultValue, String... tags) {
		Counter counter = registry.find(name).tags(tags).counter();
		if (counter != null) {
			return counter.count();
		}
		else {
			return defaultValue;
		}
	}

	private static final String SERVER_RESPONSE_TIME = HTTP_SERVER_PREFIX + RESPONSE_TIME;
	private static final String SERVER_DATA_SENT_TIME = HTTP_SERVER_PREFIX + DATA_SENT_TIME;
	private static final String SERVER_DATA_RECEIVED_TIME = HTTP_SERVER_PREFIX + DATA_RECEIVED_TIME;
	private static final String SERVER_DATA_SENT = HTTP_SERVER_PREFIX + DATA_SENT;
	private static final String SERVER_DATA_RECEIVED = HTTP_SERVER_PREFIX + DATA_RECEIVED;
	private static final String SERVER_ERRORS = HTTP_SERVER_PREFIX + ERRORS;

	private static final String CLIENT_RESPONSE_TIME = HTTP_CLIENT_PREFIX + RESPONSE_TIME;
	private static final String CLIENT_DATA_SENT_TIME = HTTP_CLIENT_PREFIX + DATA_SENT_TIME;
	private static final String CLIENT_DATA_RECEIVED_TIME = HTTP_CLIENT_PREFIX + DATA_RECEIVED_TIME;
	private static final String CLIENT_DATA_SENT = HTTP_CLIENT_PREFIX + DATA_SENT;
	private static final String CLIENT_DATA_RECEIVED = HTTP_CLIENT_PREFIX + DATA_RECEIVED;
	static final String CLIENT_ERRORS = HTTP_CLIENT_PREFIX + ERRORS;
	private static final String CLIENT_CONNECT_TIME = HTTP_CLIENT_PREFIX + CONNECT_TIME;
	private static final String CLIENT_TLS_HANDSHAKE_TIME = HTTP_CLIENT_PREFIX + TLS_HANDSHAKE_TIME;

	static final class ContextAwareRecorder extends ContextAwareHttpClientMetricsRecorder {

		static final ContextAwareRecorder INSTANCE = new ContextAwareRecorder();

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

	static final class ServerRecorder implements HttpServerMetricsRecorder {

		static final ServerRecorder INSTANCE = new ServerRecorder();
		private final AtomicInteger onServerConnectionsAmount = new AtomicInteger();
		private final AtomicReference<String> onServerConnectionsLocalAddr = new AtomicReference<>();
		private final AtomicReference<String> onActiveConnectionsUri = new AtomicReference<>();
		private final AtomicReference<String> onActiveConnectionsMethod = new AtomicReference<>();
		private final AtomicInteger onActiveConnectionsAmount = new AtomicInteger();

		void reset() {
			onServerConnectionsAmount.set(0);
			onServerConnectionsLocalAddr.set(null);
			onActiveConnectionsUri.set(null);
			onActiveConnectionsMethod.set(null);
			onActiveConnectionsAmount.set(0);
		}

		@Override
		public void incrementServerConnections(SocketAddress localAddress, int amount) {
			onServerConnectionsLocalAddr.set(reactor.netty.Metrics.formatSocketAddress(localAddress));
			onServerConnectionsAmount.addAndGet(amount);
		}

		@Override
		public void incrementActiveConnections(String uri, String method, int amount) {
			onActiveConnectionsUri.set(uri);
			onActiveConnectionsMethod.set(method);
			onActiveConnectionsAmount.addAndGet(amount);
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

	static final class ContextAwareServerRecorder extends ContextAwareHttpServerMetricsRecorder {

		static final ContextAwareServerRecorder INSTANCE = new ContextAwareServerRecorder();

		private final AtomicInteger onServerConnectionsAmount = new AtomicInteger();
		private final AtomicReference<String>  onServerConnectionsLocalAddr = new AtomicReference<>();
		private final AtomicBoolean onServerConnectionsContextView = new AtomicBoolean();

		private final AtomicReference<String> onActiveConnectionsUri = new AtomicReference<>();
		private final AtomicReference<String> onActiveConnectionsMethod = new AtomicReference<>();
		private final AtomicInteger onActiveConnectionsAmount = new AtomicInteger();
		private final AtomicBoolean onActiveConnectionsContextView = new AtomicBoolean();

		void reset() {
			onServerConnectionsAmount.set(0);
			onServerConnectionsLocalAddr.set(null);
			onServerConnectionsContextView.set(false);

			onActiveConnectionsUri.set(null);
			onActiveConnectionsMethod.set(null);
			onActiveConnectionsAmount.set(0);
			onActiveConnectionsContextView.set(false);
		}

		@Override
		public void incrementServerConnections(ContextView contextView, SocketAddress localAddress, int amount) {
			onServerConnectionsLocalAddr.set(reactor.netty.Metrics.formatSocketAddress(localAddress));
			onServerConnectionsAmount.addAndGet(amount);
			onServerConnectionsContextView.set("OK".equals(contextView.getOrDefault("testContextAwareRecorder", "KO")));
		}

		@Override
		public void incrementActiveConnections(ContextView contextView, String uri, String method, int amount) {
			onActiveConnectionsUri.set(uri);
			onActiveConnectionsMethod.set(method);
			onActiveConnectionsAmount.addAndGet(amount);
			onActiveConnectionsContextView.set("OK".equals(contextView.getOrDefault("testContextAwareRecorder", "KO")));
		}

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
		}

		@Override
		public void recordDataSentTime(ContextView contextView, String uri, String method, String status, Duration time) {
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
}
