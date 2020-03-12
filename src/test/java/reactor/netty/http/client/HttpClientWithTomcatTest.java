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
package reactor.netty.http.client;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.TomcatServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Violeta Georgieva
 */
public class HttpClientWithTomcatTest {
	private static TomcatServer tomcat;

	@BeforeClass
	public static void startTomcat() throws Exception {
		tomcat = new TomcatServer();
		tomcat.createDefaultContext();
		tomcat.start();
	}

	@AfterClass
	public static void stopTomcat() throws Exception {
		if (tomcat != null) {
			tomcat.stop();
		}
	}

	@Test
	public void nettyNetChannelAcceptsNettyChannelHandlers() throws Exception {
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

		assertTrue("Latch didn't time out", latch.await(15, TimeUnit.SECONDS));
		assertNotNull(response);
		assertTrue(response.contains("q=test%20d%20dq"));
	}

	@Test
	public void postUpload() throws Exception {
		HttpClient client =
				HttpClient.create()
				          .tcpConfiguration(tcpClient -> tcpClient.host("localhost"))
				          .port(getPort())
				          .wiretap(true);

		Tuple2<Integer, String> res;
		Path file = Paths.get(getClass().getResource("/smallFile.txt").toURI());
		try (InputStream f = Files.newInputStream(file)) {
			res = client.post()
			            .uri("/multipart")
			            .sendForm((req, form) -> form.multipart(true)
			                                         .file("test", f)
			                                         .attr("attr1", "attr2")
			                                         .file("test2", f))
			            .responseSingle((r, buf) -> buf.asString().map(s -> Tuples.of(r.status().code(), s)))
			            .block(Duration.ofSeconds(30));
		}

		assertThat(res).as("response").isNotNull();
		assertThat(res.getT1()).as("status code").isEqualTo(200);
		assertThat(res.getT2()).as("response body reflecting request").contains("test attr1 test2 ");
	}

	@Test
	public void simpleTest404() {
		doSimpleTest404(HttpClient.create()
		                          .baseUrl(getURL()));
	}

	@Test
	public void simpleTest404_1() {
		ConnectionProvider pool = ConnectionProvider.create("simpleTest404_1", 1);
		HttpClient client =
				HttpClient.create(pool)
				          .port(getPort())
				          .tcpConfiguration(tcpClient -> tcpClient.host("localhost"))
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
		                    .block();

		assertThat(res).isNotNull();
		if (res != 404) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	@Test
	public void disableChunkForced() {
		AtomicReference<HttpHeaders> headers = new AtomicReference<>();
		Tuple2<HttpResponseStatus, String> r =
				HttpClient.newConnection()
				          .tcpConfiguration(tcpClient -> tcpClient.host("localhost"))
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
	public void disableChunkForced2() {
		AtomicReference<HttpHeaders> headers = new AtomicReference<>();
		Tuple2<HttpResponseStatus, String> r =
				HttpClient.newConnection()
				          .tcpConfiguration(tcpClient -> tcpClient.host("localhost"))
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
	public void simpleClientPooling() {
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

		Assert.assertTrue(same.get());

		Assert.assertEquals(r, HttpResponseStatus.NOT_FOUND);
		p.dispose();
	}

	@Test
	public void disableChunkImplicitDefault() {
		ConnectionProvider p = ConnectionProvider.create("disableChunkImplicitDefault", 1);
		HttpClient client =
				HttpClient.create(p)
				          .tcpConfiguration(tcpClient -> tcpClient.host("localhost"))
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

		Assert.assertSame(r.getT2(), r2);

		Assert.assertEquals(r.getT1(), HttpResponseStatus.NOT_FOUND);
		p.dispose();
	}

	@Test
	public void contentHeader() {
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

		Assert.assertEquals(r, HttpResponseStatus.BAD_REQUEST);
		fixed.dispose();
	}

	private int getPort() {
		return tomcat.port();
	}

	private String getURL() {
		return "http://localhost:" + tomcat.port();
	}
}
