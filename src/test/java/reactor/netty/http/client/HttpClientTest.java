/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
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
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.DefaultEventExecutor;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.ByteBufMono;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;
import reactor.netty.NettyPipeline;
import reactor.netty.SocketUtils;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpServer;
import reactor.test.StepVerifier;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 * @since 0.6
 */
@SuppressWarnings("deprecation")
public class HttpClientTest {

	@Test
	public void abort() {
		DisposableServer x =
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

		ConnectionProvider pool = ConnectionProvider.fixed("test", 1);

		HttpClient client = createHttpClientForContextWithPort(x, pool);

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

		x.disposeNow();

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

	@Test
	public void userIssue() throws Exception {
		final ConnectionProvider pool = ConnectionProvider.fixed("local", 1);
		CountDownLatch latch = new CountDownLatch(3);
		Set<String> localAddresses = ConcurrentHashMap.newKeySet();
		DisposableServer serverContext =
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

		final HttpClient client = createHttpClientForContextWithAddress(serverContext, pool);

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
		serverContext.disposeNow();
		System.out.println("Local Addresses used: " + localAddresses);
	}

	@Test
	@Ignore
	public void pipelined() {
		DisposableServer x =
				TcpServer.create()
				         .host("localhost")
				         .port(0)
				         .handle((in, out) ->
				                 out.withConnection(c -> c.addHandlerFirst(new HttpResponseEncoder()))
				                    .sendObject(Flux.just(response(), response()))
				                    .neverComplete())
				         .wiretap(true)
				         .bindNow();

		ConnectionProvider pool = ConnectionProvider.fixed("test", 1);

		HttpClient client = createHttpClientForContextWithAddress(x, pool);

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

		x.disposeNow();
		pool.dispose();
		Assert.fail("Not aborted");
	}

	@Test
	public void testClientReuseIssue405(){
		DisposableServer c =
				HttpServer.create()
				          .port(0)
				          .handle((in,out)->out.sendString(Flux.just("hello")))
				          .wiretap(true)
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.fixed("test", 1);
		HttpClient httpClient = createHttpClientForContextWithPort(c, pool);

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

		c.disposeNow();
		pool.dispose();
	}

	@Test
	public void backpressured() throws Exception {
		Path resource = Paths.get(getClass().getResource("/public").toURI());
		DisposableServer c = HttpServer.create()
		                               .port(0)
		                               .route(routes -> routes.directory("/test", resource))
		                               .wiretap(true)
		                               .bindNow();

		ByteBufFlux remote =
				createHttpClientForContextWithPort(c)
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
		c.disposeNow();
	}

	@Test
	public void serverInfiniteClientClose() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		DisposableServer c =
				HttpServer.create()
				          .port(0)
				          .handle((req, resp) -> {
				          	req.withConnection(cn -> cn.onDispose(latch::countDown));

				                  return Flux.interval(Duration.ofSeconds(1))
				                             .flatMap(d -> resp.sendObject(Unpooled.EMPTY_BUFFER));
				          })
				          .wiretap(true)
				          .bindNow();

		createHttpClientForContextWithPort(c)
		        .get()
		        .uri("/")
		        .response()
		        .block();

		latch.await();
		c.disposeNow();
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
		DisposableServer x =
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
				createHttpClientForContextWithAddress(x)
				        .get()
				        .uri("/")
				        .responseContent()
				        .timeout(signal))
				    .verifyError(TimeoutException.class);
	}

	@Test
	public void gzip() {
		String content = "HELLO WORLD";

		DisposableServer c =
				HttpServer.create()
				          .compress(true)
				          .port(0)
				          .handle((req, res) -> res.sendString(Mono.just(content)))
				          .bindNow();

		//verify gzip is negotiated (when no decoder)
		StepVerifier.create(
				createHttpClientForContextWithPort(c)
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
				createHttpClientForContextWithPort(c)
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
		c.disposeNow();
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
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req,res) -> res.sendString(Mono.just(req.requestHeaders()
				                                                           .get(HttpHeaderNames.ACCEPT_ENCODING,
				                                                                "no gzip"))))
				          .wiretap(true)
				          .bindNow();
		HttpClient client = createHttpClientForContextWithPort(server);

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

		server.disposeNow();
	}

	@Test
	public void testUserAgent() {
		DisposableServer c =
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

		createHttpClientForContextWithPort(c)
		        .get()
		        .uri("/")
		        .responseContent()
		        .blockLast();

		c.disposeNow();
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

		DisposableServer context =
				HttpServer.create()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .handle((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .wiretap(true)
				          .bindNow();


		String responseString =
				createHttpClientForContextWithAddress(context)
				          .secure(ssl -> ssl.sslContext(sslClient))
				          .get()
				          .uri("/foo")
				          .responseSingle((res, buf) -> buf.asString(CharsetUtil.UTF_8))
				          .block(Duration.ofMillis(200));
		context.disposeNow();

		assertThat(responseString).isEqualTo("hello /foo");
	}

	@Test
	public void sslExchangeAbsoluteGet() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();

		DisposableServer context =
				HttpServer.create()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .handle((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .wiretap(true)
				          .bindNow();

		String responseString = createHttpClientForContextWithAddress(context)
		                                .secure(ssl -> ssl.sslContext(sslClient))
		                                .get()
		                                .uri("/foo")
		                                .responseSingle((res, buf) -> buf.asString(CharsetUtil.UTF_8))
		                                .block();
		context.disposeNow();

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

		DisposableServer context =
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
				createHttpClientForContextWithAddress(context)
				        .secure(ssl -> ssl.sslContext(sslClient))
				        .post()
				        .uri("/upload")
				        .send((r, out) -> out.sendFile(largeFile))
				        .responseSingle((res, buf) -> buf.asString()
				                                         .zipWith(Mono.just(res.status().code())))
				        .block(Duration.ofSeconds(30));

		context.disposeNow();

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

		DisposableServer context =
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
				createHttpClientForContextWithAddress(context)
				        .post()
				        .uri("/upload")
				        .send((r, out) -> out.sendFile(largeFile))
				        .responseSingle((res, buf) -> buf.asString()
				                                         .zipWith(Mono.just(res.status().code())))
				        .block(Duration.ofSeconds(30));

		context.disposeNow();

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
		DisposableServer context =
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

		createHttpClientForContextWithAddress(context)
		        .doOnRequest((r, c) -> System.out.println("onReq: "+r))
		        .doAfterRequest((r, c) -> System.out.println("afterReq: "+r))
		        .doOnResponse((r, c) -> System.out.println("onResp: "+r))
		        .doAfterResponse((r, c) -> System.out.println("afterResp: "+r))
		        .put()
		        .uri("/201")
		        .responseContent()
		        .blockLast();

		createHttpClientForContextWithAddress(context)
		        .doOnRequest((r, c) -> System.out.println("onReq: "+r))
		        .doAfterRequest((r, c) -> System.out.println("afterReq: "+r))
		        .doOnResponse((r, c) -> System.out.println("onResp: "+r))
		        .doAfterResponse((r, c) -> System.out.println("afterResp: "+r))
		        .put()
		        .uri("/204")
		        .responseContent()
		        .blockLast(Duration.ofSeconds(30));

		createHttpClientForContextWithAddress(context)
		        .doOnRequest((r, c) -> System.out.println("onReq: "+r))
		        .doAfterRequest((r, c) -> System.out.println("afterReq: "+r))
		        .doOnResponse((r, c) -> System.out.println("onResp: "+r))
		        .doAfterResponse((r, c) -> System.out.println("afterResp: "+r))
		        .get()
		        .uri("/200")
		        .responseContent()
		        .blockLast(Duration.ofSeconds(30));

		context.disposeNow();
	}

	@Test
	public void testDeferredUri() {
		DisposableServer context =
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
		createHttpClientForContextWithAddress(context)
		        .observe((c, s) -> System.out.println(s + "" + c))
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

		context.disposeNow();
	}

	@Test
	public void testDeferredHeader() {
		DisposableServer context =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.get("/201", (req, res) -> res.addHeader
				                  ("Content-Length", "0")
				                                                     .status(HttpResponseStatus.CREATED)
				                                                     .sendHeaders()))
				          .bindNow();

		createHttpClientForContextWithAddress(context)
		        .headersWhen(h -> Mono.just(h.set("test", "test")).delayElement(Duration.ofSeconds(2)))
		        .observe((c, s) -> System.out.println(s + "" + c))
		        .get()
		        .uri("/201")
		        .responseContent()
		        .repeat(4)
		        .blockLast();

		context.disposeNow();
	}

	@Test
	public void testCookie() {
		DisposableServer context =
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

		createHttpClientForContextWithAddress(context)
		        .cookie("test", c -> c.setValue("lol"))
		        .get()
		        .uri("/201")
		        .responseContent()
		        .blockLast();

		context.disposeNow();
	}

	@Test
	public void closePool() {
		ConnectionProvider pr = ConnectionProvider.fixed("wstest", 1);
		DisposableServer httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((in, out) ->  out.options(NettyPipeline.SendOptions::flushOnEach)
				                                   .sendString(Mono.just("test")
				                                                   .delayElement(Duration.ofMillis(100))
				                                                   .repeat()))
				          .wiretap(true)
				          .bindNow();

		Flux<String> ws = createHttpClientForContextWithPort(httpServer, pr)
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

		httpServer.disposeNow();
		pr.dispose();
	}

	@Test
	public void testIssue303() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, resp) -> resp.sendString(Mono.just("OK")))
				          .wiretap(true)
				          .bindNow();

		Mono<String> content =
				createHttpClientForContextWithPort(server)
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

		server.disposeNow();
	}

	private HttpClient createHttpClientForContextWithAddress(DisposableServer context) {
		return createHttpClientForContextWithAddress(context, null);
	}

	private HttpClient createHttpClientForContextWithAddress(DisposableServer context,
			ConnectionProvider pool) {
		HttpClient client;
		if (pool == null) {
			client = HttpClient.create();
		}
		else {
			client = HttpClient.create(pool);
		}
		return client.addressSupplier(context::address)
		             .wiretap(true);
	}

	private HttpClient createHttpClientForContextWithPort(DisposableServer context) {
		return createHttpClientForContextWithPort(context, null);
	}

	private HttpClient createHttpClientForContextWithPort(DisposableServer context,
			ConnectionProvider pool) {
		HttpClient client;
		if (pool == null) {
			client = HttpClient.create();
		}
		else {
			client = HttpClient.create(pool);
		}
		return client.port(context.port())
		             .wiretap(true);
	}

	@Test
	public void testIssue361() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> req.receive()
				                                   .aggregate()
				                                   .asString()
				          .flatMap(s -> res.sendString(Mono.just(s))
				                           .then()))
				          .bindNow();

		assertThat(server).isNotNull();

		ConnectionProvider connectionProvider = ConnectionProvider.fixed("test", 1);
		HttpClient client = createHttpClientForContextWithPort(server, connectionProvider);

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

		server.disposeNow();
		connectionProvider.dispose();
	}

	@Test
	public void testIssue473() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverSslContextBuilder =
				SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .secure(spec -> spec.sslContext(serverSslContextBuilder))
				          .bindNow();

		StepVerifier.create(
				HttpClient.create(ConnectionProvider.newConnection())
				          .secure()
				          .websocket()
				          .uri("wss://" + server.host() + ":" + server.port())
				          .handle((in, out) -> Mono.empty()))
				    .expectErrorMatches(t -> t.getCause() instanceof CertificateException)
				.verify(Duration.ofSeconds(30));

		server.disposeNow();
	}

	@Test
	public void testIssue407() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .secure(spec -> spec.sslContext(
				                  SslContextBuilder.forServer(cert.certificate(), cert.privateKey())))
				          .handle((req, res) -> res.sendString(Mono.just("test")))
				          .wiretap(true)
				          .bindNow(Duration.ofSeconds(30));

		HttpClient client =
				createHttpClientForContextWithAddress(server)
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

		server.disposeNow();
	}


	@Test
	public void testClientContext() throws Exception {
		doTestClientContext(HttpClient.create());
		doTestClientContext(HttpClient.create(ConnectionProvider.newConnection()));
	}

	private void doTestClientContext(HttpClient client) throws Exception {
		CountDownLatch latch = new CountDownLatch(4);

		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.send(req.receive().retain()))
				          .wiretap(true)
				          .bindNow();

		StepVerifier.create(
				client.port(server.port())
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
				      .doAfterResponse((req, c) -> {
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
		server.disposeNow();
	}

	@Test
	public void doOnError() {
		DisposableServer server =
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

		AtomicInteger requestError = new AtomicInteger();
		AtomicInteger responseError = new AtomicInteger();

		Mono<String> content =
				createHttpClientForContextWithPort(server)
				        .headers(h -> h.add("before", "test"))
				        .doOnRequestError((req, err) ->
				            requestError.incrementAndGet())
				        .doOnResponseError((res, err) ->
				            responseError.incrementAndGet())
				        .get()
				        .uri("/")
				        .responseContent()
				        .aggregate()
				        .asString();

		StepVerifier.create(content)
		            .verifyError(PrematureCloseException.class);

		assertThat(requestError.getAndSet(0)).isEqualTo(1);
		assertThat(responseError.getAndSet(0)).isEqualTo(0);

		content =
				createHttpClientForContextWithPort(server)
				        .headers(h -> h.add("during", "test"))
				        .doOnError((req, err) ->
				            requestError.incrementAndGet()
				            ,(res, err) ->
				            responseError.incrementAndGet())
				        .get()
				        .uri("/")
				        .responseContent()
				        .aggregate()
				        .asString();

		StepVerifier.create(content)
		            .verifyError(PrematureCloseException.class);

		assertThat(requestError.getAndSet(0)).isEqualTo(0);
		assertThat(responseError.getAndSet(0)).isEqualTo(1);

		server.disposeNow();
	}

	@Test
	public void withConnector() {
		DisposableServer server = HttpServer.create()
		                                    .port(0)
		                                    .handle((req, resp) ->
			                                    resp.sendString(Mono.just(req.requestHeaders()
			                                                                 .get("test"))))
		                                    .bindNow();

		Mono<String> content = createHttpClientForContextWithPort(server)
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

		server.disposeNow();
	}

	@Test
	public void testPreferContentLengthWhenPost() {
		DisposableServer server =
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
				createHttpClientForContextWithAddress(server)
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

		server.disposeNow();
	}

	@Test
	public void testExplicitEmptyBodyOnGetWorks() throws Exception {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();

		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                        .build();

		DisposableServer server =
				HttpServer.create()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .port(0)
				          .handle((req, res) -> res.send(req.receive().retain()))
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.fixed("test", 1);

		for (int i = 0; i < 4; i++) {
			StepVerifier.create(createHttpClientForContextWithAddress(server, pool)
			                            .secure(ssl -> ssl.sslContext(sslClient))
			                            .request(HttpMethod.GET)
			                            .uri("/")
			                            .send((req, out) -> out.send(Flux.empty()))
			                            .responseContent())
			            .expectComplete()
			            .verify(Duration.ofSeconds(30));
		}

		pool.dispose();
		server.disposeNow();
	}

	@Test
	public void testExplicitSendMonoErrorOnGet() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.send(req.receive().retain()))
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.fixed("test", 1);

		StepVerifier.create(
				Flux.range(0, 1000)
				    .flatMapDelayError(i ->
				        createHttpClientForContextWithAddress(server, pool)
				                .request(HttpMethod.GET)
				                .uri("/")
				                .send((req, out) -> out.send(Mono.error(new Exception("test"))))
				                .responseContent(), Queues.SMALL_BUFFER_SIZE, Queues.XS_BUFFER_SIZE))
				    .expectError()
				    .verify(Duration.ofSeconds(30));

		pool.dispose();
		server.disposeNow();
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
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.send(req.receive()
				                                            .retain()
				                                            .delaySubscription(Duration.ofSeconds(1))))
				          .wiretap(true)
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.fixed("test", 10);
		LoopResources loop = LoopResources.create("test", 4, true);
		HttpClient client;
		if (withLoop) {
			client = createHttpClientForContextWithAddress(server, pool)
			            .tcpConfiguration(tcpClient -> tcpClient.runOn(loop));
		}
		else {
			client = createHttpClientForContextWithAddress(server, pool);
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
		server.disposeNow();

		assertThat(threadNames.size()).isGreaterThan(1);
	}

	@Test
	public void testChannelGroupClosesAllConnections() throws Exception {
		DisposableServer server =
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

		ConnectionProvider connectionProvider = ConnectionProvider.elastic("disposeLater");

		ChannelGroup group = new DefaultChannelGroup(new DefaultEventExecutor());

		CountDownLatch latch1 = new CountDownLatch(3);
		CountDownLatch latch2 = new CountDownLatch(3);

		HttpClient client = createHttpClientForContextWithAddress(server, connectionProvider);

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

		server.disposeNow();
	}

	@Test
	public void testIssue614() {
		DisposableServer server =
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
				createHttpClientForContextWithAddress(server)
				        .post()
				        .uri("/dump")
				        .sendForm((req, form) -> form.attr("attribute", "value"))
				        .responseContent()
				        .aggregate()
				        .asString())
				    .expectNext("OK")
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		server.disposeNow();
	}

	@Test
	public void testIssue632() throws Exception {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .handle((req, res) ->
				              res.header(HttpHeaderNames.CONNECTION,
				                         HttpHeaderValues.UPGRADE + ", " + HttpHeaderValues.CLOSE))
				          .bindNow();
		assertThat(server).isNotNull();

		CountDownLatch latch = new CountDownLatch(1);
		createHttpClientForContextWithPort(server)
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

		server.dispose();
	}

	@Test
	public void testIssue694() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> {
				              req.receive()
				                 .subscribe();
				              return Mono.empty();
				          })
				          .wiretap(true)
				          .bindNow();

		HttpClient client = createHttpClientForContextWithPort(server);

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

		server.disposeNow();
	}

	@Test
	public void testIssue700AndIssue876() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				                  res.options(o -> o.flushOnEach(false))
				                     .sendString(Flux.range(0, 10)
				                                     .map(i -> "test")
				                                     .delayElements(Duration.ofMillis(4))))
				          .bindNow();

		HttpClient client = createHttpClientForContextWithAddress(server);
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
			catch (Throwable t) {}
		}

		System.gc();
		for(int i = 0; i < 100000; ++i) {
			int[] arr = new int[100000];
		}
		System.gc();

		server.disposeNow();
	}

	@Test
	public void httpClientResponseConfigInjectAttributes() {
		AtomicReference<Channel> channelRef = new AtomicReference<>();
		AtomicReference<Boolean> validate = new AtomicReference<>();
		AtomicReference<Integer> chunkSize = new AtomicReference<>();

		DisposableServer server =
				HttpServer.create()
				          .handle((req, resp) -> req.receive()
				                                    .then(resp.sendNotFound()))
				          .wiretap(true)
				          .bindNow();

		createHttpClientForContextWithAddress(server)
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

		server.disposeNow();

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
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.send(req.receive()
				                                            .retain()))
				          .wiretap(true)
				          .bindNow();

		StepVerifier.create(
		        createHttpClientForContextWithAddress(server)
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

		server.disposeNow();
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

		DisposableServer disposableServer = server.bindNow();

		HttpClient client = createHttpClientForContextWithAddress(disposableServer);
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

		disposableServer.disposeNow();
	}

	@Test
	public void testIssue777() {
		DisposableServer server = null;
		try {
			server = HttpServer.create()
			                   .port(0)
			                   .wiretap(true)
			                   .route(r ->
			                       r.post("/empty", (req, res) -> res.status(400)
			                                                               .header(HttpHeaderNames.CONNECTION, "close")
			                                                               .send(Mono.empty()))
			                        .post("/test", (req, res) -> res.status(400)
			                                                              .header(HttpHeaderNames.CONNECTION, "close")
			                                                              .sendString(Mono.just("Test"))))
			                   .bindNow();

			HttpClient client = createHttpClientForContextWithAddress(server);

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
		finally {
			if (server != null) {
				server.disposeNow();
			}
		}
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
}
