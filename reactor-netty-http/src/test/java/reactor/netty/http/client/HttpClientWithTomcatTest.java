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
package reactor.netty.http.client;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.TomcatServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.client.HttpClientOperations.SendForm.DEFAULT_FACTORY;

/**
 * This test class verifies {@link HttpClient} against {@code Tomcat} server.
 *
 * @author Violeta Georgieva
 */
class HttpClientWithTomcatTest {
	private static TomcatServer tomcat;
	private static final int MAX_SWALLOW_SIZE = 1024 * 1024;
	private static final byte[] PAYLOAD = String.join("", Collections.nCopies(TomcatServer.PAYLOAD_MAX + MAX_SWALLOW_SIZE + (1024 * 1024), "X"))
			.getBytes(Charset.defaultCharset());

	@BeforeAll
	static void startTomcat() throws Exception {
		tomcat = new TomcatServer();
		tomcat.createDefaultContext();
		tomcat.start();
	}

	@AfterAll
	static void stopTomcat() throws Exception {
		if (tomcat != null) {
			tomcat.stop();
		}
	}

	@Test
	void nettyNetChannelAcceptsNettyChannelHandlers() throws Exception {
		HttpClient client = HttpClient.create()
		                              .port(getPort())
		                              .wiretap(true);

		final CountDownLatch latch = new CountDownLatch(1);
		String response = client.get()
		                        .uri("/?q=test%20d%20dq")
		                        .responseContent()
		                        .aggregate()
		                        .asString()
		                        .doOnSuccess(v -> latch.countDown())
		                        .block(Duration.ofSeconds(30));

		assertThat(latch.await(15, TimeUnit.SECONDS)).as("Latch didn't time out").isTrue();
		assertThat(response)
				.isNotNull()
				.contains("q=test%20d%20dq");
	}

	@Test
	void postUploadWithMultipart() throws Exception {
		Path file = Paths.get(getClass().getResource("/smallFile.txt").toURI());
		try (InputStream f = Files.newInputStream(file)) {
			doTestPostUpload((req, form) -> form.multipart(true)
							.file("test", f)
							.attr("attr1", "attr2")
							.file("test2", f),
					"test attr1 test2 ");
		}
	}

	@Test
	void postUploadNoMultipart() throws Exception {
		doTestPostUpload((req, form) -> form.multipart(false).attr("attr1", "attr2"), "attr1=attr2");
	}

