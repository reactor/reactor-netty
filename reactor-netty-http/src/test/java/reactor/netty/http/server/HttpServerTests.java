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
import java.net.SocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
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
import io.netty.handler.ssl.SniCompletionEvent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufFlux;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.http.client.PrematureCloseException;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpServer;
import reactor.netty.transport.TransportConfig;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;

import javax.net.ssl.SNIHostName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assumptions.assumeThat;
import static reactor.netty.tcp.SslProvider.DefaultConfigurationType.TCP;

/**
 * @author Stephane Maldini
 */
class HttpServerTests extends BaseHttpTest {

	@Test
	void httpPort() {
		disposableServer = createServer(8080)
		                             .handle((req, resp) -> resp.sendNotFound())
		                             .bindNow();

		assertThat(disposableServer.port()).isEqualTo(8080);
	}

	@Test
	void httpPortWithAddress() {
		disposableServer = createServer(8080)
		                             .host("localhost")
		                             .handle((req, resp) -> resp.sendNotFound())
		                             .bindNow();

		assertThat(disposableServer.port()).isEqualTo(8080);
	}

	@Test
	void releaseInboundChannelOnNonKeepAliveRequest() {
		disposableServer = createServer()
		                             .handle((req, resp) -> req.receive().then(resp.status(200).send()))
		                             .bindNow();

		Flux<ByteBuf> src = Flux.range(0, 3)
		                        .map(n -> Unpooled.wrappedBuffer(Integer.toString(n)
		                                                                .getBytes(Charset.defaultCharset())));

		Flux.range(0, 100)
		    .concatMap(n -> createClient(disposableServer.port())
		                              .noSSL()
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
	void testRestart() {
		HttpServer server1 = createServer(8080).host("localhost");
		HttpServer server2 =
				HttpServer.create()
				          // Any local address
				          .bindAddress(() -> new InetSocketAddress(8080));
		HttpClient client1 = createClient(8080).host("localhost");
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
			                         .bindNow();

			response = client.get()
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
			                         .bindNow();

			response = client.get()
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
	void errorResponseAndReturn() {
		disposableServer = createServer()
		                             .handle((req, resp) -> Mono.error(new Exception("returnError")))
		                             .bindNow();

		Integer code =
				createClient(disposableServer.port())
				          .get()
				          .uri("/return")
				          .responseSingle((res, buf) -> Mono.just(res.status().code()))
				          .block();
		assertThat(code).isEqualTo(500);
	}

	@Test
	void httpPipelining() throws Exception {

		AtomicInteger i = new AtomicInteger();

		disposableServer = createServer()
		                             .handle((req, resp) ->
		                                     resp.header(HttpHeaderNames.CONTENT_LENGTH, "1")
		                                         .sendString(Mono.just(i.incrementAndGet())
		                                                         .flatMap(d ->
		                                                                 Mono.delay(Duration.ofSeconds(4 - d))
		                                                                     .map(x -> d + "\n"))))
		                             .bindNow();

		DefaultFullHttpRequest request =
				new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
				                           HttpMethod.GET,
				                           "/plaintext");

		CountDownLatch latch = new CountDownLatch(6);

		Connection client =
				TcpClient.create()
				         .port(disposableServer.port())
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

		assertThat(latch.await(45, TimeUnit.SECONDS)).as("latch await").isTrue();

		client.disposeNow();
	}

	@Test
	void flushOnComplete() {

		Flux<String> flux = Flux.range(0, 100)
		                        .map(n -> String.format("%010d", n));
		List<String> test =
				flux.collectList()
				    .block();
		assertThat(test).isNotNull();

		disposableServer = createServer()
		                             .handle((req, resp) -> resp.sendString(flux.map(s -> s + "\n")))
		                             .bindNow();

		Flux<String> client = createClient(disposableServer.port())
		                                .doOnConnected(res ->
		                                        res.addHandler(new LineBasedFrameDecoder(10)))
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
	void keepAlive() throws URISyntaxException {
		Path resource = Paths.get(getClass().getResource("/public").toURI());
		disposableServer = createServer()
		                             .route(routes -> routes.directory("/test", resource))
		                             .bindNow();

		ConnectionProvider p = ConnectionProvider.create("keepAlive", 1);

		Channel response0 = createClient(p, disposableServer.port())
		                              .get()
		                              .uri("/test/index.html")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Channel response1 = createClient(p, disposableServer.port())
		                              .get()
		                              .uri("/test/test.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Channel response2 = createClient(p, disposableServer.port())
		                              .get()
		                              .uri("/test/test1.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Channel response3 = createClient(p, disposableServer.port())
		                              .get()
		                              .uri("/test/test2.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Channel response4 = createClient(p, disposableServer.port())
		                              .get()
		                              .uri("/test/test3.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                         .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Channel response5 = createClient(p, disposableServer.port())
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
	void gettingOptionsDuplicates() {
		HttpServer server1 = HttpServer.create();
		HttpServer server2 = server1.port(123)
		                            .host("example.com")
		                            .compress(true);
		assertThat(server2)
				.isNotSameAs(server1)
				.isNotSameAs(((HttpServerBind) server2).duplicate());
	}

	@Test
	void startRouter() {
		disposableServer = createServer()
		                             .route(routes ->
		                                     routes.get("/hello",
		                                             (req, resp) -> resp.sendString(Mono.just("hello!"))))
		                             .bindNow();

		Integer code =
				createClient(disposableServer.port())
				          .get()
				          .uri("/hello")
				          .responseSingle((res, buf) -> Mono.just(res.status().code()))
				          .block();
		assertThat(code).isEqualTo(200);

		code = createClient(disposableServer.port())
		                 .get()
		                 .uri("/helloMan")
		                 .responseSingle((res, buf) -> Mono.just(res.status().code()))
		                 .block();
		assertThat(code).isEqualTo(404);
	}

	@Test
	void startRouterAndAwait() throws InterruptedException {
		ExecutorService ex = Executors.newSingleThreadExecutor();
		AtomicReference<DisposableServer> ref = new AtomicReference<>();

		Future<?> f = ex.submit(() ->
			    createServer()
			              .route(routes -> routes.get("/hello", (req, resp) -> resp.sendString(Mono.just("hello!"))))
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
	void nonContentStatusCodes() {
		disposableServer =
				createServer()
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
				          .bindNow();

		InetSocketAddress address = (InetSocketAddress) disposableServer.address();
		checkResponse("/204-1", address);
		checkResponse("/204-2", address);
		checkResponse("/205-1", address);
		checkResponse("/205-2", address);
		checkResponse("/304-1", address);
		checkResponse("/304-2", address);
		checkResponse("/304-3", address);
	}

	private void checkResponse(String url, InetSocketAddress address) {
		Mono<Tuple3<Integer, HttpHeaders, String>> response =
				createClient(() -> address)
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
		                }
		                else {
		                    return false;
		                }
		            })
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testContentLengthHeadRequest() {
		AtomicReference<HttpHeaders> sentHeaders = new AtomicReference<>();
		disposableServer =
				createServer()
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
				          .bindNow();

		InetSocketAddress address = (InetSocketAddress) disposableServer.address();
		doTestContentLengthHeadRequest("/1", address, HttpMethod.GET, sentHeaders, true, false);
		doTestContentLengthHeadRequest("/1", address, HttpMethod.HEAD, sentHeaders, true, false);
		doTestContentLengthHeadRequest("/2", address, HttpMethod.GET, sentHeaders, false, true);
		doTestContentLengthHeadRequest("/2", address, HttpMethod.HEAD, sentHeaders, false, true);
		doTestContentLengthHeadRequest("/3", address, HttpMethod.GET, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/3", address, HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/4", address, HttpMethod.HEAD, sentHeaders, true, false);
		doTestContentLengthHeadRequest("/5", address, HttpMethod.HEAD, sentHeaders, false, true);
		doTestContentLengthHeadRequest("/6", address, HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/7", address, HttpMethod.HEAD, sentHeaders, true, false);
		doTestContentLengthHeadRequest("/8", address, HttpMethod.HEAD, sentHeaders, false, true);
		doTestContentLengthHeadRequest("/9", address, HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/10", address, HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/11", address, HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/12", address, HttpMethod.HEAD, sentHeaders, false, false);
		doTestContentLengthHeadRequest("/13", address, HttpMethod.HEAD, sentHeaders, false, false);
	}

	@Test
	void testIssue1153() {
		AtomicReference<HttpHeaders> sentHeaders = new AtomicReference<>();
		disposableServer =
				createServer()
				          .host("localhost")
				          .handle((req, res) -> {
				              res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, 2);
				              return Mono.empty()
				                         .then()
				                         .doFinally(s -> sentHeaders.set(res.responseHeaders()));
				          })
				          .bindNow();
		InetSocketAddress address = (InetSocketAddress) disposableServer.address();
		doTestContentLengthHeadRequest("/", address, HttpMethod.HEAD, sentHeaders, false, false);
	}

	private void doTestContentLengthHeadRequest(String url, InetSocketAddress address,
			HttpMethod method, AtomicReference<HttpHeaders> sentHeaders, boolean chunk, boolean close) {
		Mono<Tuple2<HttpHeaders, String>> response =
				createClient(() -> address)
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
				            String clSent = sentHeaders.get().get(HttpHeaderNames.CONTENT_LENGTH);
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
	void testIssue186() {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.status(200).send())
				          .bindNow();

		ConnectionProvider provider = ConnectionProvider.create("testIssue186", 1);
		HttpClient client = createClient(provider, disposableServer::address);

		try {
			doTestIssue186(client);
			doTestIssue186(client);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(30));
		}
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
	void testConnectionCloseOnServerError() {
		Flux<String> content =
				Flux.range(1, 3)
				    .doOnNext(i -> {
				        if (i == 3) {
				            throw new RuntimeException("test");
				        }
				    })
				    .map(i -> "foo " + i);

		disposableServer =
				createServer()
				          .handle((req, res) -> res.sendString(content))
				          .bindNow();

		AtomicReference<Channel> ch = new AtomicReference<>();
		Flux<ByteBuf> r =
				createClient(disposableServer.port())
				          .doOnResponse((res, c) -> ch.set(c.channel()))
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
	void contextShouldBeTransferredFromDownStreamToUpStream() {
		AtomicReference<Context> context = new AtomicReference<>();
		disposableServer =
				createServer()
				          .handle((req, res) -> res.status(200).send())
				          .bindNow();

		ConnectionProvider provider = ConnectionProvider.create("contextShouldBeTransferredFromDownStreamToUpStream", 1);
		HttpClient client =
				HttpClient.create(provider)
				          .remoteAddress(disposableServer::address);

		try {
			for (int i = 0; i < 1000; i++) {
				Mono<String> content = client.post()
				                             .uri("/")
				                             .send(ByteBufFlux.fromString(Mono.just("bodysample")
				                                                              .contextWrite(
				                                                                      c -> {
				                                                                          context.set(c);
				                                                                          return c;
				                                                                      })))
				                             .responseContent()
				                             .aggregate()
				                             .asString()
				                             .contextWrite(c -> c.put("Hello", "World"));

				StepVerifier.create(content)
				            .expectComplete()
				            .verify(Duration.ofSeconds(30));
				assertThat(context.get()
				                  .get("Hello")
				                  .equals("World")).isTrue();
			}
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(30));
		}
	}

	@Test
	void testIssue309() {
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
	void portBindingException() {
		disposableServer = createServer().bindNow();

		try {
			createServer(disposableServer.port()).bindNow();
			fail("illegal-success");
		}
		catch (ChannelBindException e) {
			assertThat(e.localPort()).isEqualTo(disposableServer.port());
			e.printStackTrace();
		}
	}

	private void doTestIssue309(String path, HttpServer httpServer) {
		disposableServer =
				httpServer.handle((req, res) -> res.sendString(Mono.just("Should not be reached")))
				          .bindNow();

		Mono<HttpResponseStatus> status =
				createClient(disposableServer.port())
				          .get()
				          .uri(path)
				          .responseSingle((res, byteBufMono) -> Mono.just(res.status()));

		StepVerifier.create(status)
		            .expectNextMatches(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE::equals)
		            .expectComplete()
		            .verify();
	}

	@Test
	void httpServerRequestConfigInjectAttributes() {
		AtomicReference<Channel> channelRef = new AtomicReference<>();
		AtomicReference<Boolean> validate = new AtomicReference<>();
		AtomicReference<Integer> chunkSize = new AtomicReference<>();
		disposableServer =
				createServer()
				          .httpRequestDecoder(opt -> opt.maxInitialLineLength(123)
				                                        .maxHeaderSize(456)
				                                        .maxChunkSize(789)
				                                        .validateHeaders(false)
				                                        .initialBufferSize(10))
				          .handle((req, resp) -> req.receive().then(resp.sendNotFound()))
				          .doOnConnection(c -> {
				                      channelRef.set(c.channel());
				                      HttpServerCodec codec = c.channel()
				                                               .pipeline()
				                                               .get(HttpServerCodec.class);
				                      HttpObjectDecoder decoder = (HttpObjectDecoder) getValueReflection(codec, "inboundHandler", 1);
				                      chunkSize.set((Integer) getValueReflection(decoder, "maxChunkSize", 2));
				                      validate.set((Boolean) getValueReflection(decoder, "validateHeaders", 2));
				                  })
				          .bindNow();

		createClient(disposableServer::address)
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
		catch (NoSuchFieldException | IllegalAccessException e) {
			return new RuntimeException(e);
		}
	}

	@Test
	void testDropPublisherConnectionClose() throws Exception {
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
	void testDropMessageConnectionClose() throws Exception {
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
	void testDropPublisher_1() throws Exception {
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
	void testDropPublisher_2() throws Exception {
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
	void testDropMessage() throws Exception {
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
				createServer()
				          .handle(serverFn)
				          .bindNow(Duration.ofSeconds(30));

		CountDownLatch latch = new CountDownLatch(1);
		String response =
				createClient(disposableServer.port())
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
	void testIssue525() {
		disposableServer =
				createServer()
				          .doOnConnection(c -> c.addHandlerFirst("decompressor", new HttpContentDecompressor()))
				          .handle((req, res) -> res.send(req.receive()
				                                            .retain()))
				          .bindNow(Duration.ofSeconds(30));

		byte[] bytes = "test".getBytes(Charset.defaultCharset());
		String response =
				createClient(disposableServer.port())
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
	void testCustomHandlerInvokedBeforeIOHandler() {
		disposableServer =
				createServer()
				          .doOnConnection(c -> c.addHandlerFirst("custom", new ChannelInboundHandlerAdapter() {
				                      @Override
				                      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
				                          if (msg instanceof HttpRequest) {
				                              ((HttpRequest) msg).headers().add("test", "test");
				                          }
				                          super.channelRead(ctx, msg);
				                      }
				                  }))
				          .handle((req, res) -> res.sendString(
				                  Mono.just(req.requestHeaders().get("test", "not found"))))
				          .bindNow();

		StepVerifier.create(
		        createClient(disposableServer::address)
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
	void testIssue630() {
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
	void testExpectErrorWhenConnectionClosed() throws Exception {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext serverCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();
		AtomicReference<Throwable> error = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				createServer()
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
				createClient(disposableServer::address)
				          .secure(spec -> spec.sslContext(clientCtx))
				          .get()
				          .uri("/")
				          .responseContent())
				    .verifyError(PrematureCloseException.class);

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(error.get()).isInstanceOf(AbortedException.class);
	}

	@Test
	void testNormalConnectionCloseForWebSocketClient() throws Exception {
		Flux<String> flux = Flux.range(0, 100)
		                        .map(n -> String.format("%010d", n));
		AtomicReference<List<String>> receiver = new AtomicReference<>(new ArrayList<>());
		AtomicReference<WebSocketCloseStatus> statusServer = new AtomicReference<>();
		AtomicReference<WebSocketCloseStatus> statusClient = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(3);
		List<String> test =
		    flux.collectList()
		        .block();
		assertThat(test).isNotNull();

		disposableServer = createServer()
		                               .handle((req, resp) -> resp.sendWebsocket((in, out) ->
			                               out.sendString(flux)
			                                  .then(out.sendClose(4404, "test"))
			                                  .then(in.receiveCloseStatus()
			                                          .doOnNext(o -> {
			                                              statusServer.set(o);
			                                              latch.countDown();
			                                          })
			                                          .then())
		                               ))
		                               .bindNow();

		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> {
			          in.receiveCloseStatus()
			            .doOnNext(o -> {
			                statusClient.set(o);
			                latch.countDown();
			            })
			            .subscribe();

			          return in.receive()
			                   .asString()
			                   .doOnNext(s -> receiver.get().add(s))
			                   .doFinally(sig -> latch.countDown())
			                   .then(Mono.delay(Duration.ofMillis(500)));
		          })
		          .blockLast();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(receiver.get()).containsAll(test);

		assertThat(statusClient.get()).isNotNull()
				.isEqualTo(new WebSocketCloseStatus(4404, "test"));

		assertThat(statusServer.get()).isNotNull()
				.isEqualTo(new WebSocketCloseStatus(4404, "test"));
	}


	@Test
	void testNormalConnectionCloseForWebSocketServer() throws Exception {
		AtomicReference<WebSocketCloseStatus> statusServer = new AtomicReference<>();
		AtomicReference<WebSocketCloseStatus> statusClient = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(2);
		disposableServer = createServer()
		                             .handle((req, resp) ->
		                                 resp.sendWebsocket((in, out) ->
		                                     in.receiveCloseStatus()
		                                       .doOnNext(o -> {
		                                           statusServer.set(o);
		                                           latch.countDown();
		                                       })
		                                       .then()))
		                             .bindNow();

		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> out.sendClose(4404, "test")
		                                  .then(in.receiveCloseStatus()
		                                          .doOnNext(o -> {
		                                              statusClient.set(o);
		                                              latch.countDown();
		                                          })))
		          .blockLast();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(statusClient.get()).isNotNull()
				.isEqualTo(new WebSocketCloseStatus(4404, "test"));

		assertThat(statusServer.get()).isNotNull()
				.isEqualTo(new WebSocketCloseStatus(4404, "test"));
	}

	@Test
	void testCancelConnectionCloseForWebSocketClient() throws Exception {
		AtomicReference<WebSocketCloseStatus> statusServer = new AtomicReference<>();
		AtomicReference<WebSocketCloseStatus> statusClient = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(2);
		disposableServer = createServer()
		                             .handle((req, resp) ->
		                                 resp.sendWebsocket((in, out) ->
		                                     in.receiveCloseStatus()
		                                       .doOnNext(o -> {
		                                           statusServer.set(o);
		                                           latch.countDown();
		                                       })
		                                       .then()))
		                             .bindNow();

		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> {
		              in.receiveCloseStatus()
		                .doOnNext(o -> {
		                    statusClient.set(o);
		                    latch.countDown();
		                })
		              .subscribe();

		              in.withConnection(Connection::dispose);

		              return Mono.never();
		          })
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(statusClient.get()).isNotNull()
				.isEqualTo(WebSocketCloseStatus.ABNORMAL_CLOSURE);

		assertThat(statusServer.get()).isNotNull()
				.isEqualTo(new WebSocketCloseStatus(-1, ""));
	}

	@Test
	void testCancelReceivingForWebSocketClient() throws Exception {
		AtomicReference<WebSocketCloseStatus> statusServer = new AtomicReference<>();
		AtomicReference<WebSocketCloseStatus> statusClient = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(2);
		disposableServer = createServer()
		                             .handle((req, resp) ->
		                                 resp.sendWebsocket((in, out) -> {
		                                     in.receiveCloseStatus()
		                                       .doOnNext(o -> {
		                                           statusServer.set(o);
		                                           latch.countDown();
		                                       })
		                                       .subscribe();

		                                     return out.sendString(Flux.interval(Duration.ofMillis(10))
		                                                               .map(l -> l + ""));
		                                 }))
		                             .bindNow();

		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> {
		              in.receiveCloseStatus()
		                .doOnNext(o -> {
		                    statusClient.set(o);
		                    latch.countDown();
		                })
		                .subscribe();

		              in.receive()
		                .take(1)
		                .subscribe();

		              return Mono.never();
		          })
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(statusClient.get()).isNotNull()
				.isEqualTo(WebSocketCloseStatus.ABNORMAL_CLOSURE);

		assertThat(statusServer.get()).isNotNull()
				.isEqualTo(new WebSocketCloseStatus(-1, ""));
	}

	@Test
	void testCancelConnectionCloseForWebSocketServer() throws Exception {
		AtomicReference<WebSocketCloseStatus> statusServer = new AtomicReference<>();
		AtomicReference<WebSocketCloseStatus> statusClient = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(2);
		disposableServer = createServer()
		                             .handle((req, resp) -> resp.sendWebsocket((in, out) -> {
		                                 in.receiveCloseStatus()
		                                   .doOnNext(o -> {
		                                       statusServer.set(o);
		                                       latch.countDown();
		                                   })
		                                 .subscribe();

		                                 in.withConnection(Connection::dispose);

		                                 return Mono.never();
		                             }))
		                             .bindNow();

		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> {
		              in.receiveCloseStatus()
		                .doOnNext(o -> {
		                    statusClient.set(o);
		                    latch.countDown();
		                })
		                .subscribe();

		              return Mono.never();
		          })
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(statusClient.get()).isNotNull()
				.isEqualTo(new WebSocketCloseStatus(-1, ""));

		assertThat(statusServer.get()).isNotNull()
				.isEqualTo(WebSocketCloseStatus.ABNORMAL_CLOSURE);
	}

	@Test
	void testCancelReceivingForWebSocketServer() throws Exception {
		AtomicReference<WebSocketCloseStatus> statusServer = new AtomicReference<>();
		AtomicReference<WebSocketCloseStatus> statusClient = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(2);
		disposableServer = createServer()
		                             .handle((req, resp) -> resp.sendWebsocket((in, out) -> {
		                                 in.receiveCloseStatus()
		                                   .doOnNext(o -> {
		                                       statusServer.set(o);
		                                       latch.countDown();
		                                   })
		                                   .subscribe();

		                                 in.receive()
		                                   .take(1)
		                                   .subscribe();

		                                 return Mono.never();
		                             }))
		                             .bindNow();

		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> {
		              in.receiveCloseStatus()
		                .doOnNext(o -> {
		                    statusClient.set(o);
		                    latch.countDown();
		                })
		                .subscribe();

		              return out.sendString(Flux.interval(Duration.ofMillis(10))
		                                        .map(l -> l + ""));
		          })
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(statusClient.get()).isNotNull()
				.isEqualTo(new WebSocketCloseStatus(-1, ""));

		assertThat(statusServer.get()).isNotNull()
				.isEqualTo(WebSocketCloseStatus.ABNORMAL_CLOSURE);
	}

	@Test
	void testIssue825() throws Exception {
		disposableServer =
				createServer()
				          .handle((req, resp) -> resp.sendString(Mono.just("test")))
				          .bindNow();

		DefaultFullHttpRequest request =
				new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

		CountDownLatch latch = new CountDownLatch(1);

		Connection client =
				TcpClient.create()
				         .port(disposableServer.port())
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
	void testDecodingFailureLastHttpContent() throws Exception {
		disposableServer =
				createServer()
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
	void testIssue891() throws Exception {
		disposableServer =
				createServer()
				          .route(r -> r.get("/", (req, res) -> res.addHeader("Connection", "close")
				                                                  .sendString(Mono.just("test"))))
				          .bindNow();

		int port = disposableServer.port();
		String address = HttpUtil.formatHostnameForHttp((InetSocketAddress) disposableServer.address()) + ":" + port;
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
	void testIssue940() {
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
				createServer()
				          .handle((req, res) -> res.sendString(response))
				          .bindNow();

		HttpClient client = createClient(disposableServer.port());

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
	void testIssue1001() throws Exception {
		disposableServer =
				createServer()
				          .host("localhost")
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

		String address = HttpUtil.formatHostnameForHttp((InetSocketAddress) disposableServer.address()) + ":" + port;
		connection.outbound()
		          .sendString(Mono.just("GET http://" + address + "/< HTTP/1.1\r\nHost: " + address + "\r\n\r\n"))
		          .then()
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(result.get()).contains("400", "connection: close");
		assertThat(connection.channel().isActive()).isFalse();

		StepVerifier.create(
		        createClient(disposableServer::address)
		                  .get()
		                  .uri("/<")
		                  .response())
		            .expectError(IllegalArgumentException.class)
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testGracefulShutdown() throws Exception {
		CountDownLatch latch1 = new CountDownLatch(2);
		CountDownLatch latch2 = new CountDownLatch(2);
		CountDownLatch latch3 = new CountDownLatch(1);
		LoopResources loop = LoopResources.create("testGracefulShutdown");
		disposableServer =
				createServer()
				          .runOn(loop)
				          .doOnConnection(c -> {
				              c.onDispose().subscribe(null, null, latch2::countDown);
				              latch1.countDown();
				          })
				          // Register a channel group, when invoking disposeNow()
				          // the implementation will wait for the active requests to finish
				          .channelGroup(new DefaultChannelGroup(new DefaultEventExecutor()))
				          .route(r -> r.get("/delay500", (req, res) -> res.sendString(Mono.just("delay500")
				                                                          .delayElement(Duration.ofMillis(500))))
				                       .get("/delay1000", (req, res) -> res.sendString(Mono.just("delay1000")
				                                                           .delayElement(Duration.ofSeconds(1)))))
				          .bindNow(Duration.ofSeconds(30));

		HttpClient client = createClient(disposableServer::address);

		AtomicReference<String> result = new AtomicReference<>();
		Flux.just("/delay500", "/delay1000")
		    .flatMap(s ->
		            client.get()
		                  .uri(s)
		                  .responseContent()
		                  .aggregate()
		                  .asString())
		    .collect(Collectors.joining())
		    .subscribe(s -> {
		        result.set(s);
		        latch3.countDown();
		    });

		assertThat(latch1.await(30, TimeUnit.SECONDS)).isTrue();

		// Stop accepting incoming requests, wait at most 3s for the active requests to finish
		disposableServer.disposeNow();

		assertThat(latch2.await(30, TimeUnit.SECONDS)).isTrue();

		// Dispose the event loop
		loop.disposeLater()
		    .block(Duration.ofSeconds(30));

		assertThat(latch3.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(result.get()).isNotNull()
				.isEqualTo("delay500delay1000");
	}

	@Test
	void testHttpServerWithDomainSocketsNIOTransport() {
		assertThatExceptionOfType(ChannelBindException.class)
				.isThrownBy(() -> {
					LoopResources loop = LoopResources.create("testHttpServerWithDomainSocketsNIOTransport");
					try {
						HttpServer.create()
						          .runOn(loop, false)
						          .bindAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
						          .bindNow();
					}
					finally {
						loop.disposeLater()
						    .block(Duration.ofSeconds(30));
					}
		});
	}

	@Test
	void testHttpServerWithDomainSocketsWithHost() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> HttpServer.create()
		                                    .bindAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
		                                    .host("localhost")
		                                    .bindNow());
	}

	@Test
	void testHttpServerWithDomainSocketsWithPort() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> HttpServer.create()
		                                    .bindAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
		                                    .port(1234)
		                                    .bindNow());
	}

	@Test
	void testHttpServerWithDomainSockets_HTTP11() {
		doTestHttpServerWithDomainSockets(HttpServer.create(), HttpClient.create(), "http");
	}

	@Test
	void testHttpServerWithDomainSockets_HTTP2() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverCtx = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		SslContextBuilder clientCtx = SslContextBuilder.forClient()
		                                               .trustManager(InsecureTrustManagerFactory.INSTANCE);
		doTestHttpServerWithDomainSockets(
				HttpServer.create().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
				HttpClient.create().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
				"https");
	}

	private void doTestHttpServerWithDomainSockets(HttpServer server, HttpClient client, String expectedScheme) {
		assumeThat(LoopResources.hasNativeSupport()).isTrue();
		disposableServer =
				server.bindAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
				      .wiretap(true)
				      .handle((req, res) -> {
				          req.withConnection(conn -> {
				              assertThat(conn.channel().localAddress()).isNull();
				              assertThat(conn.channel().remoteAddress()).isNull();
				              assertThat(req.hostAddress()).isNull();
				              assertThat(req.remoteAddress()).isNull();
				              assertThat(req.scheme()).isNotNull().isEqualTo(expectedScheme);
				          });
				          assertThat(req.requestHeaders().get(HttpHeaderNames.HOST)).isEqualTo("localhost");
				          return res.send(req.receive().retain());
				      })
				      .bindNow();

		String response =
				client.remoteAddress(disposableServer::address)
				      .wiretap(true)
				      .post()
				      .uri("/")
				      .send(ByteBufFlux.fromString(Flux.just("1", "2", "3")))
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("123");
	}

	@Test
	@SuppressWarnings("deprecation")
	void testTcpConfiguration_1() throws Exception {
		CountDownLatch latch = new CountDownLatch(10);
		LoopResources loop = LoopResources.create("testTcpConfiguration");
		ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
		doTestTcpConfiguration(
				HttpServer.create().tcpConfiguration(tcp -> configureTcpServer(tcp, loop, group, latch)),
				HttpClient.create().tcpConfiguration(tcp -> configureTcpClient(tcp, loop, group, latch))
		);

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		FutureMono.from(group.close())
		          .then(loop.disposeLater())
		          .block(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testTcpConfiguration_2() throws Exception {
		CountDownLatch latch = new CountDownLatch(10);
		LoopResources loop = LoopResources.create("testTcpConfiguration");
		ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
		doTestTcpConfiguration(
				HttpServer.from(configureTcpServer(TcpServer.create(), loop, group, latch)),
				HttpClient.from(configureTcpClient(TcpClient.create(), loop, group, latch))
		);

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		FutureMono.from(group.close())
		          .then(loop.disposeLater())
		          .block(Duration.ofSeconds(30));
	}

	private void doTestTcpConfiguration(HttpServer server, HttpClient client) {
		disposableServer = server.bindNow();

		String response =
				client.post()
				      .uri("/")
				      .send(ByteBufFlux.fromString(Mono.just("testTcpConfiguration")))
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("testTcpConfiguration");
	}

	private TcpServer configureTcpServer(TcpServer tcp, LoopResources loop, ChannelGroup group, CountDownLatch latch) {
		return tcp.wiretap(true)
		          .host("localhost")
		          .runOn(loop)
		          .channelGroup(group)
		          .doOnBound(s -> latch.countDown())
		          .doOnConnection(c -> latch.countDown())
		          .doOnUnbound(s -> latch.countDown())
		          .handle((req, res) -> res.send(req.receive().retain()))
		          .noSSL()
		          .port(0)
		          .attr(AttributeKey.valueOf("testTcpConfiguration"), "testTcpConfiguration")
		          .option(ChannelOption.valueOf("testTcpConfiguration"), "testTcpConfiguration")
		          .childAttr(AttributeKey.valueOf("testTcpConfiguration"), "testTcpConfiguration")
		          .childOption(ChannelOption.valueOf("testTcpConfiguration"), "testTcpConfiguration")
		          .observe((conn, state) -> latch.countDown())
		          .childObserve((conn, state) -> latch.countDown())
		          .doOnChannelInit((observer, channel, address) -> latch.countDown());
	}

	private TcpClient configureTcpClient(TcpClient tcp, LoopResources loop, ChannelGroup group, CountDownLatch latch) {
		return tcp.wiretap(true)
		          .runOn(loop)
		          .channelGroup(group)
		          .doOnConnected(c -> latch.countDown())
		          .doOnDisconnected(c -> latch.countDown())
		          .noSSL()
		          .noProxy()
		          .remoteAddress(() -> disposableServer.address())
		          .attr(AttributeKey.valueOf("testTcpConfiguration"), "testTcpConfiguration")
		          .option(ChannelOption.valueOf("testTcpConfiguration"), "testTcpConfiguration")
		          .observe((conn, state) -> latch.countDown())
		          .doOnChannelInit((observer, channel, address) -> latch.countDown());
	}

	@Test
	@SuppressWarnings("deprecation")
	void testTcpConfigurationUnsupported_1() {
		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> HttpServer.create()
		                                    .tcpConfiguration(tcp -> tcp.doOnBind(TransportConfig::attributes)));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testTcpConfigurationUnsupported_2() {
		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> HttpServer.create()
		                                    .tcpConfiguration(tcp -> {
		                                        tcp.bind();
		                                        return tcp;
		                                    }));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testTcpConfigurationUnsupported_3() {
		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> HttpServer.create()
		                                    .tcpConfiguration(tcp -> {
		                                        tcp.configuration();
		                                        return tcp;
		                                    }));
	}

	@Test
	void testStatus() {
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
				ServerCookieDecoder.STRICT,
				null,
				false);
		ops.status(status);
		HttpMessage response = ops.newFullBodyMessage(Unpooled.EMPTY_BUFFER);
		assertThat(((FullHttpResponse) response).status().reasonPhrase()).isEqualTo(status.reasonPhrase());
		// "FutureReturnValueIgnored" is suppressed deliberately
		channel.close();
	}

	@Test
	@Timeout(10)
	void testHang() {
			disposableServer =
					createServer()
					          .host("0.0.0.0")
					          .route(r -> r.get("/data", (request, response) -> response.send(Mono.empty())))
					          .bindNow();

			assertThat(disposableServer).isNotNull();
	}

	@Test
	void testSniSupport() throws Exception {
		SelfSignedCertificate defaultCert = new SelfSignedCertificate("default");
		SslContextBuilder defaultSslContextBuilder =
				SslContextBuilder.forServer(defaultCert.certificate(), defaultCert.privateKey());

		SelfSignedCertificate testCert = new SelfSignedCertificate("test.com");
		SslContextBuilder testSslContextBuilder =
				SslContextBuilder.forServer(testCert.certificate(), testCert.privateKey());

		SslContextBuilder clientSslContextBuilder =
				SslContextBuilder.forClient()
				                 .trustManager(InsecureTrustManagerFactory.INSTANCE);

		AtomicReference<String> hostname = new AtomicReference<>();
		disposableServer =
				createServer()
				          .secure(spec -> spec.sslContext(defaultSslContextBuilder)
				                              .defaultConfiguration(TCP)
				                              .addSniMapping("*.test.com", domainSpec -> domainSpec.sslContext(testSslContextBuilder)))
				          .doOnChannelInit((obs, channel, remoteAddress) ->
				              channel.pipeline()
				                     .addAfter(NettyPipeline.SslHandler, "test", new ChannelInboundHandlerAdapter() {
				                         @Override
				                         public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
				                             if (evt instanceof SniCompletionEvent) {
				                                 hostname.set(((SniCompletionEvent) evt).hostname());
				                             }
				                             ctx.fireUserEventTriggered(evt);
				                         }
				                     }))
				          .handle((req, res) -> res.sendString(Mono.just("testSniSupport")))
				          .bindNow();

		createClient(disposableServer::address)
		          .secure(spec -> spec.sslContext(clientSslContextBuilder)
		                              .defaultConfiguration(TCP)
		                              .serverNames(new SNIHostName("test.com")))
		          .get()
		          .uri("/")
		          .responseContent()
		          .aggregate()
		          .block(Duration.ofSeconds(30));

		assertThat(hostname.get()).isNotNull();
		assertThat(hostname.get()).isEqualTo("test.com");
	}

	@Test
	void testIssue1286() throws Exception {
		doTestIssue1286(false, false);
	}

	@Test
	void testIssue1286ErrorResponse() throws Exception {
		doTestIssue1286(false, true);
	}

	@Test
	void testIssue1286ConnectionClose() throws Exception {
		doTestIssue1286(true, false);
	}

	@Test
	void testIssue1286ConnectionCloseErrorResponse() throws Exception {
		doTestIssue1286(true, true);
	}

	private void doTestIssue1286(boolean connectionClose, boolean throwException) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<ByteBuf>> replay = new AtomicReference<>(new ArrayList<>());
		disposableServer =
				createServer()
				          .doOnConnection(conn ->
				                  conn.addHandlerLast(new ChannelInboundHandlerAdapter() {

				                      @Override
				                      public void channelRead(ChannelHandlerContext ctx, Object msg) {
				                          if (msg instanceof ByteBufHolder) {
				                              replay.get().add(((ByteBufHolder) msg).content());
				                          }
				                          else if (msg instanceof ByteBuf) {
				                              replay.get().add((ByteBuf) msg);
				                          }
				                          ctx.fireChannelRead(msg);
				                      }
				                  }))
				          .handle((req, res) -> {
				              res.withConnection(conn -> conn.onTerminate()
				                                             .subscribe(null, t -> latch.countDown(), latch::countDown));
				              if (throwException) {
				                  return Mono.delay(Duration.ofMillis(100))
				                             .flatMap(l -> Mono.error(new RuntimeException("testIssue1286")));
				              }
				              return res.sendString(Mono.delay(Duration.ofMillis(100))
				                                        .flatMap(l -> Mono.just("OK")));
				          })
				          .bindNow();

		HttpClient client = createClient(disposableServer.port());

		if (connectionClose) {
			client = client.headers(h -> h.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE));
		}

		client.post()
		      .uri("/")
		      .sendForm((req, form) -> form.attr("testIssue1286", "testIssue1286"))
		      .responseContent()
		      .aggregate()
		      .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		Mono.delay(Duration.ofMillis(500))
		    .block();
		assertThat(replay.get()).allMatch(buf -> buf.refCnt() == 0);
	}

	@Test
	void testCustomMetricsRecorderWithUriMapper() throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(5);
		List<String> collectedUris = new CopyOnWriteArrayList<>();

		disposableServer =
				createServer()
				          .handle((req, resp) -> resp.sendString(Mono.just("OK")))
				          .metrics(true,
				              () -> new HttpServerMetricsRecorder() {
				                  @Override
				                  public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
				                      collectedUris.add(uri);
				                      latch.countDown();
				                  }

				                  @Override
				                  public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
				                      collectedUris.add(uri);
				                      latch.countDown();
				                  }

				                  @Override
				                  public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
				                      collectedUris.add(uri);
				                      latch.countDown();
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
				                  public void recordDataReceivedTime(String uri, String method, Duration time) {
				                      collectedUris.add(uri);
				                      latch.countDown();
				                  }

				                  @Override
				                  public void recordDataSentTime(String uri, String method, String status, Duration time) {
				                      collectedUris.add(uri);
				                      latch.countDown();
				                  }

				                  @Override
				                  public void recordResponseTime(String uri, String method, String status, Duration time) {
				                      collectedUris.add(uri);
				                      latch.countDown();
				                  }
				              },
				              s -> s.startsWith("/stream/") ? "/stream/{n}" : s)
				          .bindNow();

		HttpClient.create()
		          .get()
		          .uri("http://localhost:" + disposableServer.port() + "/stream/1024")
		          .responseContent()
		          .aggregate()
		          .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(collectedUris).isNotEmpty()
		                         .containsOnly("/stream/{n}");
	}

	@Test
	void testIdleTimeout_NoTimeout() throws Exception {
		doTestIdleTimeout(false);
	}

	@Test
	void testIdleTimeout() throws Exception {
		doTestIdleTimeout(true);
	}

	private void doTestIdleTimeout(boolean applyTimeout) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		HttpServer server =
				createServer()
				          .handle((req, resp) -> {
				              req.withConnection(conn -> conn.onDispose(latch::countDown));
				              return resp.sendString(Mono.just("doTestIdleTimeout"));
				          });

		if (applyTimeout) {
			server = server.idleTimeout(Duration.ofMillis(200));
		}

		disposableServer = server.bindNow(Duration.ofSeconds(30));

		createClient(disposableServer.port())
		          .get()
		          .uri("/")
		          .responseContent()
		          .aggregate()
		          .block(Duration.ofSeconds(30));

		assertThat(latch.await(500, TimeUnit.MILLISECONDS)).isEqualTo(applyTimeout);
	}

	@Test
	void testIdleTimeout_DelayFirstRequest_NoSSL() throws Exception {
		doTestIdleTimeout_DelayFirstRequest(false);
	}

	@Test
	void testIdleTimeout_DelayFirstRequest() throws Exception {
		doTestIdleTimeout_DelayFirstRequest(true);
	}

	private void doTestIdleTimeout_DelayFirstRequest(boolean withSecurity) throws Exception {
		HttpServer server =
				createServer()
				          .idleTimeout(Duration.ofMillis(200))
				          .handle((req, resp) -> resp.send(req.receive().retain()));

		HttpClient client =
				createClient(() -> disposableServer.address())
				          .disableRetry(true);

		if (withSecurity) {
			SelfSignedCertificate cert = new SelfSignedCertificate();
			SslContextBuilder serverCtx = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
			SslContextBuilder clientCtx = SslContextBuilder.forClient()
			                                               .trustManager(InsecureTrustManagerFactory.INSTANCE);
			server = server.secure(spec -> spec.sslContext(serverCtx));
			client = client.secure(spec -> spec.sslContext(clientCtx));
		}

		disposableServer = server.bindNow(Duration.ofSeconds(30));

		client.post()
		      .uri("/")
		      .send((req, out) -> out.sendString(Mono.just("doTestIdleTimeout_DelayFirstRequest")
		                                             .delaySubscription(Duration.ofMillis(500))))
		      .responseContent()
		      .aggregate()
		      .as(StepVerifier::create)
		      .expectErrorMatches(t -> t instanceof IOException || t instanceof AbortedException)
		      .verify(Duration.ofSeconds(30));
	}
}
