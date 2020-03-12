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

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.DefaultEventExecutor;
import org.junit.After;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.ByteBufMono;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;
import reactor.netty.SocketUtils;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpServer;
import reactor.test.StepVerifier;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 * @since 0.6
 */
public class HttpClientTest {

	static final Logger log = Loggers.getLogger(HttpClientTest.class);

	private DisposableServer disposableServer;

	@After
	public void tearDown() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
	}

	@Test
	public void abort() {
		disposableServer =
				TcpServer.create()
				         .port(0)
				         .handle((in, out) ->
				                 in.receive()
				                   .take(1)
				                   .thenMany(Flux.defer(() ->
				                           out.withConnection(c ->
				                                   c.addHandlerFirst(new HttpResponseEncoder()))
				                              .sendObject(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				                                                                      HttpResponseStatus.ACCEPTED))
				                              .then(Mono.delay(Duration.ofSeconds(2)).then()))))
				         .wiretap(true)
				         .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("abort", 1);

		HttpClient client = createHttpClientForContextWithPort(pool);

		client.get()
		      .uri("/")
		      .responseSingle((r, buf) -> Mono.just(r.status().code()))
		      .log()
		      .block(Duration.ofSeconds(30));

		client.get()
		      .uri("/")
		      .responseContent()
		      .log()
		      .blockLast(Duration.ofSeconds(30));

		client.get()
		      .uri("/")
		      .responseContent()
		      .log()
		      .blockLast(Duration.ofSeconds(30));

		pool.dispose();
	}

	private DefaultFullHttpResponse response() {
		DefaultFullHttpResponse r =
				new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				                            HttpResponseStatus.ACCEPTED);
		r.headers()
		 .set(HttpHeaderNames.CONTENT_LENGTH, 0);
		return r;
	}

	/** This ensures that non-default values for the HTTP request line are visible for parsing. */
	@Test
	public void postVisibleToOnRequest() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .route(r -> r.post("/foo", (in, out) -> out.sendString(Flux.just("bar"))))
				          .bindNow();

		final AtomicReference<HttpMethod> method = new AtomicReference<>();
		final AtomicReference<String> path = new AtomicReference<>();

		final HttpClientResponse response =
				createHttpClientForContextWithPort()
				        .doOnRequest((req, con) -> {
				            method.set(req.method());
				            path.set(req.path());
				        })
				        .post()
				        .uri("/foo")
				        .send(ByteBufFlux.fromString(Mono.just("bar")))
				        .response()
				        .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
		assertThat(method.get()).isEqualTo(HttpMethod.POST);
		// req.path() returns the decoded path, without a leading "/"
		assertThat(path.get()).isEqualTo("foo");
	}

	@Test
	public void userIssue() throws Exception {
		final ConnectionProvider pool = ConnectionProvider.create("userIssue", 1);
		CountDownLatch latch = new CountDownLatch(3);
		Set<String> localAddresses = ConcurrentHashMap.newKeySet();
		disposableServer =
				HttpServer.create()
				          .port(8080)
				          .route(r -> r.post("/",
				                  (req, resp) -> req.receive()
				                                    .asString()
				                                    .flatMap(data -> {
				                                        latch.countDown();
				                                        return resp.status(200)
				                                                   .send();
				                                    })))
				          .wiretap(true)
				          .bindNow();

		final HttpClient client = createHttpClientForContextWithAddress(pool);

		Flux.just("1", "2", "3")
		    .concatMap(data ->
		            client.doOnResponse((res, conn) ->
		                    localAddresses.add(conn.channel()
		                                           .localAddress()
		                                           .toString()))
		                  .post()
		                  .uri("/")
		                  .send(ByteBufFlux.fromString(Flux.just(data)))
		                  .responseContent())
		    .subscribe();


		latch.await();
		pool.dispose();
		System.out.println("Local Addresses used: " + localAddresses);
	}

	@Test
	@Ignore
	public void pipelined() {
		disposableServer =
				TcpServer.create()
				         .host("localhost")
				         .port(0)
				         .handle((in, out) ->
				                 out.withConnection(c -> c.addHandlerFirst(new HttpResponseEncoder()))
				                    .sendObject(Flux.just(response(), response()))
				                    .neverComplete())
				         .wiretap(true)
				         .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("pipelined", 1);

		HttpClient client = createHttpClientForContextWithAddress(pool);

		client.get()
		      .uri("/")
		      .responseSingle((r, buf) -> buf.thenReturn(r.status().code()))
		      .log()
		      .block(Duration.ofSeconds(30));

		try {
			client.get()
			      .uri("/")
			      .responseContent()
			      .blockLast(Duration.ofSeconds(30));
		}
		catch (AbortedException ae) {
			return;
		}

		pool.dispose();
		Assert.fail("Not aborted");
	}

	@Test
	public void testClientReuseIssue405(){
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((in,out)->out.sendString(Flux.just("hello")))
				          .wiretap(true)
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("testClientReuseIssue405", 1);
		HttpClient httpClient = createHttpClientForContextWithPort(pool);

		Mono<String> mono1 =
				httpClient.get()
				          .responseSingle((r, buf) -> buf.asString())
				          .log("mono1");

		Mono<String> mono2 =
				httpClient.get()
				          .responseSingle((r, buf) -> buf.asString())
				          .log("mono1");

		StepVerifier.create(Flux.zip(mono1,mono2))
		            .expectNext(Tuples.of("hello","hello"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(20));

		pool.dispose();
	}

	@Test
	public void backpressured() throws Exception {
		Path resource = Paths.get(getClass().getResource("/public").toURI());
		disposableServer = HttpServer.create()
		                             .port(0)
		                             .route(routes -> routes.directory("/test", resource))
		                             .wiretap(true)
		                             .bindNow();

		ByteBufFlux remote =
				createHttpClientForContextWithPort()
				        .get()
				        .uri("/test/test.css")
				        .responseContent();

		Mono<String> page = remote.asString()
		                          .limitRate(1)
		                          .reduce(String::concat);

		Mono<String> cancelledPage = remote.asString()
		                                   .take(5)
		                                   .limitRate(1)
		                                   .reduce(String::concat);

		page.block(Duration.ofSeconds(30));
		cancelledPage.block(Duration.ofSeconds(30));
		page.block(Duration.ofSeconds(30));
	}

	@Test
	public void serverInfiniteClientClose() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, resp) -> {
				          	req.withConnection(cn -> cn.onDispose(latch::countDown));

				                  return Flux.interval(Duration.ofSeconds(1))
				                             .flatMap(d -> resp.sendObject(Unpooled.EMPTY_BUFFER));
				          })
				          .wiretap(true)
				          .bindNow();

		createHttpClientForContextWithPort()
		        .get()
		        .uri("/")
		        .response()
		        .block();

		latch.await();
	}

	@Test
	public void simpleTestHttps() {
		StepVerifier.create(HttpClient.create()
		                              .wiretap(true)
		                              .get()
		                              .uri("https://example.com")
		                              .response((r, buf) -> Mono.just(r.status().code())))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify();

		StepVerifier.create(HttpClient.create()
		                              .wiretap(true)
		                              .get()
		                              .uri("https://example.com")
		                              .response((r, buf) -> Mono.just(r.status().code())))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify();
	}

	@Test
	public void prematureCancel() {
		DirectProcessor<Void> signal = DirectProcessor.create();
		disposableServer =
				TcpServer.create()
				         .host("localhost")
				         .port(0)
				         .handle((in, out) -> {
				             signal.onComplete();
				             return out.withConnection(c -> c.addHandlerFirst(new HttpResponseEncoder()))
				                       .sendObject(Mono.delay(Duration.ofSeconds(2))
				                                       .map(t -> new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				                                                                             HttpResponseStatus.PROCESSING)))
				                       .neverComplete();
				         })
				         .wiretap(true)
				         .bindNow(Duration.ofSeconds(30));

		StepVerifier.create(
				createHttpClientForContextWithAddress()
				        .get()
				        .uri("/")
				        .responseContent()
				        .timeout(signal))
				    .verifyError(TimeoutException.class);
	}

	@Test
	public void gzip() {
		String content = "HELLO WORLD";

		disposableServer =
				HttpServer.create()
				          .compress(true)
				          .port(0)
				          .handle((req, res) -> res.sendString(Mono.just(content)))
				          .bindNow();

		//verify gzip is negotiated (when no decoder)
		StepVerifier.create(
				createHttpClientForContextWithPort()
				        .headers(h -> h.add("Accept-Encoding", "gzip")
				                       .add("Accept-Encoding", "deflate"))
				        .followRedirect(true)
				        .get()
				        .response((r, buf) -> buf.aggregate()
				                                 .asString()
				                                 .zipWith(Mono.just(r.responseHeaders()
				                                                     .get("Content-Encoding", "")))
				                                 .zipWith(Mono.just(r))))
				    .expectNextMatches(tuple -> {
				            String content1 = tuple.getT1().getT1();
				            return !content1.equals(content)
				                   && "gzip".equals(tuple.getT1().getT2());
				    })
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		//verify decoder does its job and removes the header
		StepVerifier.create(
				createHttpClientForContextWithPort()
				        .followRedirect(true)
				        .headers(h -> h.add("Accept-Encoding", "gzip")
				                       .add("Accept-Encoding", "deflate"))
				        .doOnRequest((req, conn) ->
				                conn.addHandlerFirst("gzipDecompressor", new HttpContentDecompressor()))
				        .get()
				        .response((r, buf) -> buf.aggregate()
				                                 .asString()
				                                 .zipWith(Mono.just(r.responseHeaders()
				                                                     .get("Content-Encoding", "")))
				                                 .zipWith(Mono.just(r))))
				    .expectNextMatches(tuple -> {
				            String content1 = tuple.getT1().getT1();
				            return content1.equals(content)
				                   && "".equals(tuple.getT1().getT2());
				    })
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void gzipEnabled() {
		doTestGzip(true);
	}

	@Test
	public void gzipDisabled() {
		doTestGzip(false);
	}

	private void doTestGzip(boolean gzipEnabled) {
		String expectedResponse = gzipEnabled ? "gzip" : "no gzip";
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req,res) -> res.sendString(Mono.just(req.requestHeaders()
				                                                           .get(HttpHeaderNames.ACCEPT_ENCODING,
				                                                                "no gzip"))))
				          .wiretap(true)
				          .bindNow();
		HttpClient client = createHttpClientForContextWithPort();

		if (gzipEnabled){
			client = client.compress(true);
		}

		StepVerifier.create(client.get()
		                          .uri("/")
		                          .response((r, buf) -> buf.asString()
		                                                   .elementAt(0)
		                                                   .zipWith(Mono.just(r))))
		            .expectNextMatches(tuple -> expectedResponse.equals(tuple.getT1()))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testUserAgent() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, resp) -> {
				                  Assert.assertTrue("" + req.requestHeaders()
				                                            .get(HttpHeaderNames.USER_AGENT),
				                                   req.requestHeaders()
				                                       .contains(HttpHeaderNames.USER_AGENT) &&
				                                   req.requestHeaders()
				                                      .get(HttpHeaderNames.USER_AGENT)
				                                      .equals(HttpClient.USER_AGENT));

				                  return req.receive().then();
				          })
				          .wiretap(true)
				          .bindNow();

		createHttpClientForContextWithPort()
		        .get()
		        .uri("/")
		        .responseContent()
		        .blockLast();
	}

	@Test
	public void gettingOptionsDuplicates() {
		HttpClient client = HttpClient.create()
		                              .tcpConfiguration(tcpClient -> tcpClient.host("example.com"))
		                              .wiretap(true)
		                              .port(123)
		                              .compress(true);
		assertThat(client.tcpConfiguration())
		        .isNotSameAs(HttpClient.DEFAULT_TCP_CLIENT)
		        .isNotSameAs(client.tcpConfiguration());
	}

	@Test
	public void sslExchangeRelativeGet() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                        .build();

		disposableServer =
				HttpServer.create()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .handle((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .wiretap(true)
				          .bindNow();


		String responseString =
				createHttpClientForContextWithAddress()
				          .secure(ssl -> ssl.sslContext(sslClient))
				          .get()
				          .uri("/foo")
				          .responseSingle((res, buf) -> buf.asString(CharsetUtil.UTF_8))
				          .block(Duration.ofMillis(200));

		assertThat(responseString).isEqualTo("hello /foo");
	}

	@Test
	public void sslExchangeAbsoluteGet() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();

		disposableServer =
				HttpServer.create()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .handle((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .wiretap(true)
				          .bindNow();

		String responseString = createHttpClientForContextWithAddress()
		                                .secure(ssl -> ssl.sslContext(sslClient))
		                                .get()
		                                .uri("/foo")
		                                .responseSingle((res, buf) -> buf.asString(CharsetUtil.UTF_8))
		                                .block();

		assertThat(responseString).isEqualTo("hello /foo");
	}

	@Test
	public void secureSendFile()
			throws CertificateException, SSLException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		AtomicReference<String> uploaded = new AtomicReference<>();

		disposableServer =
				HttpServer.create()
				          .port(9090)
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .route(r -> r.post("/upload", (req, resp) ->
				                  req.receive()
				                     .aggregate()
				                     .asString(StandardCharsets.UTF_8)
				                     .log()
				                     .doOnNext(uploaded::set)
				                     .then(resp.status(201).sendString(Mono.just("Received File")).then())))
				          .wiretap(true)
				          .bindNow();

		Tuple2<String, Integer> response =
				createHttpClientForContextWithAddress()
				        .secure(ssl -> ssl.sslContext(sslClient))
				        .post()
				        .uri("/upload")
				        .send((r, out) -> out.sendFile(largeFile))
				        .responseSingle((res, buf) -> buf.asString()
				                                         .zipWith(Mono.just(res.status().code())))
				        .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.getT2()).isEqualTo(201);
		assertThat(response.getT1()).isEqualTo("Received File");

		assertThat(uploaded.get())
		                   .startsWith("This is an UTF-8 file that is larger than 1024 bytes. " +
		                           "It contains accents like é.")
		                   .contains("1024 mark here -><- 1024 mark here")
		                   .endsWith("End of File");
	}

	@Test
	public void chunkedSendFile() throws URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		AtomicReference<String> uploaded = new AtomicReference<>();

		disposableServer =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.post("/upload", (req, resp) ->
				                  req.receive()
				                    .aggregate()
				                    .asString(StandardCharsets.UTF_8)
				                    .doOnNext(uploaded::set)
				                    .then(resp.status(201)
				                              .sendString(Mono.just("Received File"))
				                              .then())))
				          .wiretap(true)
				          .bindNow();

		Tuple2<String, Integer> response =
				createHttpClientForContextWithAddress()
				        .post()
				        .uri("/upload")
				        .send((r, out) -> out.sendFile(largeFile))
				        .responseSingle((res, buf) -> buf.asString()
				                                         .zipWith(Mono.just(res.status().code())))
				        .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.getT2()).isEqualTo(201);
		assertThat(response.getT1()).isEqualTo("Received File");

		assertThat(uploaded.get())
		                   .startsWith("This is an UTF-8 file that is larger than 1024 bytes. " +
		                           "It contains accents like é.")
		                   .contains("1024 mark here -><- 1024 mark here")
		                   .endsWith("End of File");
	}

	@Test
	public void test() {
		disposableServer =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.put("/201", (req, res) -> res.addHeader("Content-Length", "0")
				                                                     .status(HttpResponseStatus.CREATED)
				                                                     .sendHeaders())
				                       .put("/204", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
				                                                     .sendHeaders())
				                       .get("/200", (req, res) -> res.addHeader("Content-Length", "0")
				                                                     .sendHeaders()))
				          .bindNow();

		AtomicInteger onReq = new AtomicInteger();
		AtomicInteger afterReq = new AtomicInteger();
		AtomicInteger onResp = new AtomicInteger();
		AtomicInteger afterResp = new AtomicInteger();
		createHttpClientForContextWithAddress()
		        .doOnRequest((r, c) -> onReq.getAndIncrement())
		        .doAfterRequest((r, c) -> afterReq.getAndIncrement())
		        .doOnResponse((r, c) -> onResp.getAndIncrement())
		        .doAfterResponseSuccess((r, c) -> afterResp.getAndIncrement())
		        .put()
		        .uri("/201")
		        .responseContent()
		        .blockLast();

		createHttpClientForContextWithAddress()
		        .doOnRequest((r, c) -> onReq.getAndIncrement())
		        .doAfterRequest((r, c) -> afterReq.getAndIncrement())
		        .doOnResponse((r, c) -> onResp.getAndIncrement())
		        .doAfterResponseSuccess((r, c) -> afterResp.getAndIncrement())
		        .put()
		        .uri("/204")
		        .responseContent()
		        .blockLast(Duration.ofSeconds(30));

		createHttpClientForContextWithAddress()
		        .doOnRequest((r, c) -> onReq.getAndIncrement())
		        .doAfterRequest((r, c) -> afterReq.getAndIncrement())
		        .doOnResponse((r, c) -> onResp.getAndIncrement())
		        .doAfterResponseSuccess((r, c) -> afterResp.getAndIncrement())
		        .get()
		        .uri("/200")
		        .responseContent()
		        .blockLast(Duration.ofSeconds(30));

		assertThat(onReq.get()).isEqualTo(3);
		assertThat(afterReq.get()).isEqualTo(3);
		assertThat(onResp.get()).isEqualTo(3);
		assertThat(afterResp.get()).isEqualTo(3);
	}

	@Test
	public void testDeferredUri() {
		disposableServer =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.get("/201", (req, res) -> res.addHeader
				                  ("Content-Length", "0")
				                                                     .status(HttpResponseStatus.CREATED)
				                                                     .sendHeaders())
				                       .get("/204", (req, res) -> res.status
				                               (HttpResponseStatus.NO_CONTENT)
				                                                     .sendHeaders())
				                       .get("/200", (req, res) -> res.addHeader("Content-Length", "0")
				                                                     .sendHeaders()))
				          .bindNow();

		AtomicInteger i = new AtomicInteger();
		createHttpClientForContextWithAddress()
		        .observe((c, s) -> log.info(s + "" + c))
		        .get()
		        .uri(Mono.fromCallable(() -> {
		            switch (i.incrementAndGet()) {
		                case 1: return "/201";
		                case 2: return "/204";
		                case 3: return "/200";
		                default: return null;
		            }
		        }))
		        .responseContent()
		        .repeat(4)
		        .blockLast();
	}

	@Test
	public void testDeferredHeader() {
		disposableServer =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.get("/201", (req, res) -> res.addHeader
				                  ("Content-Length", "0")
				                                                     .status(HttpResponseStatus.CREATED)
				                                                     .sendHeaders()))
				          .bindNow();

		createHttpClientForContextWithAddress()
		        .headersWhen(h -> Mono.just(h.set("test", "test")).delayElement(Duration.ofSeconds(2)))
		        .observe((c, s) -> log.debug(s + "" + c))
		        .get()
		        .uri("/201")
		        .responseContent()
		        .repeat(4)
		        .blockLast();
	}

	@Test
	public void testCookie() {
		disposableServer =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.get("/201",
				                  (req, res) -> res.addHeader("test",
				                                              req.cookies()
				                                                 .get("test")
				                                                 .stream()
				                                                 .findFirst()
				                                                 .get()
				                                                 .value())
				                                   .status(HttpResponseStatus.CREATED)
				                                   .sendHeaders()))
				          .bindNow();

		createHttpClientForContextWithAddress()
		        .cookie("test", c -> c.setValue("lol"))
		        .get()
		        .uri("/201")
		        .responseContent()
		        .blockLast();
	}

	@Test
	public void closePool() {
		ConnectionProvider pr = ConnectionProvider.create("closePool", 1);
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((in, out) ->  out.sendString(Mono.just("test")
				                                                   .delayElement(Duration.ofMillis(100))
				                                                   .repeat()))
				          .wiretap(true)
				          .bindNow();

		Flux<String> ws = createHttpClientForContextWithPort(pr)
		                          .get()
		                          .uri("/")
		                          .responseContent()
		                          .asString();

		List<String> expected =
				Flux.range(1, 20)
				    .map(v -> "test")
				    .collectList()
				    .block();
		Assert.assertNotNull(expected);

		StepVerifier.create(
				Flux.range(1, 10)
				    .concatMap(i -> ws.take(2)
				                      .log()))
				    .expectNextSequence(expected)
				    .expectComplete()
				    .verify();

		pr.dispose();
	}

	@Test
	public void testIssue303() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, resp) -> resp.sendString(Mono.just("OK")))
				          .wiretap(true)
				          .bindNow();

		Mono<String> content =
				createHttpClientForContextWithPort()
				        .request(HttpMethod.GET)
				        .uri("/")
				        .send(ByteBufFlux.fromInbound(Mono.defer(() -> Mono.just("Hello".getBytes(Charset.defaultCharset())))))
				        .responseContent()
				        .aggregate()
				        .asString();

		StepVerifier.create(content)
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	private HttpClient createHttpClientForContextWithAddress() {
		return createHttpClientForContextWithAddress(null);
	}

	private HttpClient createHttpClientForContextWithAddress(ConnectionProvider pool) {
		HttpClient client;
		if (pool == null) {
			client = HttpClient.create();
		}
		else {
			client = HttpClient.create(pool);
		}
		return client.addressSupplier(disposableServer::address)
		             .wiretap(true);
	}

	private HttpClient createHttpClientForContextWithPort() {
		return createHttpClientForContextWithPort(null);
	}

	private HttpClient createHttpClientForContextWithPort(ConnectionProvider pool) {
		HttpClient client;
		if (pool == null) {
			client = HttpClient.create();
		}
		else {
			client = HttpClient.create(pool);
		}
		return client.port(disposableServer.port())
		             .wiretap(true);
	}

	@Test
	public void testIssue361() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> req.receive()
				                                   .aggregate()
				                                   .asString()
				          .flatMap(s -> res.sendString(Mono.just(s))
				                           .then()))
				          .bindNow();

		assertThat(disposableServer).isNotNull();

		ConnectionProvider connectionProvider = ConnectionProvider.create("testIssue361", 1);
		HttpClient client = createHttpClientForContextWithPort(connectionProvider);

		String response = client.post()
		                        .uri("/")
		                        .send(ByteBufFlux.fromString(Mono.just("test")
		                                         .then(Mono.error(new Exception("error")))))
		                        .responseContent()
		                        .aggregate()
		                        .asString()
		                        .onErrorResume(t -> Mono.just(t.getMessage()))
		                        .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("error");

		response = client.post()
		                 .uri("/")
		                 .send(ByteBufFlux.fromString(Mono.just("test")))
		                 .responseContent()
		                 .aggregate()
		                 .asString()
		                 .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("test");

		connectionProvider.dispose();
	}

	@Test
	public void testIssue473() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverSslContextBuilder =
				SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .secure(spec -> spec.sslContext(serverSslContextBuilder))
				          .bindNow();

		StepVerifier.create(
				HttpClient.create(ConnectionProvider.newConnection())
				          .secure()
				          .websocket()
				          .uri("wss://" + disposableServer.host() + ":" + disposableServer.port())
				          .handle((in, out) -> Mono.empty()))
				    .expectErrorMatches(t -> t.getCause() instanceof CertificateException)
				.verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue407_1() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .secure(spec -> spec.sslContext(
				                  SslContextBuilder.forServer(cert.certificate(), cert.privateKey())))
				          .handle((req, res) -> res.sendString(Mono.just("test")))
				          .wiretap(true)
				          .bindNow(Duration.ofSeconds(30));

		ConnectionProvider provider = ConnectionProvider.create("testIssue407_1", 1);
		HttpClient client =
				createHttpClientForContextWithAddress(provider)
				        .secure(spec -> spec.sslContext(
				                SslContextBuilder.forClient()
				                                 .trustManager(InsecureTrustManagerFactory.INSTANCE)));

		AtomicReference<Channel> ch1 = new AtomicReference<>();
		StepVerifier.create(client.tcpConfiguration(tcpClient -> tcpClient.doOnConnected(c -> ch1.set(c.channel())))
				                  .get()
				                  .uri("/1")
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch2 = new AtomicReference<>();
		StepVerifier.create(client.tcpConfiguration(tcpClient -> tcpClient.doOnConnected(c -> ch2.set(c.channel())))
				                  .post()
				                  .uri("/2")
				                  .send(ByteBufFlux.fromString(Mono.just("test")))
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch3 = new AtomicReference<>();
		StepVerifier.create(
				client.tcpConfiguration(tcpClient -> tcpClient.doOnConnected(c -> ch3.set(c.channel())))
				      .secure(spec -> spec.sslContext(
				              SslContextBuilder.forClient()
				                               .trustManager(InsecureTrustManagerFactory.INSTANCE))
				                          .defaultConfiguration(SslProvider.DefaultConfigurationType.TCP))
				      .post()
				      .uri("/3")
				      .responseContent()
				      .aggregate()
				      .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		assertThat(ch1.get()).isSameAs(ch2.get());
		assertThat(ch1.get()).isNotSameAs(ch3.get());

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue407_2() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .secure(spec -> spec.sslContext(
				                  SslContextBuilder.forServer(cert.certificate(), cert.privateKey())))
				          .handle((req, res) -> res.sendString(Mono.just("test")))
				          .wiretap(true)
				          .bindNow(Duration.ofSeconds(30));

		SslContextBuilder clientSslContextBuilder =
				SslContextBuilder.forClient()
				                 .trustManager(InsecureTrustManagerFactory.INSTANCE);
		ConnectionProvider provider = ConnectionProvider.create("testIssue407_2", 1);
		HttpClient client =
				createHttpClientForContextWithAddress(provider)
				        .tcpConfiguration(tcpClient -> tcpClient.secure(spec -> spec.sslContext(clientSslContextBuilder)));

		AtomicReference<Channel> ch1 = new AtomicReference<>();
		StepVerifier.create(client.tcpConfiguration(tcpClient -> tcpClient.doOnConnected(c -> ch1.set(c.channel())))
				                  .get()
				                  .uri("/1")
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch2 = new AtomicReference<>();
		StepVerifier.create(client.tcpConfiguration(tcpClient -> tcpClient.doOnConnected(c -> ch2.set(c.channel())))
				                  .post()
				                  .uri("/2")
				                  .send(ByteBufFlux.fromString(Mono.just("test")))
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch3 = new AtomicReference<>();
		StepVerifier.create(
				client.tcpConfiguration(tcpClient ->
				          tcpClient.doOnConnected(c -> ch3.set(c.channel()))
				                   .secure(spec -> spec.sslContext(clientSslContextBuilder)
				                                       .defaultConfiguration(SslProvider.DefaultConfigurationType.TCP)))
				      .post()
				      .uri("/3")
				      .responseContent()
				      .aggregate()
				      .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		assertThat(ch1.get()).isSameAs(ch2.get());
		assertThat(ch1.get()).isNotSameAs(ch3.get());

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}


	@Test
	public void testClientContext() throws Exception {
		doTestClientContext(HttpClient.create());
		doTestClientContext(HttpClient.create(ConnectionProvider.newConnection()));
	}

	private void doTestClientContext(HttpClient client) throws Exception {
		CountDownLatch latch = new CountDownLatch(4);

		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.send(req.receive().retain()))
				          .wiretap(true)
				          .bindNow();

		StepVerifier.create(
				client.port(disposableServer.port())
				      .doOnRequest((req, c) -> {
				          if (req.currentContext().hasKey("test")) {
				              latch.countDown();
				          }
				      })
				      .doAfterRequest((req, c) -> {
				          if (req.currentContext().hasKey("test")) {
				              latch.countDown();
				          }
				      })
				      .doOnResponse((res, c) -> {
				          if (res.currentContext().hasKey("test")) {
				              latch.countDown();
				          }
				      })
				      .doAfterResponseSuccess((req, c) -> {
				          if (req.currentContext().hasKey("test")) {
				              latch.countDown();
				          }
				      })
				      .post()
				      .send((req, out) ->
				          out.sendString(Mono.subscriberContext()
				                             .map(ctx -> ctx.getOrDefault("test", "fail"))))
				      .responseContent()
				      .asString()
				      .subscriberContext(Context.of("test", "success")))
				    .expectNext("success")
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isEqualTo(true);
	}

	@Test
	public void doOnError() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, resp) -> {
				              if (req.requestHeaders().contains("during")) {
				                  return resp.sendString(Flux.just("test").hide())
				                             .then(Mono.error(new RuntimeException("test")));
				              }
				              throw new RuntimeException("test");
				          })
				          .bindNow();

		AtomicReference<String> requestError1 = new AtomicReference<>();
		AtomicReference<String> responseError1 = new AtomicReference<>();

		Mono<String> content =
				createHttpClientForContextWithPort()
				        .headers(h -> h.add("before", "test"))
				        .doOnRequestError((req, err) ->
				            requestError1.set(req.currentContext().getOrDefault("test", "empty")))
				        .doOnResponseError((res, err) ->
				            responseError1.set(res.currentContext().getOrDefault("test", "empty")))
				        .mapConnect((c, b) -> c.subscriberContext(Context.of("test", "success")))
				        .get()
				        .uri("/")
				        .responseContent()
				        .aggregate()
				        .asString();

		StepVerifier.create(content)
		            .verifyError(PrematureCloseException.class);

		assertThat(requestError1.get()).isEqualTo("success");
		assertThat(responseError1.get()).isNull();

		AtomicReference<String> requestError2 = new AtomicReference<>();
		AtomicReference<String> responseError2 = new AtomicReference<>();

		content =
				createHttpClientForContextWithPort()
				        .headers(h -> h.add("during", "test"))
				        .doOnError((req, err) ->
				            requestError2.set(req.currentContext().getOrDefault("test", "empty"))
				            ,(res, err) ->
				            responseError2.set(res.currentContext().getOrDefault("test", "empty")))
				        .mapConnect((c, b) -> c.subscriberContext(Context.of("test", "success")))
				        .get()
				        .uri("/")
				        .responseContent()
				        .aggregate()
				        .asString();

		StepVerifier.create(content)
		            .verifyError(PrematureCloseException.class);

		assertThat(requestError2.get()).isNull();
		assertThat(responseError2.get()).isEqualTo("success");
	}

	@Test
	public void withConnector() {
		disposableServer = HttpServer.create()
		                             .port(0)
		                             .handle((req, resp) ->
		                                 resp.sendString(Mono.just(req.requestHeaders()
		                                                              .get("test"))))
		                             .bindNow();

		Mono<String> content = createHttpClientForContextWithPort()
		                               .mapConnect((c, b) -> c.subscriberContext(Context.of("test", "success")))
		                               .post()
		                               .uri("/")
		                               .send((req, out) -> {
		                                   req.requestHeaders()
		                                      .set("test",
		                                           req.currentContext()
		                                              .getOrDefault("test", "fail"));
		                                   return Mono.empty();
		                               })
		                               .responseContent()
		                               .aggregate()
		                               .asString();

		StepVerifier.create(content)
		            .expectNext("success")
		            .verifyComplete();
	}

	@Test
	public void testPreferContentLengthWhenPost() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .handle((req, res) ->
				                  res.header(HttpHeaderNames.CONTENT_LENGTH,
				                             req.requestHeaders()
				                                .get(HttpHeaderNames.CONTENT_LENGTH))
				                     .send(req.receive()
				                              .aggregate()
				                              .retain()))
				          .bindNow();

		StepVerifier.create(
				createHttpClientForContextWithAddress()
				        .headers(h -> h.add(HttpHeaderNames.CONTENT_LENGTH, 5))
				        .post()
				        .uri("/")
				        .send(Mono.just(Unpooled.wrappedBuffer("hello".getBytes(Charset.defaultCharset()))))
				        .responseContent()
				        .aggregate()
				        .asString())
				    .expectNextMatches("hello"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testExplicitEmptyBodyOnGetWorks() throws Exception {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();

		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                        .build();

		disposableServer =
				HttpServer.create()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .port(0)
				          .handle((req, res) -> res.send(req.receive().retain()))
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("testExplicitEmptyBodyOnGetWorks", 1);

		for (int i = 0; i < 4; i++) {
			StepVerifier.create(createHttpClientForContextWithAddress(pool)
			                            .secure(ssl -> ssl.sslContext(sslClient))
			                            .request(HttpMethod.GET)
			                            .uri("/")
			                            .send((req, out) -> out.send(Flux.empty()))
			                            .responseContent())
			            .expectComplete()
			            .verify(Duration.ofSeconds(30));
		}

		pool.dispose();
	}

	@Test
	public void testExplicitSendMonoErrorOnGet() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.send(req.receive().retain()))
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("test", 1);

		StepVerifier.create(
				Flux.range(0, 1000)
				    .flatMapDelayError(i ->
				        createHttpClientForContextWithAddress(pool)
				                .request(HttpMethod.GET)
				                .uri("/")
				                .send((req, out) -> out.send(Mono.error(new Exception("test"))))
				                .responseContent(), Queues.SMALL_BUFFER_SIZE, Queues.XS_BUFFER_SIZE))
				    .expectError()
				    .verify(Duration.ofSeconds(30));

		pool.dispose();
	}

	@Test
	public void testRetryNotEndlessIssue587() throws Exception {
		ExecutorService threadPool = Executors.newCachedThreadPool();
		int serverPort = SocketUtils.findAvailableTcpPort();
		ConnectionResetByPeerServer server = new ConnectionResetByPeerServer(serverPort);
		Future<?> serverFuture = threadPool.submit(server);
		if(!server.await(10, TimeUnit.SECONDS)){
			throw new IOException("fail to start test server");
		}

		StepVerifier.create(
		        HttpClient.create()
		                  .port(serverPort)
		                  .wiretap(true)
		                  .get()
		                  .uri("/")
		                  .responseContent())
		            .expectErrorMatches(t -> t.getMessage() != null &&
		                    (t.getMessage().contains("Connection reset by peer") ||
		                            t.getMessage().contains("Connection prematurely closed BEFORE response")))
		            .verify(Duration.ofSeconds(30));

		server.close();
		assertThat(serverFuture.get()).isNull();
		threadPool.shutdown();
		assertThat(threadPool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	private static final class ConnectionResetByPeerServer extends CountDownLatch implements Runnable {
		final int port;
		private final ServerSocketChannel server;
		private volatile Thread thread;

		private ConnectionResetByPeerServer(int port) {
			super(1);
			this.port = port;
			try {
				server = ServerSocketChannel.open();
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void run() {
			try {
				server.configureBlocking(true);
				server.socket()
				      .bind(new InetSocketAddress(port));
				countDown();
				thread = Thread.currentThread();
				while (true) {
					SocketChannel ch = server.accept();

					ByteBuffer buffer = ByteBuffer.allocate(1);
					int read = ch.read(buffer);
					if (read > 0) {
						buffer.flip();
					}

					ch.write(buffer);

					ch.close();
				}
			}
			catch (Exception e) {
				// Server closed
			}
		}

		public void close() throws IOException {
			Thread thread = this.thread;
			if (thread != null) {
				thread.interrupt();
			}
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

	@Test
	public void testIssue600_1() {
		doTestIssue600(true);
	}

	@Test
	public void testIssue600_2() {
		doTestIssue600(false);
	}

	private void doTestIssue600(boolean withLoop) {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.send(req.receive()
				                                            .retain()
				                                            .delaySubscription(Duration.ofSeconds(1))))
				          .wiretap(true)
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("doTestIssue600", 10);
		LoopResources loop = LoopResources.create("test", 4, true);
		HttpClient client;
		if (withLoop) {
			client = createHttpClientForContextWithAddress(pool)
			            .tcpConfiguration(tcpClient -> tcpClient.runOn(loop));
		}
		else {
			client = createHttpClientForContextWithAddress(pool);
		}

		Set<String> threadNames = new ConcurrentSkipListSet<>();
		StepVerifier.create(
				Flux.range(1,4)
				    .flatMap(i -> client.request(HttpMethod.GET)
				                        .uri("/")
				                        .send((req, out) -> out.send(Flux.empty()))
				                        .responseContent()
				                        .doFinally(s -> threadNames.add(Thread.currentThread().getName()))))
 		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		pool.dispose();
		loop.dispose();

		assertThat(threadNames.size()).isGreaterThan(1);
	}

	@Test
	public void testChannelGroupClosesAllConnections() throws Exception {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .route(r -> r.get("/never",
				                  (req, res) -> res.sendString(Mono.never()))
				              .get("/delay10",
				                  (req, res) -> res.sendString(Mono.just("test")
				                                                   .delayElement(Duration.ofSeconds(10))))
				              .get("/delay1",
				                  (req, res) -> res.sendString(Mono.just("test")
				                                                   .delayElement(Duration.ofSeconds(1)))))
				          .wiretap(true)
				          .bindNow(Duration.ofSeconds(30));

		ConnectionProvider connectionProvider =
				ConnectionProvider.create("testChannelGroupClosesAllConnections", Integer.MAX_VALUE);

		ChannelGroup group = new DefaultChannelGroup(new DefaultEventExecutor());

		CountDownLatch latch1 = new CountDownLatch(3);
		CountDownLatch latch2 = new CountDownLatch(3);

		HttpClient client = createHttpClientForContextWithAddress(connectionProvider);

		Flux.just("/never", "/delay10", "/delay1")
		    .flatMap(s ->
		            client.tcpConfiguration(
		                      tcpClient -> tcpClient.doOnConnected(c -> {
		                          c.onDispose()
		                           .subscribe(null, null, latch2::countDown);
		                          group.add(c.channel());
		                          latch1.countDown();
		                      }))
		                  .get()
		                  .uri(s)
		                  .responseContent()
		                  .aggregate()
		                  .asString())
		    .subscribe();

		assertThat(latch1.await(30, TimeUnit.SECONDS)).isTrue();

		Mono.whenDelayError(FutureMono.from(group.close()), connectionProvider.disposeLater())
		    .block(Duration.ofSeconds(30));

		assertThat(latch2.await(30, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	public void testIssue614() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .route(routes ->
				              routes.post("/dump", (req, res) -> {
				                  if (req.requestHeaders().contains("Transfer-Encoding")) {
				                      return Mono.error(new Exception("Transfer-Encoding is not expected"));
				                  }
				                  return res.sendString(Mono.just("OK"));
				              }))
				          .wiretap(true)
				          .bindNow();

		StepVerifier.create(
				createHttpClientForContextWithAddress()
				        .post()
				        .uri("/dump")
				        .sendForm((req, form) -> form.attr("attribute", "value"))
				        .responseContent()
				        .aggregate()
				        .asString())
				    .expectNext("OK")
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue632() throws Exception {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .handle((req, res) ->
				              res.header(HttpHeaderNames.CONNECTION,
				                         HttpHeaderValues.UPGRADE + ", " + HttpHeaderValues.CLOSE))
				          .bindNow();
		assertThat(disposableServer).isNotNull();

		CountDownLatch latch = new CountDownLatch(1);
		createHttpClientForContextWithPort()
		        .tcpConfiguration(tcpClient ->
		            tcpClient.doOnConnected(conn ->
		                conn.channel()
		                    .closeFuture()
		                    .addListener(future -> latch.countDown())))
		        .get()
		        .uri("/")
		        .responseContent()
		        .blockLast(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	public void testIssue694() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> {
				              req.receive()
				                 .subscribe();
				              return Mono.empty();
				          })
				          .wiretap(true)
				          .bindNow();

		HttpClient client = createHttpClientForContextWithPort();

		ByteBufAllocator alloc =ByteBufAllocator.DEFAULT;

		ByteBuf buffer1 = alloc.buffer()
		                       .writeInt(1)
		                       .retain(9);
		client.request(HttpMethod.GET)
		      .send((req, out) -> out.send(Flux.range(0, 10)
		                                       .map(i -> buffer1)))
		      .response()
		      .block(Duration.ofSeconds(30));

		assertThat(buffer1.refCnt()).isEqualTo(0);

		ByteBuf buffer2 = alloc.buffer()
		                       .writeInt(1)
		                       .retain(9);
		client.request(HttpMethod.GET)
		      .send(Flux.range(0, 10)
		                .map(i -> buffer2))
		      .response()
		      .block(Duration.ofSeconds(30));

		assertThat(buffer2.refCnt()).isEqualTo(0);
	}

	@Test
	public void testIssue700AndIssue876() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				                  res.sendString(Flux.range(0, 10)
				                                     .map(i -> "test")
				                                     .delayElements(Duration.ofMillis(4))))
				          .bindNow();

		HttpClient client = createHttpClientForContextWithAddress();
		for(int i = 0; i < 1000; ++i) {
			try {
				client.get()
				      .uri("/")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .timeout(Duration.ofMillis(ThreadLocalRandom.current().nextInt(1, 35)))
				      .block(Duration.ofMillis(100));
			}
			catch (Throwable t) {
				// ignore
			}
		}

		System.gc();
		for(int i = 0; i < 100000; ++i) {
			int[] arr = new int[100000];
		}
		System.gc();
	}

	@Test
	public void httpClientResponseConfigInjectAttributes() {
		AtomicReference<Channel> channelRef = new AtomicReference<>();
		AtomicReference<Boolean> validate = new AtomicReference<>();
		AtomicReference<Integer> chunkSize = new AtomicReference<>();

		disposableServer =
				HttpServer.create()
				          .handle((req, resp) -> req.receive()
				                                    .then(resp.sendNotFound()))
				          .wiretap(true)
				          .bindNow();

		createHttpClientForContextWithAddress()
		        .httpResponseDecoder(opt -> opt.maxInitialLineLength(123)
		                                       .maxHeaderSize(456)
		                                       .maxChunkSize(789)
		                                       .validateHeaders(false)
		                                       .initialBufferSize(10)
		                                       .failOnMissingResponse(true)
		                                       .parseHttpAfterConnectRequest(true))
		        .tcpConfiguration(tcp ->
		                tcp.doOnConnected(c -> {
		                    channelRef.set(c.channel());
		                    HttpClientCodec codec = c.channel()
		                                             .pipeline()
		                                             .get(HttpClientCodec.class);
		                    HttpObjectDecoder decoder = (HttpObjectDecoder) getValueReflection(codec, "inboundHandler", 1);
		                    chunkSize.set((Integer) getValueReflection(decoder, "maxChunkSize", 2));
		                    validate.set((Boolean) getValueReflection(decoder, "validateHeaders", 2));
		                }))
		        .post()
		        .uri("/")
		        .send(ByteBufFlux.fromString(Mono.just("bodysample")))
		        .responseContent()
		        .aggregate()
		        .asString()
		        .block(Duration.ofSeconds(30));

		assertThat(channelRef.get()).isNotNull();

		assertThat(chunkSize.get()).as("line length").isEqualTo(789);
		assertThat(validate.get()).as("validate headers").isFalse();
	}

	private Object getValueReflection(Object obj, String fieldName, int superLevel) {
		try {
			Field field;
			if (superLevel == 1) {
				field = obj.getClass()
				           .getSuperclass()
				           .getDeclaredField(fieldName);
			}
			else {
				field = obj.getClass()
				           .getSuperclass()
				           .getSuperclass()
				           .getDeclaredField(fieldName);
			}
			field.setAccessible(true);
			return field.get(obj);
		}
		catch (NoSuchFieldException | IllegalAccessException e) {
			return new RuntimeException(e);
		}
	}

	@Test
	public void testDoOnRequestInvokedBeforeSendingRequest() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.send(req.receive()
				                                            .retain()))
				          .wiretap(true)
				          .bindNow();

		StepVerifier.create(
		        createHttpClientForContextWithAddress()
		                  .doOnRequest((req, con) -> req.header("test", "test"))
		                  .post()
		                  .uri("/")
		                  .send((req, out) -> {
		                      String header = req.requestHeaders().get("test");
		                      if (header != null) {
		                          return out.sendString(Flux.just("FOUND"));
		                      }
		                      else {
		                          return out.sendString(Flux.just("NOT_FOUND"));
		                      }
		                  })
		                  .responseSingle((res, bytes) -> bytes.asString()))
		            .expectNext("FOUND")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue719() throws Exception {
		doTestIssue719(ByteBufFlux.fromString(Mono.just("test")),
				h -> h.set("Transfer-Encoding", "chunked"), false);
		doTestIssue719(ByteBufFlux.fromString(Mono.just("test")),
				h -> h.set("Content-Length", "4"), false);

		doTestIssue719(ByteBufFlux.fromString(Mono.just("")),
				h -> h.set("Transfer-Encoding", "chunked"), false);
		doTestIssue719(ByteBufFlux.fromString(Mono.just("")),
				h -> h.set("Content-Length", "0"), false);

		doTestIssue719(ByteBufFlux.fromString(Mono.just("test")),
				h -> h.set("Transfer-Encoding", "chunked"), true);
		doTestIssue719(ByteBufFlux.fromString(Mono.just("test")),
				h -> h.set("Content-Length", "4"), true);

		doTestIssue719(ByteBufFlux.fromString(Mono.just("")),
				h -> h.set("Transfer-Encoding", "chunked"), true);
		doTestIssue719(ByteBufFlux.fromString(Mono.just("")),
				h -> h.set("Content-Length", "0"), true);
	}

	private void doTestIssue719(Publisher<ByteBuf> clientSend,
			Consumer<HttpHeaders> clientSendHeaders, boolean ssl) throws Exception {
		HttpServer server =
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .handle((req, res) -> req.receive()
				                                   .then(res.sendString(Mono.just("test"))
				                                            .then()));

		if (ssl) {
			SelfSignedCertificate cert = new SelfSignedCertificate();
			server = server.secure(spec -> spec.sslContext(
					SslContextBuilder.forServer(cert.certificate(), cert.privateKey())));
		}

		disposableServer = server.bindNow();

		HttpClient client = createHttpClientForContextWithAddress();
		if (ssl) {
			client = client.secure(spec ->
					spec.sslContext(SslContextBuilder.forClient()
					                                 .trustManager(InsecureTrustManagerFactory.INSTANCE)));
		}

		StepVerifier.create(
				client.headers(clientSendHeaders)
				      .post()
				      .uri("/")
				      .send(clientSend)
				      .responseContent()
				      .aggregate()
				      .asString())
		            .expectNext("test")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(
				client.headers(clientSendHeaders)
				      .post()
				      .uri("/")
				      .send(clientSend)
				      .responseContent()
				      .aggregate()
				      .asString())
		            .expectNext("test")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue777() {
		disposableServer = HttpServer.create()
		                             .port(0)
		                             .wiretap(true)
		                             .route(r ->
		                                 r.post("/empty", (req, res) -> {
		                                     // Just consume the incoming body
		                                     req.receive().subscribe();
		                                     return res.status(400)
		                                               .header(HttpHeaderNames.CONNECTION, "close")
		                                               .send(Mono.empty());
		                                  })
		                                  .post("/test", (req, res) -> {
		                                      // Just consume the incoming body
		                                      req.receive().subscribe();
		                                      return res.status(400)
		                                                .header(HttpHeaderNames.CONNECTION, "close")
		                                                .sendString(Mono.just("Test"));
		                                  }))
		                             .bindNow();

		HttpClient client = createHttpClientForContextWithAddress();

		BiFunction<HttpClientResponse, ByteBufMono, Mono<String>> receiver =
				(resp, bytes) -> {
					if (!Objects.equals(HttpResponseStatus.OK, resp.status())) {
						return bytes.asString()
						            .switchIfEmpty(Mono.just(resp.status().reasonPhrase()))
						            .flatMap(text -> Mono.error(new RuntimeException(text)));
					}
					return bytes.asString();
				};
		doTestIssue777_1(client, "/empty", "Bad Request", receiver);
		doTestIssue777_1(client, "/test", "Test", receiver);

		receiver = (resp, bytes) -> {
			if (Objects.equals(HttpResponseStatus.OK, resp.status())) {
				return bytes.asString();
			}
			return Mono.error(new RuntimeException("error"));
		};
		doTestIssue777_1(client, "/empty", "error", receiver);
		doTestIssue777_1(client, "/test", "error", receiver);

		BiFunction<HttpClientResponse, ByteBufMono, Mono<Tuple2<String, HttpClientResponse>>> receiver1 =
				(resp, byteBuf) ->
						Mono.zip(byteBuf.asString(StandardCharsets.UTF_8)
						                .switchIfEmpty(Mono.just(resp.status().reasonPhrase())),
						         Mono.just(resp));
		doTestIssue777_2(client, "/empty", "Bad Request", receiver1);
		doTestIssue777_2(client, "/test", "Test", receiver1);

		receiver =
				(resp, bytes) -> bytes.asString(StandardCharsets.UTF_8)
				                      .switchIfEmpty(Mono.just(resp.status().reasonPhrase()))
				                      .map(respBody -> {
				                          if (!Objects.equals(HttpResponseStatus.OK, resp.status())) {
				                              throw new RuntimeException(respBody);
				                          }
				                          return respBody;
				                      });
		doTestIssue777_1(client, "/empty", "Bad Request", receiver);
		doTestIssue777_1(client, "/test", "Test", receiver);
	}

	private void doTestIssue777_1(HttpClient client, String uri, String expectation,
			BiFunction<? super HttpClientResponse, ? super ByteBufMono, ? extends Mono<String>> receiver) {
		StepVerifier.create(
		        client.post()
		              .uri(uri)
		              .send((req, out) -> out.sendString(Mono.just("Test")))
		              .responseSingle(receiver))
		            .expectErrorMessage(expectation)
		            .verify(Duration.ofSeconds(30));
	}

	private void doTestIssue777_2(HttpClient client, String uri, String expectation,
			BiFunction<? super HttpClientResponse, ? super ByteBufMono, ? extends Mono<Tuple2<String, HttpClientResponse>>> receiver) {
		StepVerifier.create(
		        client.post()
		              .uri(uri)
		              .send((req, out) -> out.sendString(Mono.just("Test")))
		              .responseSingle(receiver)
		              .map(tuple -> {
		                  if (!Objects.equals(HttpResponseStatus.OK, tuple.getT2().status())) {
		                      throw new RuntimeException(tuple.getT1());
		                  }
		                  return tuple.getT1();
		              }))
		            .expectErrorMessage(expectation)
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testConnectionIdleTimeFixedPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionIdleTimeFixedPool")
				                  .maxConnections(1)
				                  .pendingAcquireTimeout(Duration.ofMillis(100))
				                  .maxIdleTime(Duration.ofMillis(10))
				                  .build();
		ChannelId[] ids = doTestConnectionIdleTime(provider);
		assertThat(ids[0]).isNotEqualTo(ids[1]);
	}

	@Test
	public void testConnectionIdleTimeElasticPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionIdleTimeElasticPool")
				                  .maxConnections(Integer.MAX_VALUE)
				                  .maxIdleTime(Duration.ofMillis(10))
				                  .build();
		ChannelId[] ids = doTestConnectionIdleTime(provider);
		assertThat(ids[0]).isNotEqualTo(ids[1]);
	}

	@Test
	public void testConnectionNoIdleTimeFixedPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionNoIdleTimeFixedPool")
				                  .maxConnections(1)
				                  .pendingAcquireTimeout(Duration.ofMillis(100))
				                  .build();
		ChannelId[] ids = doTestConnectionIdleTime(provider);
		assertThat(ids[0]).isEqualTo(ids[1]);
	}

	@Test
	public void testConnectionNoIdleTimeElasticPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.create("testConnectionNoIdleTimeElasticPool", Integer.MAX_VALUE);
		ChannelId[] ids = doTestConnectionIdleTime(provider);
		assertThat(ids[0]).isEqualTo(ids[1]);
	}

	private ChannelId[] doTestConnectionIdleTime(ConnectionProvider provider) throws Exception {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .handle((req, res) -> res.sendString(Mono.just("hello")))
				          .bindNow();

		Flux<ChannelId> id = createHttpClientForContextWithAddress(provider)
		                       .get()
		                       .uri("/")
		                       .responseConnection((res, conn) -> Mono.just(conn.channel().id())
		                                                              .delayUntil(ch -> conn.inbound().receive()));

		ChannelId id1 = id.blockLast(Duration.ofSeconds(30));
		Thread.sleep(30);
		ChannelId id2 = id.blockLast(Duration.ofSeconds(30));

		assertThat(id1).isNotNull();
		assertThat(id2).isNotNull();

		provider.dispose();
		return new ChannelId[] {id1, id2};
	}

	@Test
	public void testConnectionLifeTimeFixedPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionLifeTimeFixedPool")
				                  .maxConnections(1)
				                  .pendingAcquireTimeout(Duration.ofMillis(100))
				                  .maxLifeTime(Duration.ofMillis(30))
				                  .build();
		ChannelId[] ids = doTestConnectionLifeTime(provider);
		assertThat(ids[0]).isNotEqualTo(ids[1]);
	}

	@Test
	public void testConnectionLifeTimeElasticPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionNoLifeTimeElasticPool")
				                  .maxConnections(Integer.MAX_VALUE)
				                  .maxLifeTime(Duration.ofMillis(30))
				                  .build();
		ChannelId[] ids = doTestConnectionLifeTime(provider);
		assertThat(ids[0]).isNotEqualTo(ids[1]);
	}

	@Test
	public void testConnectionNoLifeTimeFixedPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionNoLifeTimeFixedPool")
				                  .maxConnections(1)
				                  .pendingAcquireTimeout(Duration.ofMillis(100))
				                  .build();
		ChannelId[] ids = doTestConnectionLifeTime(provider);
		assertThat(ids[0]).isEqualTo(ids[1]);
	}

	@Test
	public void testConnectionNoLifeTimeElasticPool() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.create("testConnectionNoLifeTimeElasticPool", Integer.MAX_VALUE);
		ChannelId[] ids = doTestConnectionLifeTime(provider);
		assertThat(ids[0]).isEqualTo(ids[1]);
	}

	private ChannelId[] doTestConnectionLifeTime(ConnectionProvider provider) throws Exception {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, resp) ->
				              resp.sendObject(ByteBufFlux.fromString(Mono.delay(Duration.ofMillis(30))
				                                                         .map(Objects::toString))))
				          .wiretap(true)
				          .bindNow();

		Flux<ChannelId> id = createHttpClientForContextWithAddress(provider)
		                       .get()
		                       .uri("/")
		                       .responseConnection((res, conn) -> Mono.just(conn.channel().id())
		                                                              .delayUntil(ch -> conn.inbound().receive()));

		ChannelId id1 = id.blockLast(Duration.ofSeconds(30));
		Thread.sleep(10);
		ChannelId id2 = id.blockLast(Duration.ofSeconds(30));

		assertThat(id1).isNotNull();
		assertThat(id2).isNotNull();

		provider.dispose();
		return new ChannelId[] {id1, id2};
	}


	@Test
	public void testResourceUrlSetInResponse() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.send())
				          .wiretap(true)
				          .bindNow();

		final String requestUri = "http://localhost:" + disposableServer.port() + "/foo";
		StepVerifier.create(
		        createHttpClientForContextWithAddress()
		                .get()
		                .uri(requestUri)
		                .responseConnection((res, conn) -> Mono.justOrEmpty(res.resourceUrl())))
		            .expectNext(requestUri)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue975() throws Exception {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .route(routes ->
				              routes.get("/dispose",
				                  (req, res) -> res.sendString(
				                      Flux.range(0, 10_000)
				                          .map(i -> {
				                              if (i == 1_000) {
				                                  res.withConnection(Connection::disposeNow);
				                              }
				                              return "a";
				                          }))))
				          .bindNow();

		AtomicBoolean doAfterResponseSuccess = new AtomicBoolean();
		AtomicBoolean doOnResponseError = new AtomicBoolean();
		CountDownLatch latch = new CountDownLatch(1);
		HttpClient.create()
		          .doAfterResponseSuccess((resp, conn) -> doAfterResponseSuccess.set(true))
		          .doOnResponseError((resp, exc) -> doOnResponseError.set(true))
		          .get()
		          .uri("http://localhost:" + disposableServer.port() + "/dispose")
		          .responseSingle((resp, bytes) -> bytes.asString())
		          .subscribe(null, t -> latch.countDown());

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(doAfterResponseSuccess.get()).isFalse();
		assertThat(doOnResponseError.get()).isTrue();
	}

	@Test
	public void testIssue988() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.sendString(Mono.just("test")))
				          .wiretap(true)
				          .bindNow(Duration.ofSeconds(30));

		ConnectionProvider provider = ConnectionProvider.create("testIssue988", 1);
		HttpClient client =
				createHttpClientForContextWithAddress(provider)
				        .tcpConfiguration(tcpClient -> tcpClient.wiretap("testIssue988", LogLevel.INFO));

		AtomicReference<Channel> ch1 = new AtomicReference<>();
		StepVerifier.create(client.tcpConfiguration(tcpClient -> tcpClient.doOnConnected(c -> ch1.set(c.channel())))
				                  .get()
				                  .uri("/1")
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch2 = new AtomicReference<>();
		StepVerifier.create(client.tcpConfiguration(tcpClient -> tcpClient.doOnConnected(c -> ch2.set(c.channel())))
				                  .post()
				                  .uri("/2")
				                  .send(ByteBufFlux.fromString(Mono.just("test")))
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		AtomicReference<Channel> ch3 = new AtomicReference<>();
		StepVerifier.create(
				client.tcpConfiguration(tcpClient ->
				          tcpClient.doOnConnected(c -> ch3.set(c.channel()))
				                   .wiretap("testIssue988", LogLevel.ERROR))
				      .post()
				      .uri("/3")
				      .responseContent()
				      .aggregate()
				      .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		assertThat(ch1.get()).isSameAs(ch2.get());
		assertThat(ch1.get()).isNotSameAs(ch3.get());

		provider.dispose();
	}
}