	@Test
	void postUploadNoMultipartWithCustomFactory() throws Exception {
		doTestPostUpload((req, form) -> {
			DefaultHttpDataFactory customFactory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);
			form.factory(customFactory)
			    .multipart(false)
			    .attr("attr1", "attr2");
		}, "attr1=attr2");
	}

	@SuppressWarnings("unchecked")
	private void doTestPostUpload(BiConsumer<? super HttpClientRequest, HttpClientForm> formCallback,
			String expectedResponse) throws Exception {
		HttpClient client =
				HttpClient.create()
				          .host("localhost")
				          .port(getPort())
				          .wiretap(true);

		Tuple2<Integer, String> res;
		res = client.post()
		            .uri("/multipart")
		            .sendForm(formCallback)
		            .responseSingle((r, buf) -> buf.asString().map(s -> Tuples.of(r.status().code(), s)))
		            .block(Duration.ofSeconds(30));

		assertThat(res).as("response").isNotNull();
		assertThat(res.getT1()).as("status code").isEqualTo(200);
		assertThat(res.getT2()).as("response body reflecting request").contains(expectedResponse);

		Field field = DEFAULT_FACTORY.getClass().getDeclaredField("requestFileDeleteMap");
		field.setAccessible(true);
		assertThat((Map<HttpRequest, List<HttpData>>) field.get(DEFAULT_FACTORY)).isEmpty();
	}

	@Test
	void simpleTest404() {
		doSimpleTest404(HttpClient.create()
		                          .baseUrl(getURL()));
	}

	@Test
	void simpleTest404_1() {
		ConnectionProvider pool = ConnectionProvider.create("simpleTest404_1", 1);
		HttpClient client =
				HttpClient.create(pool)
				          .port(getPort())
				          .host("localhost")
				          .wiretap(true);
		doSimpleTest404(client);
		doSimpleTest404(client);
		pool.dispose();
	}

	private void doSimpleTest404(HttpClient client) {
		Integer res = client.followRedirect(true)
		                    .get()
		                    .uri("/status/404")
		                    .responseSingle((r, buf) -> Mono.just(r.status().code()))
		                    .log()
		                    .block(Duration.ofSeconds(5));

		assertThat(res).isNotNull();
		if (res != 404) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	@Test
	void disableChunkForced() {
		AtomicReference<HttpHeaders> headers = new AtomicReference<>();
		Tuple2<HttpResponseStatus, String> r =
				HttpClient.newConnection()
				          .host("localhost")
				          .port(getPort())
				          .headers(h -> h.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED))
				          .wiretap(true)
				          .doAfterRequest((req, connection) -> headers.set(req.requestHeaders()))
				          .request(HttpMethod.GET)
				          .uri("/status/400")
				          .send(ByteBufFlux.fromString(Flux.just("hello")))
				          .responseSingle((res, conn) -> Mono.just(res.status())
				                                             .zipWith(conn.asString()))
				          .block(Duration.ofSeconds(30));

		assertThat(r).isNotNull();

		assertThat(r.getT1()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
		assertThat(headers.get().get("Content-Length")).isEqualTo("5");
		assertThat(headers.get().get("Transfer-Encoding")).isNull();
	}

	@Test
	void disableChunkForced2() {
		AtomicReference<HttpHeaders> headers = new AtomicReference<>();
		Tuple2<HttpResponseStatus, String> r =
				HttpClient.newConnection()
				          .host("localhost")
				          .port(getPort())
				          .wiretap(true)
				          .doAfterRequest((req, connection) -> headers.set(req.requestHeaders()))
				          .keepAlive(false)
				          .get()
				          .uri("/status/404")
				          .responseSingle((res, conn) -> Mono.just(res.status())
				                                             .zipWith(conn.asString()))
				          .block(Duration.ofSeconds(30));

		assertThat(r).isNotNull();

		assertThat(r.getT1()).isEqualTo(HttpResponseStatus.NOT_FOUND);
		assertThat(headers.get().get("Content-Length")).isEqualTo("0");
		assertThat(headers.get().get("Transfer-Encoding")).isNull();
	}

	@Test
	void simpleClientPooling() {
		ConnectionProvider p = ConnectionProvider.create("simpleClientPooling", 1);
		AtomicReference<Channel> ch1 = new AtomicReference<>();
		AtomicReference<Channel> ch2 = new AtomicReference<>();

		HttpResponseStatus r =
				HttpClient.create(p)
				          .doOnResponse((res, c) -> ch1.set(c.channel()))
				          .wiretap(true)
				          .get()
				          .uri(getURL() + "/status/404")
				          .responseSingle((res, buf) -> buf.thenReturn(res.status()))
				          .block(Duration.ofSeconds(30));

		HttpClient.create(p)
		          .doOnResponse((res, c) -> ch2.set(c.channel()))
		          .wiretap(true)
		          .get()
		          .uri(getURL() + "/status/404")
		          .responseSingle((res, buf) -> buf.thenReturn(res.status()))
		          .block(Duration.ofSeconds(30));

		AtomicBoolean same = new AtomicBoolean();

		same.set(ch1.get() == ch2.get());

		assertThat(same.get()).isTrue();

		assertThat(r).isEqualTo(HttpResponseStatus.NOT_FOUND);
		p.dispose();
	}

	@Test
	void disableChunkImplicitDefault() {
		ConnectionProvider p = ConnectionProvider.create("disableChunkImplicitDefault", 1);
		HttpClient client =
				HttpClient.create(p)
				          .host("localhost")
				          .port(getPort())
				          .wiretap(true);

		Tuple2<HttpResponseStatus, Channel> r =
				client.get()
				      .uri("/status/404")
				      .responseConnection((res, conn) -> Mono.just(res.status())
				                                             .delayUntil(s -> conn.inbound().receive())
				                                             .zipWith(Mono.just(conn.channel())))
				      .blockLast(Duration.ofSeconds(30));

		assertThat(r).isNotNull();

		Channel r2 =
				client.get()
				      .uri("/status/404")
				      .responseConnection((res, conn) -> Mono.just(conn.channel())
				                                             .delayUntil(s -> conn.inbound().receive()))
				      .blockLast(Duration.ofSeconds(30));

		assertThat(r2).isNotNull();

		assertThat(r.getT2()).isSameAs(r2);

		assertThat(r.getT1()).isEqualTo(HttpResponseStatus.NOT_FOUND);
		p.dispose();
	}

	@Test
	void contentHeader() {
		ConnectionProvider fixed = ConnectionProvider.create("contentHeader", 1);
		HttpClient client =
				HttpClient.create(fixed)
				          .wiretap(true)
				          .headers(h -> h.add("content-length", "1"));

		HttpResponseStatus r =
				client.request(HttpMethod.GET)
				      .uri(getURL())
				      .send(ByteBufFlux.fromString(Mono.just(" ")))
				      .responseSingle((res, buf) -> Mono.just(res.status()))
				      .block(Duration.ofSeconds(30));

		client.request(HttpMethod.GET)
		      .uri(getURL())
		      .send(ByteBufFlux.fromString(Mono.just(" ")))
		      .responseSingle((res, buf) -> Mono.just(res.status()))
		      .block(Duration.ofSeconds(30));

		assertThat(r).isEqualTo(HttpResponseStatus.BAD_REQUEST);
		fixed.dispose();
	}

	@Test
	void testIssue2825() {
		int currentMaxSwallowSize = tomcat.getMaxSwallowSize();

		try {
			tomcat.setMaxSwallowSize(MAX_SWALLOW_SIZE);

			AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
			HttpClient client = HttpClient.create()
					.port(getPort())
					.wiretap(false)
					.metrics(true, ClientMetricsRecorder::reset)
					.option(ChannelOption.SO_SNDBUF, 4096)
					.doOnConnected(conn -> serverAddress.set(conn.address()));

			StepVerifier.create(client
					.headers(hdr -> hdr.set("Content-Type", "text/plain"))
					.post()
					.uri("/payload-size")
					.send(Mono.just(Unpooled.wrappedBuffer(PAYLOAD)))
					.response((r, buf) -> buf.aggregate().asString().zipWith(Mono.just(r))))
					.expectNextMatches(tuple -> TomcatServer.TOO_LARGE.equals(tuple.getT1())
							&& tuple.getT2().status().equals(HttpResponseStatus.BAD_REQUEST))
					.expectComplete()
					.verify(Duration.ofSeconds(30));

			assertThat(ClientMetricsRecorder.INSTANCE.recordDataSentTimeMethod).isEqualTo("POST");
			assertThat(ClientMetricsRecorder.INSTANCE.recordDataSentTimeTime).isNotNull();
			assertThat(ClientMetricsRecorder.INSTANCE.recordDataSentTimeTime.isZero()).isFalse();
			assertThat(ClientMetricsRecorder.INSTANCE.recordDataSentTimeUri).isEqualTo("/payload-size");
			assertThat(ClientMetricsRecorder.INSTANCE.recordDataSentTimeRemoteAddr).isEqualTo(serverAddress.get());

			assertThat(ClientMetricsRecorder.INSTANCE.recordDataSentRemoteAddr).isEqualTo(serverAddress.get());
			assertThat(ClientMetricsRecorder.INSTANCE.recordDataSentUri).isEqualTo("/payload-size");
			assertThat(ClientMetricsRecorder.INSTANCE.recordDataSentBytes).isEqualTo(PAYLOAD.length);
		}

		finally {
			tomcat.setMaxSwallowSize(currentMaxSwallowSize);
		}
	}

	private int getPort() {
		return tomcat.port();
	}

	private String getURL() {
		return "http://localhost:" + tomcat.port();
	}

	/**
	 * This Custom metrics recorder checks that the {@link AbstractHttpClientMetricsHandler#recordWrite(SocketAddress)} is properly invoked by
	 * (see {@link AbstractHttpClientMetricsHandler#channelRead(ChannelHandlerContext, Object)}) when
	 * an early response is received while the corresponding request it still being written.
	 */
	static final class ClientMetricsRecorder implements HttpClientMetricsRecorder {

		static final ClientMetricsRecorder INSTANCE = new ClientMetricsRecorder();
		volatile SocketAddress recordDataSentTimeRemoteAddr;
		volatile String recordDataSentTimeUri;
		volatile String recordDataSentTimeMethod;
		volatile Duration recordDataSentTimeTime;
		volatile SocketAddress recordDataSentRemoteAddr;
		volatile String recordDataSentUri;
		volatile long recordDataSentBytes;

		static ClientMetricsRecorder reset() {
			INSTANCE.recordDataSentTimeRemoteAddr = null;
			INSTANCE.recordDataSentTimeUri = null;
			INSTANCE.recordDataSentTimeMethod = null;
			INSTANCE.recordDataSentTimeTime = null;
			INSTANCE.recordDataSentRemoteAddr = null;
			INSTANCE.recordDataSentUri = null;
			INSTANCE.recordDataSentBytes = -1;
			return INSTANCE;
		}

		@Override
		public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		}

		@Override
		public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		}

		@Override
		public void incrementErrorsCount(SocketAddress remoteAddress) {
		}

		@Override
		public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		}

		@Override
		public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		}

		@Override
		public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		}

		@Override
		public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		}

		@Override
		public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
			this.recordDataSentRemoteAddr = remoteAddress;
			this.recordDataSentUri = uri;
			this.recordDataSentBytes = bytes;
		}

		@Override
		public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		}

		@Override
		public void recordDataReceivedTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		}

		@Override
		public void recordDataSentTime(SocketAddress remoteAddress, String uri, String method, Duration time) {
			this.recordDataSentTimeRemoteAddr = remoteAddress;
			this.recordDataSentTimeUri = uri;
			this.recordDataSentTimeMethod = method;
			this.recordDataSentTimeTime = time;
		}

		@Override
		public void recordResponseTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		}
	}
}
