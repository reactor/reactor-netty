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

package reactor.netty.http.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.DefaultEventExecutor;
import org.junit.After;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.UnicastProcessor;
import reactor.core.publisher.SignalType;
import reactor.netty.ByteBufFlux;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableChannel;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;
import reactor.netty.NettyOutbound;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.http.client.PrematureCloseException;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.TcpClient;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;

import static org.assertj.core.api.Assertions.*;

/**
 * @author Stephane Maldini
 */
public class HttpServerTests {
	private DisposableServer disposableServer;

	@After
	public void tearDown() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
	}

	@Test
	public void httpPort() {
		disposableServer = HttpServer.create()
		                             .port(8080)
		                             .handle((req, resp) -> resp.sendNotFound())
		                             .wiretap(true)
		                             .bindNow();

		assertThat(disposableServer.address().getPort()).isEqualTo(8080);
	}

	@Test
	public void httpPortWithAddress() {
		disposableServer = HttpServer.create()
		                             .port(8080)
		                             .host("localhost")
		                             .handle((req, resp) -> resp.sendNotFound())
		                             .wiretap(true)
		                             .bindNow();

		assertThat(disposableServer.address().getPort()).isEqualTo(8080);
	}

	@Test
	public void releaseInboundChannelOnNonKeepAliveRequest() {
		disposableServer = HttpServer.create()
		                             .port(0)
		                             .handle((req, resp) -> req.receive().then(resp.status(200).send()))
		                             .wiretap(true)
		                             .bindNow();

		Flux<ByteBuf> src = Flux.range(0, 3)
		                        .map(n -> Unpooled.wrappedBuffer(Integer.toString(n)
		                                                                .getBytes(Charset.defaultCharset())));

		Flux.range(0, 100)
		    .concatMap(n -> HttpClient.create()
		                              .port(disposableServer.address().getPort())
		                              .tcpConfiguration(TcpClient::noSSL)
		                              .wiretap(true)
		                              .keepAlive(false)
		                              .post()
		                              .uri("/return")
		                              .send(src)
		                              .responseSingle((res, buf) -> Mono.just(res.status().code())))
		    .collectList()
		    .block();
	}

	//from https://github.com/reactor/reactor-netty/issues/90
	@Test
	public void testRestart() {
		HttpServer server1 = HttpServer.create()
		                               .host("localhost")
		                               .port(8080);
		HttpServer server2 =
				HttpServer.create()
				          // Any local address
				          .bindAddress(() -> new InetSocketAddress(8080));
		HttpClient client1 = HttpClient.create()
		                               .port(8080)
		                               .tcpConfiguration(tcpClient -> tcpClient.host("localhost"));
		HttpClient client2 = HttpClient.create()
		                               .baseUrl("http://localhost:8080");
		doTestRestart(server1, client1);
		doTestRestart(server1, client2);
		doTestRestart(server2, client1);
		doTestRestart(server2, client2);
	}

	private void doTestRestart(HttpServer server, HttpClient client) {
		String response;
		try {
			// start a first server with a handler that answers HTTP 200 OK
			disposableServer = server.handle((req, resp) -> resp.sendString(Mono.just("200")))
			                         .wiretap(true)
			                         .bindNow();

			response = client.wiretap(true)
			                 .get()
			                 .uri("/")
			                 .responseContent()
			                 .aggregate()
			                 .asString()
			                 .block();

			// checking the response status, OK
			assertThat(response).isEqualTo("200");
		}
		finally {
			// dispose the Netty context and wait for the channel close
			if (disposableServer != null) {
				disposableServer.disposeNow();
			}
		}

		try {
			// create a totally new server instance, with a different handler that answers HTTP 201
			disposableServer = server.handle((req, resp) -> resp.sendString(Mono.just("201")))
			                         .wiretap(true)
			                         .bindNow();

			response = client.wiretap(true)
			                 .get()
			                 .uri("/")
			                 .responseContent()
			                 .aggregate()
			                 .asString()
			                 .block();

			assertThat(response).isEqualTo("201");
		}
		finally {
			// dispose the Netty context and wait for the channel close
			if (disposableServer != null) {
				disposableServer.disposeNow();
			}
		}
	}

	@Test
	public void errorResponseAndReturn() {
		disposableServer = HttpServer.create()
		                             .port(0)
		                             .handle((req, resp) -> Mono.error(new Exception("returnError")))
		                             .wiretap(true)
		                             .bindNow();

		Integer code =
				HttpClient.create()
				          .port(disposableServer.address().getPort())
				          .wiretap(true)
				          .get()
				          .uri("/return")
				          .responseSingle((res, buf) -> Mono.just(res.status().code()))
				          .block();
		assertThat(code).isEqualTo(500);
	}

	@Test
	public void httpPipelining() throws Exception {

		AtomicInteger i = new AtomicInteger();

		disposableServer = HttpServer.create()
		                             .port(0)
		                             .handle((req, resp) ->
		                                     resp.header(HttpHeaderNames.CONTENT_LENGTH, "1")
		                                         .sendString(Mono.just(i.incrementAndGet())
		                                                         .flatMap(d ->
		                                                                 Mono.delay(Duration.ofSeconds(4 - d))
		                                                                     .map(x -> d + "\n"))))
		                             .wiretap(true)
		                             .bindNow();

		DefaultFullHttpRequest request =
				new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
				                           HttpMethod.GET,
				                           "/plaintext");

		CountDownLatch latch = new CountDownLatch(6);

		Connection client =
				TcpClient.create()
				         .port(disposableServer.address().getPort())
				         .handle((in, out) -> {
				                 in.withConnection(x ->
				                         x.addHandlerFirst(new HttpClientCodec()))
				                   .receiveObject()
				                   .ofType(DefaultHttpContent.class)
				                   .as(ByteBufFlux::fromInbound)
				                   .asString()
				                   .log()
				                   .map(Integer::parseInt)
				                   .subscribe(d -> {
				                       for (int x = 0; x < d; x++) {
				                           latch.countDown();
				                       }
				                   });

				                 return out.sendObject(Flux.just(request.retain(),
				                                                 request.retain(),
				                                                 request.retain()))
				                           .neverComplete();
				         })
				         .wiretap(true)
				         .connectNow();

		assertThat(latch.await(45, TimeUnit.SECONDS)).isTrue();

		client.disposeNow();
	}

	@Test
	public void flushOnComplete() {

		Flux<String> flux = Flux.range(0, 100)
		                        .map(n -> String.format("%010d", n));
		List<String> test =
				flux.collectList()
				    .block();
		assertThat(test).isNotNull();

		disposableServer = HttpServer.create()
		                             .port(0)
		                             .handle((req, resp) -> resp.sendString(flux.map(s -> s + "\n")))
		                             .wiretap(true)
		                             .bindNow();

		Flux<String> client = HttpClient.create()
		                                .port(disposableServer.address().getPort())
		                                .wiretap(true)
		                                .tcpConfiguration(tcp -> tcp.doOnConnected(res ->
		                                        res.addHandler(new LineBasedFrameDecoder(10))))
		                                .get()
		                                .uri("/")
		                                .responseContent()
		                                .asString();

		StepVerifier.create(client)
		            .expectNextSequence(test)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void keepAlive() throws URISyntaxException {
		Path resource = Paths.get(getClass().getResource("/public").toURI());
		disposableServer = HttpServer.create()
		                             .port(0)
		                             .route(routes -> routes.directory("/test", resource))
		                             .wiretap(true)
		                             .bindNow();

		ConnectionProvider p = ConnectionProvider.create("keepAlive", 1);

		Channel response0 = HttpClient.create(p)
		                              .port(disposableServer.address().getPort())
		                              .wiretap(true)
		                              .get()
		                              .uri("/test/index.html")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(3099));

		Channel response1 = HttpClient.create(p)
		                              .port(disposableServer.address().getPort())
		                              .wiretap(true)
		                              .get()
		                              .uri("/test/test.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(3099));

		Channel response2 = HttpClient.create(p)
		                              .port(disposableServer.address().getPort())
		                              .wiretap(true)
		                              .get()
		                              .uri("/test/test1.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Channel response3 = HttpClient.create(p)
		                              .port(disposableServer.address().getPort())
		                              .wiretap(true)
		                              .get()
		                              .uri("/test/test2.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Channel response4 = HttpClient.create(p)
		                              .port(disposableServer.address().getPort())
		                              .wiretap(true)
		                              .get()
		                              .uri("/test/test3.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                         .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Channel response5 = HttpClient.create(p)
		                              .port(disposableServer.address().getPort())
		                              .wiretap(true)
		                              .get()
		                              .uri("/test/test4.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		assertThat(response0).isEqualTo(response1);
		assertThat(response0).isEqualTo(response2);
		assertThat(response0).isEqualTo(response3);
		assertThat(response0).isEqualTo(response4);
		assertThat(response0).isEqualTo(response5);

		p.dispose();
	}

	@Test
	public void gettingOptionsDuplicates() {
		HttpServer server = HttpServer.create()
		                              .port(123)
		                              .host("example.com")
		                              .compress(true);
		assertThat(server.tcpConfiguration().configure())
		          .isNotSameAs(HttpServer.DEFAULT_TCP_SERVER)
		          .isNotSameAs(server.tcpConfiguration().configure());
	}

	@Test
	public void startRouter() {
		disposableServer = HttpServer.create()
		                             .port(0)
		                             .route(routes ->
		                                     routes.get("/hello",
		                                             (req, resp) -> resp.sendString(Mono.just("hello!"))))
		                             .wiretap(true)
		                             .bindNow();

		Integer code =
				HttpClient.create()
				          .port(disposableServer.address().getPort())
				          .wiretap(true)
				          .get()
				          .uri("/hello")
				          .responseSingle((res, buf) -> Mono.just(res.status().code()))
				          .block();
		assertThat(code).isEqualTo(200);

		code = HttpClient.create()
		                 .port(disposableServer.address().getPort())
		                 .wiretap(true)
		                 .get()
		                 .uri("/helloMan")
		                 .responseSingle((res, buf) -> Mono.just(res.status().code()))
		                 .block();
		assertThat(code).isEqualTo(404);
	}

	@Test
	public void startRouterAndAwait() throws InterruptedException {
		ExecutorService ex = Executors.newSingleThreadExecutor();
		AtomicReference<DisposableServer> ref = new AtomicReference<>();

		Future<?> f = ex.submit(() ->
			    HttpServer.create()
			              .port(0)
			              .route(routes -> routes.get("/hello", (req, resp) -> resp.sendString(Mono.just("hello!"))))
			              .wiretap(true)
			              .bindUntilJavaShutdown(Duration.ofSeconds(2), ref::set)
		);

		//if the server cannot be started, a ExecutionException will be thrown instead
		assertThatExceptionOfType(TimeoutException.class)
				.isThrownBy(() -> f.get(1, TimeUnit.SECONDS));

		//the router is not done and is still blocking the thread
		assertThat(f.isDone()).isFalse();
		assertThat(ref.get()).withFailMessage("Server is not initialized after 1s").isNotNull();

		//shutdown the router to unblock the thread
		ref.get().disposeNow();
		Thread.sleep(100);
		assertThat(f.isDone()).isTrue();
	}

	@Test
	public void nonContentStatusCodes() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .host("localhost")
				          .route(r -> r.get("/204-1", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
				                                                       .sendHeaders())
				                       .get("/204-2", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT))
				                       .get("/205-1", (req, res) -> res.status(HttpResponseStatus.RESET_CONTENT)
				                                                       .sendHeaders())
				                       .get("/205-2", (req, res) -> res.status(HttpResponseStatus.RESET_CONTENT))
				                       .get("/304-1", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED)
				                                                       .sendHeaders())
				                       .get("/304-2", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED))
				                       .get("/304-3", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED)
				                                                       .send()))
				          .wiretap(true)
				          .bindNow();

		checkResponse("/204-1", disposableServer.address());
		checkResponse("/204-2", disposableServer.address());
		checkResponse("/205-1", disposableServer.address());
		checkResponse("/205-2", disposableServer.address());
		checkResponse("/304-1", disposableServer.address());
		checkResponse("/304-2", disposableServer.address());
		checkResponse("/304-3", disposableServer.address());
	}

	private void checkResponse(String url, InetSocketAddress address) {
		Mono<Tuple3<Integer, HttpHeaders, String>> response =
				HttpClient.create()
				          .remoteAddress(() -> address)
				          .wiretap(true)
				          .get()
				          .uri(url)
				          .responseSingle((r, buf) ->
				                  Mono.zip(Mono.just(r.status().code()),
				                           Mono.just(r.responseHeaders()),
				                           buf.asString().defaultIfEmpty("NO BODY"))
				          );

		StepVerifier.create(response)
		            .expectNextMatches(t -> {
		                int code = t.getT1();
		                HttpHeaders h = t.getT2();
		                if (code == 204 || code == 304) {
		                    return !h.contains("Transfer-Encoding") &&
		                           !h.contains("Content-Length") &&
		                           "NO BODY".equals(t.getT3());
		                }
		                else if (code == 205) {
		                    return !h.contains("Transfer-Encoding") &&
		                           h.getInt("Content-Length").equals(0) &&
		                           "NO BODY".equals(t.getT3());
		                }else {
		                    return false;
		                }
		            })
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testContentLengthHeadRequest() {
		AtomicReference<HttpHeaders> sentHeaders = new AtomicReference<>();
		disposableServer =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.route(req -> req.uri().equals("/1"),
				                                  (req, res) -> res.sendString(Flux.just("OK").hide()))
				                       .route(req -> req.uri().startsWith("/2"),
				                                  (req, res) -> res.chunkedTransfer(false)
				                                                   .sendString(Flux.just("OK").hide()))
				                       .route(req -> req.uri().startsWith("/3"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, 2);
				                                                return res.sendString(Mono.just("OK"))
				                                                          .then()
				                                                          .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders()));
				                                                })
				                       .route(req -> req.uri().startsWith("/4"),
				                                  (req, res) -> res.sendHeaders())
				                       .route(req -> req.uri().startsWith("/5"),
				                                  (req, res) -> res.chunkedTransfer(false)
				                                                   .sendHeaders())
				                       .route(req -> req.uri().startsWith("/6"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, 2);
				                                                return res.sendHeaders()
				                                                          .then()
				                                                          .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders()));
				                                                })
				                       .route(req -> req.uri().startsWith("/7"),
				                                  (req, res) -> res.send()
				                                                   .then()
				                                                   .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders())))
				                       .route(req -> req.uri().startsWith("/8"),
				                                  (req, res) -> res.chunkedTransfer(false)
				                                                   .send()
				                                                   .then()
				                                                   .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders())))
				                       .route(req -> req.uri().startsWith("/9"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, 2);
				                                                return res.send()
				                                                          .then()
				                                                          .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders()));
				                                                })
				                       .route(req -> req.uri().startsWith("/10"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, 0);
				                                                return res.sendString(Mono.just("OK"))
				                                                          .then()
				                                                          .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders()));
				                                                })
				                       .route(req -> req.uri().startsWith("/11"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, 0);
				                                                return res.sendString(Flux.just("OK").hide())
				                                                          .then()
				                                                          .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders()));
				                                                })
				                       .route(req -> req.uri().startsWith("/12"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, 2);
				                                                return res.sendObject(Unpooled.wrappedBuffer("OK".getBytes(Charset.defaultCharset())))
				                                                          .then()
				                                                          .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders()));
				                                                })
				                       .route(req -> req.uri().startsWith("/13"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, 0);
				                                                return res.sendObject(Unpooled.wrappedBuffer("OK".getBytes(Charset.defaultCharset())))
				                                                          .then()
				                                                          .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders()));
				                                                }))
				          .wiretap(true)
				          .bindNow();

		doTestContentLengthHeadRequest("/1", disposableServer.address(), HttpMethod.GET, sentHeaders, true, false);
		doTestContentLengthHeadRequest("/1", disposableServer.address(), HttpMethod.HEAD, sentHeaders, true, false);
		doTestContentLengthHeadRequest("/2", disposableServer.address(), HttpMethod.GET, sentHeaders, false, true);
		doTestContentLengthHeadRequest("/2", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, true);
		doTestContentLengthHeadRequest("/3", disposableServer.address(), HttpMethod.GET, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/3", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/4", disposableServer.address(), HttpMethod.HEAD, sentHeaders, true, false);
		doTestContentLengthHeadRequest("/5", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, true);
		doTestContentLengthHeadRequest("/6", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/7", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/8", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/9", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/10", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/11", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/12", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/13", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, false);
	}

	@Test
	public void testIssue1153() {
		AtomicReference<HttpHeaders> sentHeaders = new AtomicReference<>();
		disposableServer =
				HttpServer.create()
				          .host("localhost")
				          .handle((req, res) -> {
				              res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, 2);
				              return Mono.empty()
				                         .then()
				                         .doFinally(s -> sentHeaders.set(res.responseHeaders()));
				          })
				          .wiretap(true)
				          .bindNow();
		doTestContentLengthHeadRequest("/", disposableServer.address(), HttpMethod.HEAD, sentHeaders, false, false);
	}

	private void doTestContentLengthHeadRequest(String url, InetSocketAddress address,
			HttpMethod method, AtomicReference<HttpHeaders> sentHeaders, boolean chunk, boolean close) {
		Mono<Tuple2<HttpHeaders, String>> response =
				HttpClient.create()
				          .remoteAddress(() -> address)
				          .wiretap(true)
				          .request(method)
				          .uri(url)
				          .responseSingle((res, buf) -> Mono.zip(Mono.just(res.responseHeaders()),
				                                                 buf.asString()
				                                                    .defaultIfEmpty("NO BODY")))
				          .delayElement(Duration.ofMillis(100));

		StepVerifier.create(response)
				    .expectNextMatches(t -> {
				        if (chunk) {
				            String chunked = t.getT1().get(HttpHeaderNames.TRANSFER_ENCODING);
				            String cl = t.getT1().get(HttpHeaderNames.CONTENT_LENGTH);
				            if (HttpMethod.GET.equals(method)) {
				                return chunked != null && cl == null && "OK".equals(t.getT2());
				            }
				            else {
				                return chunked == null && cl == null && "NO BODY".equals(t.getT2());
				            }
				        }
				        else if (close) {
				            String connClosed = t.getT1().get(HttpHeaderNames.CONNECTION);
				            String chunked = t.getT1().get(HttpHeaderNames.TRANSFER_ENCODING);
				            String cl = t.getT1().get(HttpHeaderNames.CONTENT_LENGTH);
				            if (HttpMethod.GET.equals(method)) {
				                return "close".equals(connClosed) && chunked == null && cl == null && "OK".equals(t.getT2());
				            }
				            else {
				                return "close".equals(connClosed) && chunked == null && cl == null && "NO BODY".equals(t.getT2());
				            }
				        }
				        else {
				            String chunkedReceived = t.getT1().get(HttpHeaderNames.TRANSFER_ENCODING);
				            String clReceived = t.getT1().get(HttpHeaderNames.CONTENT_LENGTH);
				            String chunkedSent = sentHeaders.get().get(HttpHeaderNames.TRANSFER_ENCODING);
				            String clSent =sentHeaders.get().get(HttpHeaderNames.CONTENT_LENGTH);
				            if (HttpMethod.GET.equals(method)) {
				                return chunkedReceived == null && chunkedSent == null &&
				                       Integer.parseInt(clReceived) == Integer.parseInt(clSent) &&
				                       "OK".equals(t.getT2());
				            }
				            else {
				                return chunkedReceived == null && chunkedSent == null &&
				                       Integer.parseInt(clReceived) == Integer.parseInt(clSent) &&
				                       "NO BODY".equals(t.getT2());
				            }
				        }
				    })
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue186() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.status(200).send())
				          .wiretap(true)
				          .bindNow();

		HttpClient client =
				HttpClient.create(ConnectionProvider.create("testIssue186", 1))
				          .remoteAddress(disposableServer::address)
				          .wiretap(true);

		doTestIssue186(client);
		doTestIssue186(client);
	}

	private void doTestIssue186(HttpClient client) {
		Mono<String> content = client.post()
				                     .uri("/")
				                     .send(ByteBufFlux.fromString(Mono.just("bodysample")))
				                     .responseContent()
				                     .aggregate()
				                     .asString();

		StepVerifier.create(content)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testConnectionCloseOnServerError() {
		Flux<String> content =
				Flux.range(1, 3)
				    .doOnNext(i -> {
				        if (i == 3) {
				            throw new RuntimeException("test");
				        }
				    })
				    .map(i -> "foo " + i);

		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.sendString(content))
				          .bindNow();

		AtomicReference<Channel> ch = new AtomicReference<>();
		Flux<ByteBuf> r =
				HttpClient.create()
				          .doOnResponse((res, c) -> ch.set(c.channel()))
				          .port(disposableServer.address().getPort())
				          .get()
				          .uri("/")
				          .responseContent();

		StepVerifier.create(r)
		            .expectNextCount(2)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		FutureMono.from(ch.get().closeFuture()).block(Duration.ofSeconds(30));
	}

	@Test
	public void contextShouldBeTransferredFromDownStreamToUpStream() {
		AtomicReference<Context> context = new AtomicReference<>();
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.status(200).send())
				          .bindNow();

		HttpClient client =
				HttpClient.create(ConnectionProvider.create("contextShouldBeTransferredFromDownStreamToUpStream", 1))
				          .remoteAddress(disposableServer::address);

		Mono<String> content = client.post()
		                             .uri("/")
		                             .send(ByteBufFlux.fromString(Mono.just("bodysample")
		                                                              .subscriberContext(c -> {
		                                                                  context.set(c);
		                                                                      return c;
		                                                                  })))
		                             .responseContent()
		                             .aggregate()
		                             .asString()
		                             .subscriberContext(c -> c.put("Hello", "World"));

			StepVerifier.create(content)
			            .expectComplete()
			            .verify(Duration.ofSeconds(30));
			assertThat(context.get().get("Hello").equals("World")).isTrue();
	}

	@Test
	public void testIssue309() {
		doTestIssue309("/somethingtooolooong",
				HttpServer.create()
				          .port(0)
				          .httpRequestDecoder(c -> c.maxInitialLineLength(20)));

		doTestIssue309("/something",
				HttpServer.create()
				          .port(0)
				          .httpRequestDecoder(c -> c.maxInitialLineLength(20)));
	}

	@Test
	public void portBindingException() {
		disposableServer = HttpServer.create()
		                             .port(0)
		                             .bindNow();

		try {
			HttpServer.create()
			          .port(disposableServer.port())
			          .bindNow();
			fail("illegal-success");
		}
		catch (ChannelBindException e){
			assertThat(e.localPort()).isEqualTo(disposableServer.port());
			e.printStackTrace();
		}
	}

	private void doTestIssue309(String path, HttpServer httpServer) {
		disposableServer =
				httpServer.handle((req, res) -> res.sendString(Mono.just("Should not be reached")))
				          .bindNow();

		Mono<HttpResponseStatus> status =
				HttpClient.create()
				          .port(disposableServer.address().getPort())
				          .get()
				          .uri(path)
				          .responseSingle((res, byteBufMono) -> Mono.just(res.status()));

		StepVerifier.create(status)
		            .expectNextMatches(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE::equals)
		            .expectComplete()
		            .verify();
	}

	@Test
	public void httpServerRequestConfigInjectAttributes() {
		AtomicReference<Channel> channelRef = new AtomicReference<>();
		AtomicReference<Boolean> validate = new AtomicReference<>();
		AtomicReference<Integer> chunkSize = new AtomicReference<>();
		HttpServer server =
				HttpServer.create()
				          .httpRequestDecoder(opt -> opt.maxInitialLineLength(123)
				                                        .maxHeaderSize(456)
				                                        .maxChunkSize(789)
				                                        .validateHeaders(false)
				                                        .initialBufferSize(10))
				          .handle((req, resp) -> req.receive().then(resp.sendNotFound()))
				          .tcpConfiguration(tcp ->
				                  tcp.doOnConnection(c -> {
				                      channelRef.set(c.channel());
				                      HttpServerCodec codec = c.channel()
				                                               .pipeline()
				                                               .get(HttpServerCodec.class);
				                      HttpObjectDecoder decoder = (HttpObjectDecoder) getValueReflection(codec, "inboundHandler", 1);
				                      chunkSize.set((Integer) getValueReflection(decoder, "maxChunkSize", 2));
				                      validate.set((Boolean) getValueReflection(decoder, "validateHeaders", 2));
				                  }))
				          .wiretap(true);

		disposableServer = server.bindNow();

		HttpClient.create()
		          .remoteAddress(disposableServer::address)
		          .post()
		          .uri("/")
		          .send(ByteBufFlux.fromString(Mono.just("bodysample")))
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block();

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
		catch(NoSuchFieldException | IllegalAccessException e) {
			return new RuntimeException(e);
		}
	}

	@Test
	public void testDropPublisherConnectionClose() throws Exception {
		ByteBuf data = ByteBufAllocator.DEFAULT.buffer();
		data.writeCharSequence("test", Charset.defaultCharset());
		CountDownLatch latch = new CountDownLatch(1);
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .send(Flux.defer(() -> Flux.just(data, data.retain(), data.retain())))
				                 .then()
				                 .doOnCancel(() -> {
				                     data.release(3);
				                     latch.countDown();
				                 }),
				(req, out) -> {
					req.addHeader("Connection", "close");
					return out;
				});
		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(ReferenceCountUtil.refCnt(data)).isEqualTo(0);
	}

	@Test
	public void testDropMessageConnectionClose() throws Exception {
		ByteBuf data = ByteBufAllocator.DEFAULT.buffer();
		data.writeCharSequence("test", Charset.defaultCharset());
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .sendObject(data),
				(req, out) -> {
					req.addHeader("Connection", "close");
					return out;
				});
		assertThat(ReferenceCountUtil.refCnt(data)).isEqualTo(0);
	}

	@Test
	public void testDropPublisher_1() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		ByteBuf data = ByteBufAllocator.DEFAULT.buffer();
		data.writeCharSequence("test", Charset.defaultCharset());
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .send(Flux.defer(() -> Flux.just(data, data.retain(), data.retain()))
				                           .doFinally(s -> latch.countDown()))
				                 .then(),
				(req, out) -> out);
		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(ReferenceCountUtil.refCnt(data)).isEqualTo(0);
	}

	@Test
	public void testDropPublisher_2() throws Exception {
		ByteBuf data = ByteBufAllocator.DEFAULT.buffer();
		data.writeCharSequence("test", Charset.defaultCharset());
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .send(Mono.just(data))
				                 .then(),
				(req, out) -> out);
		assertThat(ReferenceCountUtil.refCnt(data)).isEqualTo(0);
	}

	@Test
	public void testDropMessage() throws Exception {
		ByteBuf data = ByteBufAllocator.DEFAULT.buffer();
		data.writeCharSequence("test", Charset.defaultCharset());
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .sendObject(data),
				(req, out) -> out);
		assertThat(ReferenceCountUtil.refCnt(data)).isEqualTo(0);
	}

	private void doTestDropData(
			BiFunction<? super HttpServerRequest, ? super
					HttpServerResponse, ? extends Publisher<Void>> serverFn,
			BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>> clientFn)
			throws Exception {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle(serverFn)
				          .wiretap(true)
				          .bindNow(Duration.ofSeconds(30));

		CountDownLatch latch = new CountDownLatch(1);
		String response =
				HttpClient.create()
				          .port(disposableServer.port())
				          .wiretap(true)
				          .doOnRequest((req, conn) -> conn.onTerminate()
				                                          .subscribe(null, null, latch::countDown))
				          .request(HttpMethod.GET)
				          .uri("/")
				          .send(clientFn)
				          .responseContent()
				          .aggregate()
				          .asString()
				          .switchIfEmpty(Mono.just("Empty"))
				          .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(response).isEqualTo("Empty");
	}

	@Test
	public void testIssue525() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .tcpConfiguration(tcpServer ->
				                  tcpServer.doOnConnection(c -> c.addHandlerFirst("decompressor", new HttpContentDecompressor())))
				          .handle((req, res) -> res.send(req.receive()
				                                            .retain()))
				          .wiretap(true)
				          .bindNow(Duration.ofSeconds(30));

		byte[] bytes = "test".getBytes(Charset.defaultCharset());
		String response =
				HttpClient.create()
				          .port(disposableServer.port())
				          .wiretap(true)
				          .headers(h -> h.add("Content-Encoding", "gzip"))
				          .post()
				          .uri("/")
				          .send(Mono.just(Unpooled.wrappedBuffer(compress(bytes))))
				          .responseContent()
				          .aggregate()
				          .asString()
				          .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("test");
	}

	private static byte[] compress(byte[] body) {
		try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream()) {
			try (GZIPOutputStream zipStream = new GZIPOutputStream(byteStream)) {
				zipStream.write(body);
			}
			return byteStream.toByteArray();
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testCustomHandlerInvokedBeforeIOHandler() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .tcpConfiguration(tcpServer ->
				                  tcpServer.doOnConnection(c -> c.addHandlerFirst("custom", new ChannelInboundHandlerAdapter() {
				                      @Override
				                      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
				                          if (msg instanceof HttpRequest) {
				                              ((HttpRequest) msg).headers().add("test", "test");
				                          }
				                          super.channelRead(ctx, msg);
				                      }
				                  })))
				          .handle((req, res) -> res.sendString(
				                  Mono.just(req.requestHeaders().get("test", "not found"))))
				          .wiretap(true)
				          .bindNow();

		StepVerifier.create(
		        HttpClient.create()
		                  .remoteAddress(disposableServer::address)
		                  .wiretap(true)
		                  .get()
		                  .uri("/")
		                  .responseContent()
		                  .aggregate()
		                  .asString())
		            .expectNextMatches("test"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue630() {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				              // Not consuming the incoming data is deliberate
				              res.sendString(Flux.just("OK")
				                                 .delayElements(Duration.ofSeconds(3))))
				          .bindNow();

		Flux.range(0, 70)
		    .flatMap(i ->
		        HttpClient.create()
		                  .remoteAddress(disposableServer::address)
		                  .post()
		                  .uri("/")
		                  .send(ByteBufFlux.fromString(Mono.just("test")))
		                  .responseConnection((res, conn) -> {
		                      int status = res.status().code();
		                      conn.dispose();
		                      return Mono.just(status);
		                  }))
		    .blockLast(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("FutureReturnValueIgnored")
	public void testExpectErrorWhenConnectionClosed() throws Exception {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext serverCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();
		AtomicReference<Throwable> error = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .secure(spec -> spec.sslContext(serverCtx))
				          .handle((req, res) -> {
				              // "FutureReturnValueIgnored" is suppressed deliberately
				              res.withConnection(conn -> conn.channel().close());
				              return res.sendString(Flux.just("OK").hide())
				                        .then()
				                        .doOnError(t -> {
				                            error.set(t);
				                            latch.countDown();
				                        });
				          })
				          .bindNow();

		SslContext clientCtx = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                        .build();
		StepVerifier.create(
				HttpClient.create()
				          .remoteAddress(disposableServer::address)
				          .secure(spec -> spec.sslContext(clientCtx))
				          .get()
				          .uri("/")
				          .responseContent())
				    .verifyError(PrematureCloseException.class);

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(error.get()).isInstanceOf(AbortedException.class);
	}

	@Test
	public void testNormalConnectionCloseForWebSocketClient() {
		Flux<String> flux = Flux.range(0, 100)
		                        .map(n -> String.format("%010d", n));
		UnicastProcessor<String> receiver = UnicastProcessor.create();
		MonoProcessor<WebSocketCloseStatus> statusServer = MonoProcessor.create();
		MonoProcessor<WebSocketCloseStatus> statusClient = MonoProcessor.create();
		List<String> test =
		    flux.collectList()
		        .block();
		assertThat(test).isNotNull();

		DisposableServer c = HttpServer.create()
		                               .port(0)
		                               .handle((req, resp) -> resp.sendWebsocket((in, out) ->
			                               out.sendString(flux)
			                                  .then(out.sendClose(4404, "test"))
			                                  .then(in.receiveCloseStatus()
			                                          .subscribeWith(statusServer)
			                                          .then())
		                               ))
		                               .wiretap(true)
		                               .bindNow();

		HttpClient.create()
		          .port(c.address()
		                 .getPort())
		          .wiretap(true)
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> {
			          MonoProcessor<Object> done = MonoProcessor.create();
			          in.receiveCloseStatus()
			            .subscribeWith(statusClient);
			          in.receive()
			            .asString()
			            .doFinally((s) -> done.onComplete())
			            .subscribeWith(receiver);
			          return done.then(Mono.delay(Duration.ofMillis(500)));
		          })
		          .blockLast();

		StepVerifier.create(receiver)
		            .expectNextSequence(test)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(statusClient)
		            .expectNext(new WebSocketCloseStatus(4404, "test"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));


		StepVerifier.create(statusServer)
		            .expectNext(new WebSocketCloseStatus(4404, "test"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		c.disposeNow();
	}


	@Test
	public void testNormalConnectionCloseForWebSocketServer() {
		MonoProcessor<WebSocketCloseStatus> statusServer = MonoProcessor.create();
		MonoProcessor<WebSocketCloseStatus> statusClient = MonoProcessor.create();

		disposableServer = HttpServer.create()
		                             .port(0)
		                             .handle((req, resp) ->
		                                 resp.sendWebsocket((in, out) -> in.receiveCloseStatus()
		                                                                   .subscribeWith(statusServer)
		                                                                   .then()))
		                             .wiretap(true)
		                             .bindNow();

		HttpClient.create()
		          .port(disposableServer.address()
		                                .getPort())
		          .wiretap(true)
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> out.sendClose(4404, "test")
		                                  .then(in.receiveCloseStatus()
		                                          .subscribeWith(statusClient)))
		          .blockLast();

		StepVerifier.create(statusClient)
		            .expectNext(new WebSocketCloseStatus(4404, "test"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(statusServer)
		            .expectNext(new WebSocketCloseStatus(4404, "test"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testCancelConnectionCloseForWebSocketClient() {
		MonoProcessor<WebSocketCloseStatus> statusServer = MonoProcessor.create();
		MonoProcessor<WebSocketCloseStatus> statusClient = MonoProcessor.create();

		disposableServer = HttpServer.create()
		                             .port(0)
		                             .handle((req, resp) ->
		                                 resp.sendWebsocket((in, out) -> in.receiveCloseStatus()
		                                                                   .subscribeWith(statusServer)
		                                                                   .then()))
		                             .wiretap(true)
		                             .bindNow();

		HttpClient.create()
		          .port(disposableServer.address()
		                                .getPort())
		          .wiretap(true)
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> {
		              in.receiveCloseStatus()
		                .subscribeWith(statusClient);

		              in.withConnection(Connection::dispose);

		              return Mono.never();
		          })
		          .subscribe();

		StepVerifier.create(statusClient)
		            .expectNext(new WebSocketCloseStatus(-1, ""))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(statusServer)
		            .expectNext(new WebSocketCloseStatus(-1, ""))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testCancelReceivingForWebSocketClient() {
		MonoProcessor<WebSocketCloseStatus> statusServer = MonoProcessor.create();
		MonoProcessor<WebSocketCloseStatus> statusClient = MonoProcessor.create();

		disposableServer = HttpServer.create()
		                             .port(0)
		                             .handle((req, resp) ->
		                                 resp.sendWebsocket((in, out) -> {
		                                     in.receiveCloseStatus()
		                                       .subscribeWith(statusServer);

		                                     return out.sendString(Flux.interval(Duration.ofMillis(10))
		                                                               .map(l -> l + ""));
		                                 }))
		                             .wiretap(true)
		                             .bindNow();

		HttpClient.create()
		          .port(disposableServer.address()
		                                .getPort())
		          .wiretap(true)
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> {
		              in.receiveCloseStatus()
		                .subscribeWith(statusClient);

		              in.receive()
		                .take(1)
		                .subscribe();

		              return Mono.never();
		          })
		          .subscribe();

		StepVerifier.create(statusClient)
		            .expectNext(new WebSocketCloseStatus(-1, ""))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(statusServer)
		            .expectNext(new WebSocketCloseStatus(-1, ""))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testCancelConnectionCloseForWebSocketServer() {
		MonoProcessor<WebSocketCloseStatus> statusServer = MonoProcessor.create();
		MonoProcessor<WebSocketCloseStatus> statusClient = MonoProcessor.create();

		disposableServer = HttpServer.create()
		                             .port(0)
		                             .handle((req, resp) -> resp.sendWebsocket((in, out) -> {
		                                 in.receiveCloseStatus()
		                                   .subscribeWith(statusServer);

		                                 in.withConnection(Connection::dispose);

		                                 return Mono.never();
		                             }))
		                             .wiretap(true)
		                             .bindNow();

		HttpClient.create()
		          .port(disposableServer.address()
		                                .getPort())
		          .wiretap(true)
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> {
		              in.receiveCloseStatus()
		                .subscribeWith(statusClient);

		              return Mono.never();
		          })
		          .subscribe();

		StepVerifier.create(statusClient)
		            .expectNext(new WebSocketCloseStatus(-1, ""))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(statusServer)
		            .expectNext(new WebSocketCloseStatus(-1, ""))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testCancelReceivingForWebSocketServer() {
		MonoProcessor<WebSocketCloseStatus> statusServer = MonoProcessor.create();
		MonoProcessor<WebSocketCloseStatus> statusClient = MonoProcessor.create();

		disposableServer = HttpServer.create()
		                             .port(0)
		                             .handle((req, resp) -> resp.sendWebsocket((in, out) -> {
		                                 in.receiveCloseStatus()
		                                   .subscribeWith(statusServer);

		                                 in.receive()
		                                   .take(1)
		                                   .subscribe();

		                                 return Mono.never();
		                             }))
		                             .wiretap(true)
		                             .bindNow();

		HttpClient.create()
		          .port(disposableServer.address()
		                                .getPort())
		          .wiretap(true)
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> {
		              in.receiveCloseStatus()
		                .subscribeWith(statusClient);

		              return out.sendString(Flux.interval(Duration.ofMillis(10))
		                                        .map(l -> l + ""));
		          })
		          .subscribe();

		StepVerifier.create(statusClient)
		            .expectNext(new WebSocketCloseStatus(-1, ""))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(statusServer)
		            .expectNext(new WebSocketCloseStatus(-1, ""))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue825() throws Exception {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, resp) -> resp.sendString(Mono.just("test")))
				          .wiretap(true)
				          .bindNow();

		DefaultFullHttpRequest request =
				new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

		CountDownLatch latch = new CountDownLatch(1);

		Connection client =
				TcpClient.create()
				         .port(disposableServer.address().getPort())
				         .handle((in, out) -> {
				             in.withConnection(x -> x.addHandlerFirst(new HttpClientCodec()))
				               .receiveObject()
				               .ofType(DefaultHttpContent.class)
				               .as(ByteBufFlux::fromInbound)
				               // ReferenceCounted::release is deliberately invoked
				               // so that .release() in FluxReceive.drainReceiver will fail
				               .subscribe(ReferenceCounted::release, t -> latch.countDown(), null);

				             return out.sendObject(Flux.just(request))
				                       .neverComplete();
				         })
				         .wiretap(true)
				         .connectNow();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		client.disposeNow();
	}

	@Test
	public void testDecodingFailureLastHttpContent() throws Exception {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .route(r -> r.put("/1", (req, res) -> req.receive()
				                                                   .then(res.sendString(Mono.just("test"))
				                                                            .then()))
				                       .put("/2", (req, res) -> res.send(req.receive().retain())))
				          .bindNow();

		doTestDecodingFailureLastHttpContent("PUT /1 HTTP/1.1\r\nHost: a.example.com\r\n" +
				"Transfer-Encoding: chunked\r\n\r\nsomething\r\n\r\n", "400 Bad Request", "connection: close");
		doTestDecodingFailureLastHttpContent("PUT /2 HTTP/1.1\r\nHost: a.example.com\r\n" +
				"Transfer-Encoding: chunked\r\n\r\nsomething\r\n\r\n", "200 OK");
	}

	private void doTestDecodingFailureLastHttpContent(String message, String... expectations) throws Exception {
		TcpClient tcpClient =
				TcpClient.create()
				         .port(disposableServer.port())
				         .wiretap(true);

		Connection connection = tcpClient.connectNow();

		CountDownLatch latch = new CountDownLatch(1);
		connection.channel()
		          .closeFuture()
		          .addListener(f -> latch.countDown());

		AtomicReference<String> result = new AtomicReference<>();
		connection.inbound()
		          .receive()
		          .asString()
		          .doOnNext(result::set)
		          .subscribe();

		connection.outbound()
		          .sendString(Mono.just(message))
		          .then()
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(result.get()).contains(expectations);
		assertThat(connection.channel().isActive()).isFalse();
	}

	@Test
	public void testIssue891() throws Exception {
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .route(r -> r.get("/", (req, res) -> res.addHeader("Connection", "close")
				                                                  .sendString(Mono.just("test"))))
				          .bindNow();

		int port = disposableServer.port();
		String address = HttpUtil.formatHostnameForHttp(disposableServer.address()) + ":" + port;
		doTest(port, "GET http://" + address + "/ HTTP/1.1\r\nHost: " + address + "\r\n\r\n");
		doTest(port, "GET http://" + address + " HTTP/1.1\r\nHost: " + address + "\r\n\r\n");
	}

	private void doTest(int port, String message) throws Exception {
		TcpClient tcpClient =
				TcpClient.create()
				         .port(port)
				         .wiretap(true);

		Connection connection = tcpClient.connectNow();

		CountDownLatch latch = new CountDownLatch(2);
		connection.channel()
		          .closeFuture()
		          .addListener(f -> latch.countDown());

		AtomicReference<String> result = new AtomicReference<>();
		connection.inbound()
		          .receive()
		          .asString()
		          .doOnNext(s -> {
		              result.set(s);
		              latch.countDown();
		          })
		          .subscribe();

		connection.outbound()
		          .sendString(Mono.just(message))
		          .then()
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(result.get()).contains("test", "connection: close");
		assertThat(connection.channel().isActive()).isFalse();
	}

	@Test
	public void testIssue940() {
		AtomicInteger counter = new AtomicInteger();
		Flux<String> response =
				Flux.interval(Duration.ofMillis(200))
				    .map(l -> "" + counter.getAndIncrement())
				    .doFinally(sig -> {
				        if (SignalType.ON_ERROR.equals(sig)) {
				            counter.getAndDecrement();
				        }
				    });

		disposableServer =
				HttpServer.create()
				          .port(0)
				          .wiretap(true)
				          .handle((req, res) -> res.sendString(response))
				          .bindNow();

		HttpClient client =
				HttpClient.create()
				          .port(disposableServer.port());

		doTestIssue940(client, "0", "1");

		doTestIssue940(client, "2", "3");
	}

	private void doTestIssue940(HttpClient client, String... expectations) {
		StepVerifier.create(
		        client.get()
		              .responseContent()
		              .asString()
		              .take(2))
		            .expectNext(expectations)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue1001() throws Exception {
		disposableServer =
				HttpServer.create()
				          .host("localhost")
				          .port(0)
				          .wiretap(true)
				          .handle((req, res) -> res.sendString(Mono.just("testIssue1001")))
				          .bindNow();

		int port = disposableServer.port();
		Connection connection =
				TcpClient.create()
				         .remoteAddress(disposableServer::address)
				         .wiretap(true)
				         .connectNow();

		CountDownLatch latch = new CountDownLatch(1);
		connection.channel()
		          .closeFuture()
		          .addListener(f -> latch.countDown());

		AtomicReference<String> result = new AtomicReference<>();
		connection.inbound()
		          .receive()
		          .asString()
		          .doOnNext(result::set)
		          .subscribe();

		String address = HttpUtil.formatHostnameForHttp(disposableServer.address()) + ":" + port;
		connection.outbound()
		          .sendString(Mono.just("GET http://" + address + "/< HTTP/1.1\r\nHost: " + address + "\r\n\r\n"))
		          .then()
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(result.get()).contains("400", "connection: close");
		assertThat(connection.channel().isActive()).isFalse();

		StepVerifier.create(
		        HttpClient.create()
		                  .remoteAddress(disposableServer::address)
		                  .wiretap(true)
		                  .get()
		                  .uri("/<")
		                  .response())
		            .expectError(IllegalArgumentException.class)
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testGracefulShutdown() throws Exception {
		CountDownLatch latch1 = new CountDownLatch(2);
		CountDownLatch latch2 = new CountDownLatch(2);
		LoopResources loop = LoopResources.create("testGracefulShutdown");
		disposableServer =
				HttpServer.create()
				          .port(0)
				          .tcpConfiguration(tcpServer ->
				              tcpServer.runOn(loop)
				                       .doOnConnection(c -> {
				                           c.onDispose().subscribe(null, null, latch2::countDown);
				                           latch1.countDown();
				                       }))
				          // Register a channel group, when invoking disposeNow()
				          // the implementation will wait for the active requests to finish
				          .channelGroup(new DefaultChannelGroup(new DefaultEventExecutor()))
				          .route(r -> r.get("/delay500", (req, res) -> res.sendString(Mono.just("delay500")
				                                                          .delayElement(Duration.ofMillis(500))))
				                       .get("/delay1000", (req, res) -> res.sendString(Mono.just("delay1000")
				                                                           .delayElement(Duration.ofSeconds(1)))))
				          .wiretap(true)
				          .bindNow(Duration.ofSeconds(30));

		HttpClient client = HttpClient.create()
		                              .remoteAddress(disposableServer::address)
		                              .wiretap(true);

		MonoProcessor<String> result = MonoProcessor.create();
		Flux.just("/delay500", "/delay1000")
		    .flatMap(s ->
		            client.get()
		                  .uri(s)
		                  .responseContent()
		                  .aggregate()
		                  .asString())
		    .collect(Collectors.joining())
		    .subscribe(result);

		assertThat(latch1.await(30, TimeUnit.SECONDS)).isTrue();

		// Stop accepting incoming requests, wait at most 3s for the active requests to finish
		disposableServer.disposeNow();

		// Dispose the event loop
		loop.disposeLater()
		    .block(Duration.ofSeconds(30));

		assertThat(latch2.await(30, TimeUnit.SECONDS)).isTrue();

		StepVerifier.create(result)
		            .expectNext("delay500delay1000")
		            .verifyComplete();
	}

	@Test
	public void testStatus() {
		doTestStatus(HttpResponseStatus.OK);
		doTestStatus(new HttpResponseStatus(200, "Some custom reason phrase for 200 status code"));
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	private void doTestStatus(HttpResponseStatus status) {
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpServerOperations ops = new HttpServerOperations(
				Connection.from(channel),
				ConnectionObserver.emptyListener(),
				null,
				new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
				null,
				ServerCookieEncoder.STRICT,
				ServerCookieDecoder.STRICT);
		ops.status(status);
		HttpMessage response = ops.newFullBodyMessage(Unpooled.EMPTY_BUFFER);
		assertThat(((FullHttpResponse) response).status().reasonPhrase()).isEqualTo(status.reasonPhrase());
		// "FutureReturnValueIgnored" is suppressed deliberately
		channel.close();
	}
}
