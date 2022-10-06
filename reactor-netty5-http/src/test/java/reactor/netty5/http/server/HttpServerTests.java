/*
 * Copyright (c) 2011-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import io.netty5.buffer.Buffer;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.group.ChannelGroup;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.channel.group.DefaultChannelGroup;
import io.netty5.channel.socket.DomainSocketAddress;
import io.netty5.handler.codec.LineBasedFrameDecoder;
import io.netty5.handler.codec.http.DefaultFullHttpRequest;
import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.FullHttpRequest;
import io.netty5.handler.codec.http.FullHttpResponse;
import io.netty5.handler.codec.http.HttpClientCodec;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpContentDecompressor;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http.HttpMessage;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpObjectDecoder;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.HttpServerCodec;
import io.netty5.handler.codec.http.HttpVersion;
import io.netty5.handler.codec.http.HttpUtil;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty5.handler.ssl.SniCompletionEvent;
import io.netty5.handler.ssl.SslCloseCompletionEvent;
import io.netty5.handler.ssl.SslContext;
import io.netty5.handler.ssl.SslContextBuilder;
import io.netty5.handler.ssl.SslHandler;
import io.netty5.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty5.handler.ssl.util.SelfSignedCertificate;
import io.netty5.util.concurrent.SingleThreadEventExecutor;
import io.netty5.util.concurrent.EventExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.netty5.BaseHttpTest;
import reactor.netty5.BufferFlux;
import reactor.netty5.ChannelBindException;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.DisposableServer;
import reactor.netty5.NettyOutbound;
import reactor.netty5.NettyPipeline;
import reactor.netty5.channel.AbortedException;
import reactor.netty5.http.Http11SslContextSpec;
import reactor.netty5.http.Http2SslContextSpec;
import reactor.netty5.http.HttpProtocol;
import reactor.netty5.http.client.HttpClient;
import reactor.netty5.http.client.HttpClientRequest;
import reactor.netty5.http.client.PrematureCloseException;
import reactor.netty5.http.logging.ReactorNettyHttpMessageLogFactory;
import reactor.netty5.resources.ConnectionProvider;
import reactor.netty5.resources.LoopResources;
import reactor.netty5.tcp.SslProvider;
import reactor.netty5.tcp.TcpClient;
import reactor.test.StepVerifier;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;

import javax.net.ssl.SNIHostName;

import static io.netty5.buffer.DefaultBufferAllocators.preferredAllocator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;
import static reactor.netty5.http.server.HttpServerFormDecoderProvider.DEFAULT_FORM_DECODER_SPEC;

/**
 * @author Stephane Maldini
 */
class HttpServerTests extends BaseHttpTest {

	static SelfSignedCertificate ssc;
	static final EventExecutor executor = new SingleThreadEventExecutor();

	ChannelGroup group;

	/**
	 * Server Handler used to send a TLS close_notify after the server last response has been flushed.
	 * The close_notify is sent without closing the connection.
	 */
	final static class SendCloseNotifyAfterLastResponseHandler extends ChannelHandlerAdapter {
		final static String NAME = "handler.send_close_notify_after_response";
		final CountDownLatch latch;

		SendCloseNotifyAfterLastResponseHandler(CountDownLatch latch) {
			this.latch = latch;
		}

		static void register(Connection cnx, CountDownLatch latch) {
			SendCloseNotifyAfterLastResponseHandler handler = new SendCloseNotifyAfterLastResponseHandler(latch);
			cnx.channel().pipeline().addBefore(NettyPipeline.HttpTrafficHandler, NAME, handler);
		}

		@Override
		public io.netty5.util.concurrent.Future<Void> write(ChannelHandlerContext ctx, Object msg) {
			if (msg instanceof LastHttpContent) {
				SslHandler sslHandler = ctx.channel().pipeline().get(SslHandler.class);
				Objects.requireNonNull(sslHandler, "sslHandler not found from pipeline");
				// closeOutbound sends a close_notify but don't close the connection.
				return ctx.write(msg).addListener(future -> sslHandler.closeOutbound().addListener(f -> latch.countDown()));
			}
			return ctx.write(msg);
		}
	}

	/**
	 * Handler used by secured servers which don't want to close client connection when receiving a client close_notify ack.
	 * The handler is placed just before the ReactiveBridge (ChannelOperationsHandler), and will block
	 * any received SslCloseCompletionEvent events. Hence, ChannelOperationsHandler won't get the close_notify ack,
	 * and won't close the channel.
	 */
	final static class IgnoreCloseNotifyHandler extends ChannelHandlerAdapter {
		final static String NAME = "handler.ignore_close_notify";

		static void register(Connection cnx) {
			cnx.channel().pipeline().addBefore(NettyPipeline.ReactiveBridge, NAME, new IgnoreCloseNotifyHandler());
		}

		@Override
		public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
			if (!(evt instanceof SslCloseCompletionEvent) || !((SslCloseCompletionEvent) evt).isSuccess()) {
				ctx.fireChannelInboundEvent(evt);
			}
		}
	}

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@AfterAll
	static void cleanup() throws ExecutionException, InterruptedException, TimeoutException {
		executor.shutdownGracefully()
				.asStage().get(30, TimeUnit.SECONDS);
	}

	@AfterEach
	void tearDown() throws ExecutionException, InterruptedException, TimeoutException {
		if (disposableServer != null) {
			disposableServer.disposeNow();
			disposableServer = null; // avoid to dispose again from BaseHttpTest.disposeServer()
		}
		if (group != null) {
			group.close()
					.asStage().get(30, TimeUnit.SECONDS);
			group = null;
		}
	}

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

		Flux<Buffer> src = Flux.range(0, 3)
		                       .map(n -> preferredAllocator().copyOf(Integer.toString(n)
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

		FullHttpRequest request1 =
				new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/plaintext", preferredAllocator().allocate(0));
		FullHttpRequest request2 = request1.copy();
		FullHttpRequest request3 = request1.copy();

		CountDownLatch latch = new CountDownLatch(6);

		Connection client =
				TcpClient.create()
				         .port(disposableServer.port())
				         .handle((in, out) -> {
				                 in.withConnection(x ->
				                         x.addHandlerFirst(new HttpClientCodec()))
				                   .receiveObject()
				                   .ofType(HttpContent.class)
				                   .as(httpContentFlux ->
				                       BufferFlux.fromInbound(httpContentFlux, preferredAllocator(), o -> ((HttpContent<?>) o).payload()))
				                   .asString()
				                   .log()
				                   .map(Integer::parseInt)
				                   .subscribe(d -> {
				                       for (int x = 0; x < d; x++) {
				                           latch.countDown();
				                       }
				                   });

				                 return out.sendObject(Flux.just(request1, request2, request3))
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
		                                        res.addHandlerLast(new LineBasedFrameDecoder(10)))
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
		                           "0".equals(getHeader(h, "Content-Length")) &&
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
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, "2");
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
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, "2");
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
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, "2");
				                                                return res.send()
				                                                          .then()
				                                                          .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders()));
				                                                })
				                       .route(req -> req.uri().startsWith("/10"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, "0");
				                                                return res.sendString(Mono.just("OK"))
				                                                          .then()
				                                                          .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders()));
				                                                })
				                       .route(req -> req.uri().startsWith("/11"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, "0");
				                                                return res.sendString(Flux.just("OK").hide())
				                                                          .then()
				                                                          .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders()));
				                                                })
				                       .route(req -> req.uri().startsWith("/12"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, "2");
				                                                return res.sendObject(res.alloc().copyOf("OK", Charset.defaultCharset()))
				                                                          .then()
				                                                          .doOnSuccess(aVoid -> sentHeaders.set(res.responseHeaders()));
				                                                })
				                       .route(req -> req.uri().startsWith("/13"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, "0");
				                                                return res.sendObject(res.alloc().copyOf("OK", Charset.defaultCharset()))
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
				              res.responseHeaders().set(HttpHeaderNames.CONTENT_LENGTH, "2");
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
				            CharSequence chunked = t.getT1().get(HttpHeaderNames.TRANSFER_ENCODING);
				            CharSequence cl = t.getT1().get(HttpHeaderNames.CONTENT_LENGTH);
				            if (HttpMethod.GET.equals(method)) {
				                return chunked != null && cl == null && "OK".equals(t.getT2());
				            }
				            else {
				                return chunked == null && cl == null && "NO BODY".equals(t.getT2());
				            }
				        }
				        else if (close) {
				            String connClosed = getHeader(t.getT1(), HttpHeaderNames.CONNECTION);
				            String chunked = getHeader(t.getT1(), HttpHeaderNames.TRANSFER_ENCODING);
				            String cl = getHeader(t.getT1(), HttpHeaderNames.CONTENT_LENGTH);
				            if (HttpMethod.GET.equals(method)) {
				                return "close".equals(connClosed) && chunked == null && cl == null && "OK".equals(t.getT2());
				            }
				            else {
				                return "close".equals(connClosed) && chunked == null && cl == null && "NO BODY".equals(t.getT2());
				            }
				        }
				        else {
				            String chunkedReceived = getHeader(t.getT1(), HttpHeaderNames.TRANSFER_ENCODING);
				            String clReceived = getHeader(t.getT1(), HttpHeaderNames.CONTENT_LENGTH);
				            String chunkedSent = getHeader(sentHeaders.get(), HttpHeaderNames.TRANSFER_ENCODING);
				            String clSent = getHeader(sentHeaders.get(), HttpHeaderNames.CONTENT_LENGTH);
				            if (HttpMethod.GET.equals(method)) {
				                return chunkedReceived == null && chunkedSent == null &&
				                       clReceived != null && clSent != null &&
				                       Integer.parseInt(clReceived) == Integer.parseInt(clSent) &&
				                       "OK".equals(t.getT2());
				            }
				            else {
				                return chunkedReceived == null && chunkedSent == null &&
				                       clReceived != null && clSent != null &&
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
				                     .send(BufferFlux.fromString(Mono.just("bodysample")))
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
		Flux<Buffer> r =
				createClient(disposableServer.port())
				          .doOnResponse((res, c) -> ch.set(c.channel()))
				          .get()
				          .uri("/")
				          .responseContent();

		StepVerifier.create(r)
		            .expectNextCount(2)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		Mono.fromCompletionStage(ch.get().closeFuture().asStage()).block(Duration.ofSeconds(30));
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
				                             .send(BufferFlux.fromString(Mono.just("bodysample")
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
				          .responseSingle((res, bufferMono) -> Mono.just(res.status()));

		StepVerifier.create(status)
		            .expectNextMatches(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE::equals)
		            .expectComplete()
		            .verify();
	}

	@Test
	void httpServerRequestConfigInjectAttributes() {
		AtomicReference<Channel> channelRef = new AtomicReference<>();
		AtomicBoolean validate = new AtomicBoolean();
		AtomicBoolean allowDuplicateContentLengths = new AtomicBoolean();
		disposableServer =
				createServer()
				          .httpRequestDecoder(opt -> opt.maxInitialLineLength(123)
				                                        .maxHeaderSize(456)
				                                        .validateHeaders(false)
				                                        .initialBufferSize(10)
				                                        .allowDuplicateContentLengths(true))
				          .handle((req, resp) -> req.receive().then(resp.sendNotFound()))
				          .doOnConnection(c -> {
				                      channelRef.set(c.channel());
				                      HttpServerCodec codec = c.channel()
				                                               .pipeline()
				                                               .get(HttpServerCodec.class);
				                      HttpObjectDecoder decoder = (HttpObjectDecoder) getValueReflection(codec, "inboundHandler", 1);
				                      validate.set((Boolean) getValueReflection(decoder, "validateHeaders", 2));
				                      allowDuplicateContentLengths.set((Boolean) getValueReflection(decoder, "allowDuplicateContentLengths", 2));
				                  })
				          .bindNow();

		createClient(disposableServer::address)
		          .post()
		          .uri("/")
		          .send(BufferFlux.fromString(Mono.just("bodysample")))
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block();

		assertThat(channelRef.get()).isNotNull();
		assertThat(validate).as("validate headers").isFalse();
		assertThat(allowDuplicateContentLengths).as("allow duplicate Content-Length").isTrue();
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
		Buffer data = preferredAllocator().copyOf("test".getBytes(Charset.defaultCharset())).makeReadOnly();
		Buffer data1 = data.copy(0, data.readableBytes(), true);
		Buffer data2 = data.copy(0, data.readableBytes(), true);
		CountDownLatch latch = new CountDownLatch(1);
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .send(Flux.just(data, data1, data2))
				                 .then()
				                 .doOnCancel(latch::countDown),
				(req, out) -> {
					req.addHeader("Connection", "close");
					return out;
				});
		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(data.isAccessible()).isFalse();
		assertThat(data1.isAccessible()).isFalse();
		assertThat(data2.isAccessible()).isFalse();
	}

	@Test
	void testDropMessageConnectionClose() throws Exception {
		Buffer data = preferredAllocator().copyOf("test", Charset.defaultCharset()).makeReadOnly();
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .sendObject(data),
				(req, out) -> {
					req.addHeader("Connection", "close");
					return out;
				});
		assertThat(data.isAccessible()).isFalse();
	}

	@Test
	void testDropPublisher_1() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		Buffer data = preferredAllocator().copyOf("test".getBytes(Charset.defaultCharset())).makeReadOnly();
		Buffer data1 = data.copy(0, data.readableBytes(), true);
		Buffer data2 = data.copy(0, data.readableBytes(), true);
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .send(Flux.defer(() -> Flux.just(data, data1, data2))
				                           .doFinally(s -> latch.countDown()))
				                 .then(),
				(req, out) -> out);
		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(data.isAccessible()).isFalse();
		assertThat(data1.isAccessible()).isFalse();
		assertThat(data2.isAccessible()).isFalse();
	}

	@Test
	void testDropPublisher_2() throws Exception {
		Buffer data = preferredAllocator().copyOf("test".getBytes(Charset.defaultCharset())).makeReadOnly();
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .send(Mono.just(data))
				                 .then(),
				(req, out) -> out);
		assertThat(data.isAccessible()).isFalse();
	}

	@Test
	void testDropMessage() throws Exception {
		Buffer data = preferredAllocator().copyOf("test".getBytes(Charset.defaultCharset())).makeReadOnly();
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .sendObject(data),
				(req, out) -> out);
		assertThat(data.isAccessible()).isFalse();
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
				          .handle((req, res) -> res.send(req.receive().transferOwnership()))
				          .bindNow(Duration.ofSeconds(30));

		byte[] bytes = "test".getBytes(Charset.defaultCharset());
		String response =
				createClient(disposableServer.port())
				          .headers(h -> h.add("Content-Encoding", "gzip"))
				          .post()
				          .uri("/")
				          .send((req, out) -> out.send(Mono.just(out.alloc().copyOf(compress(bytes)))))
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
				          .doOnConnection(c -> c.addHandlerFirst("custom", new ChannelHandlerAdapter() {
				                      @Override
				                      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
					                      if (msg instanceof HttpRequest request) {
				                              request.headers().add("test", "test");
				                          }
				                          super.channelRead(ctx, msg);
				                      }
				                  }))
				          .handle((req, res) -> res.sendString(
				                  Mono.just(getHeader(req.requestHeaders(), "test", "not found"))))
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
		                  .send(BufferFlux.fromString(Mono.just("test")))
		                  .responseConnection((res, conn) -> {
		                      int status = res.status().code();
		                      conn.dispose();
		                      return Mono.just(status);
		                  }))
		    .blockLast(Duration.ofSeconds(30));
	}

	@Test
	void testExpectErrorWhenConnectionClosed() throws Exception {
		SslContext serverCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();
		AtomicReference<Throwable> error = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				createServer()
				          .secure(spec -> spec.sslContext(serverCtx))
				          .handle((req, res) -> {
					          res.withConnection(conn -> conn.channel().close().addListener(f -> {
						          res.sendString(Flux.just("OK").hide())
								          .then()
								          .doOnError(t -> {
									          error.set(t);
									          latch.countDown();
								          })
								          .subscribe();
					          }));

					          return Mono.never();
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
				.isEqualTo(WebSocketCloseStatus.EMPTY);
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
				.isEqualTo(WebSocketCloseStatus.EMPTY);
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
				.isEqualTo(WebSocketCloseStatus.EMPTY);

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
				.isEqualTo(WebSocketCloseStatus.EMPTY);

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
				new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", preferredAllocator().allocate(0));

		CountDownLatch latch = new CountDownLatch(1);

		Connection client =
				TcpClient.create()
				         .port(disposableServer.port())
				         .handle((in, out) -> {
				             in.withConnection(x -> x.addHandlerFirst(new HttpClientCodec()))
				               .receiveObject()
				               .ofType(HttpContent.class)
				               .as(BufferFlux::fromInbound)
				               // Resource::dispose is deliberately invoked
				               // so that .dispose() in FluxReceive.drainReceiver will fail
				               .subscribe(Buffer::close, t -> latch.countDown(), null);

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
		AtomicReference<Throwable> error = new AtomicReference<>();
		disposableServer =
				createServer()
				          .doOnConnection(conn -> conn.channel().pipeline().addAfter(NettyPipeline.HttpTrafficHandler, null,
				                  new ChannelHandlerAdapter() {

				                      @Override
				                      public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
				                          error.set(cause);
				                          ctx.fireChannelExceptionCaught(cause);
				                      }
				          }))
				          .route(r -> r.put("/1", (req, res) -> req.receive()
				                                                   .then(res.sendString(Mono.just("test"))
				                                                            .then()))
				                       .put("/2", (req, res) -> res.send(req.receive().transferOwnership())))
				          .bindNow();

		doTestDecodingFailureLastHttpContent("PUT /1 HTTP/1.1\r\nHost: a.example.com\r\n" +
				"Transfer-Encoding: chunked\r\n\r\nsomething\r\n\r\n", "400 Bad Request", "connection: close");

		assertThat(error.get()).isNull();

		doTestDecodingFailureLastHttpContent("PUT /2 HTTP/1.1\r\nHost: a.example.com\r\n" +
				"Transfer-Encoding: chunked\r\n\r\nsomething\r\n\r\n", "200 OK");

		assertThat(error.get()).isNull();
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
		group = new DefaultChannelGroup(executor);
		disposableServer =
				createServer()
				          .runOn(loop)
				          .doOnConnection(c -> {
				              c.onDispose().subscribe(null, null, latch2::countDown);
				              latch1.countDown();
				          })
				          // Register a channel group, when invoking disposeNow()
				          // the implementation will wait for the active requests to finish
				          .channelGroup(group)
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
	void testHttpServerWithDomainSockets_HTTP2() {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestHttpServerWithDomainSockets(
				HttpServer.create().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
				HttpClient.create().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
				"https");
	}

	private void doTestHttpServerWithDomainSockets(HttpServer server, HttpClient client, String expectedScheme) {
		disposableServer =
				server.bindAddress(HttpServerTests::newDomainSocketAddress)
				      .wiretap(true)
				      .handle((req, res) -> {
				          req.withConnection(conn -> {
				              assertThat(req.hostAddress()).isNull();
				              assertThat(req.remoteAddress()).isNull();
				              assertThat(req.scheme()).isNotNull().isEqualTo(expectedScheme);
				          });
				          assertThat(getHeader(req.requestHeaders(), HttpHeaderNames.HOST)).isEqualTo("localhost");
				          return res.send(req.receive().transferOwnership());
				      })
				      .bindNow();

		String response =
				client.remoteAddress(disposableServer::address)
				      .wiretap(true)
				      .post()
				      .uri("/")
				      .send(BufferFlux.fromString(Flux.just("1", "2", "3")))
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("123");
	}

	private static DomainSocketAddress newDomainSocketAddress() {
		try {
			File tempFile = Files.createTempFile("HttpServerTests", "UDS").toFile();
			assertThat(tempFile.delete()).isTrue();
			tempFile.deleteOnExit();
			return new DomainSocketAddress(tempFile);
		}
		catch (Exception e) {
			throw new RuntimeException("Error creating a  temporary file", e);
		}
	}

	@Test
	void testStatus() {
		doTestStatus(HttpResponseStatus.OK);
		doTestStatus(new HttpResponseStatus(200, "Some custom reason phrase for 200 status code"));
	}

	private void doTestStatus(HttpResponseStatus status) {
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpServerOperations ops = new HttpServerOperations(
				Connection.from(channel),
				ConnectionObserver.emptyListener(),
				new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
				null,
				null,
				DEFAULT_FORM_DECODER_SPEC,
				ReactorNettyHttpMessageLogFactory.INSTANCE,
				null,
				false);
		ops.status(status);
		try (Buffer buffer = channel.bufferAllocator().allocate(0)) {
			HttpMessage response = ops.newFullBodyMessage(buffer);
			assertThat(((FullHttpResponse) response).status().reasonPhrase()).isEqualTo(status.reasonPhrase());
		}
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
		Http11SslContextSpec defaultSslContextBuilder =
				Http11SslContextSpec.forServer(defaultCert.certificate(), defaultCert.privateKey());

		SelfSignedCertificate testCert = new SelfSignedCertificate("test.com");
		Http11SslContextSpec testSslContextBuilder =
				Http11SslContextSpec.forServer(testCert.certificate(), testCert.privateKey());

		Http11SslContextSpec clientSslContextBuilder =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		AtomicReference<String> hostname = new AtomicReference<>();
		disposableServer =
				createServer()
				          .secure(spec -> spec.sslContext(defaultSslContextBuilder)
				                              .addSniMapping("*.test.com", domainSpec -> domainSpec.sslContext(testSslContextBuilder)))
				          .doOnChannelInit((obs, channel, remoteAddress) ->
				              channel.pipeline()
				                     .addAfter(NettyPipeline.SslHandler, "test", new ChannelHandlerAdapter() {
				                         @Override
				                         public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
					                         if (evt instanceof SniCompletionEvent sniCompletionEvent) {
				                                 hostname.set(sniCompletionEvent.hostname());
				                             }
				                             ctx.fireChannelInboundEvent(evt);
				                         }
				                     }))
				          .handle((req, res) -> res.sendString(Mono.just("testSniSupport")))
				          .bindNow();

		createClient(disposableServer::address)
		          .secure(spec -> spec.sslContext(clientSslContextBuilder)
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
	void testSniSupportAsyncMappings() throws Exception {
		SelfSignedCertificate defaultCert = new SelfSignedCertificate("default");
		Http11SslContextSpec defaultSslContextBuilder =
				Http11SslContextSpec.forServer(defaultCert.certificate(), defaultCert.privateKey());

		SelfSignedCertificate testCert = new SelfSignedCertificate("test.com");
		Http11SslContextSpec testSslContextBuilder =
				Http11SslContextSpec.forServer(testCert.certificate(), testCert.privateKey());
		SslProvider testSslProvider = SslProvider.builder().sslContext(testSslContextBuilder).build();

		Http11SslContextSpec clientSslContextBuilder =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		AtomicReference<String> hostname = new AtomicReference<>();
		disposableServer =
				createServer()
				          .secure(spec -> spec.sslContext(defaultSslContextBuilder)
				                              .setSniAsyncMappings((input, promise) -> promise.setSuccess(testSslProvider).asFuture()))
				          .doOnChannelInit((obs, channel, remoteAddress) ->
				              channel.pipeline()
				                     .addAfter(NettyPipeline.SslHandler, "test", new ChannelHandlerAdapter() {
				                         @Override
				                         public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
					                         if (evt instanceof SniCompletionEvent sniCompletionEvent) {
				                                 hostname.set(sniCompletionEvent.hostname());
				                             }
				                                 ctx.fireChannelInboundEvent(evt);
				                             }
				                         }))
				          .handle((req, res) -> res.sendString(Mono.just("testSniSupport")))
				          .bindNow();

		createClient(disposableServer::address)
		          .secure(spec -> spec.sslContext(clientSslContextBuilder)
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
	void testIdleTimeout_DelayFirstRequest_NoSSL() {
		doTestIdleTimeout_DelayFirstRequest(false);
	}

	@Test
	void testIdleTimeout_DelayFirstRequest() {
		doTestIdleTimeout_DelayFirstRequest(true);
	}

	private void doTestIdleTimeout_DelayFirstRequest(boolean withSecurity) {
		HttpServer server =
				createServer()
				          .idleTimeout(Duration.ofMillis(200))
				          .handle((req, resp) -> resp.send(req.receive().transferOwnership()));

		HttpClient client =
				createClient(() -> disposableServer.address())
				          .disableRetry(true);

		if (withSecurity) {
			Http11SslContextSpec serverCtx = Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
			Http11SslContextSpec clientCtx =
					Http11SslContextSpec.forClient()
					                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
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

	@Test
	void testServerSelectorChannelClosedOnServerDispose() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, resp) ->
				                resp.sendString(Mono.just("testServerSelectorChannelClosedOnServerDispose")))
				        .bindNow();

		CountDownLatch latch = new CountDownLatch(1);
		disposableServer.onDispose(latch::countDown);

		try {
			createClient(disposableServer.port())
			        .get()
			        .uri("/")
			        .responseContent()
			        .aggregate()
			        .asString()
			        .as(StepVerifier::create)
			        .expectNext("testServerSelectorChannelClosedOnServerDispose")
			        .expectComplete()
			        .verify(Duration.ofSeconds(5));
		}
		finally {
			assertThat(disposableServer.isDisposed()).as("Server should not be disposed").isFalse();
			assertThat(disposableServer.channel().isActive()).as("Channel should be active").isTrue();
			assertThat(disposableServer.channel().isOpen()).as("Channel should be opened").isTrue();

			disposableServer.disposeNow();

			assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch await").isTrue();
			assertThat(disposableServer.isDisposed()).as("Server should be disposed").isTrue();
			assertThat(disposableServer.channel().isActive()).as("Channel should not be active").isFalse();
			assertThat(disposableServer.channel().isOpen()).as("Channel should not be opened").isFalse();
		}
	}

	@Test
	void testConnectionClosePropagatedAsError_CL() throws Exception {
		doTestConnectionClosePropagatedAsError(
				"POST http://%s/ HTTP/1.1\r\nHost: %s\r\nContent-Length: 10\r\n\r\nhello");
	}

	@Test
	void testConnectionClosePropagatedAsError_TE() throws Exception {
		doTestConnectionClosePropagatedAsError(
				"POST http://%s/ HTTP/1.1\r\nHost: %s\r\nTransfer-Encoding: chunked\r\n\r\n10\r\nhello");
	}

	@Test
	void testMatchRouteInConfiguredOrder() {
		HttpServerRoutes serverRoutes = HttpServerRoutes.newRoutes()
				.get("/yes/{value}", (request, response) -> response.sendString(Mono.just("/yes/{value}")))
				.get("/yes/value", (request, response) -> response.sendString(Mono.just("/yes/value")));

		disposableServer = HttpServer.create().handle(serverRoutes).bindNow();

		StepVerifier.create(createClient(disposableServer.port()).get().uri("/yes/value")
				.responseSingle((response, bufferMono) -> bufferMono.asString()))
				.expectNext("/yes/{value}")
				.verifyComplete();
	}

	@Test
	void testUseComparatorOrderRoutes() {
		HttpServerRoutes serverRoutes = HttpServerRoutes.newRoutes()
				.get("/yes/{value}", (request, response) -> response.sendString(Mono.just("/yes/{value}")))
				.get("/yes/value", (request, response) -> response.sendString(Mono.just("/yes/value")));

		disposableServer = HttpServer.create().handle(serverRoutes.comparator(comparator)).bindNow();

		StepVerifier.create(createClient(disposableServer.port()).get().uri("/yes/value")
				.responseSingle((response, bufferMono) -> bufferMono.asString()))
				.expectNext("/yes/value")
				.verifyComplete();
	}

	@Test
	void testOverrideRouteOrder() {
		HttpServerRoutes serverRoutes = HttpServerRoutes.newRoutes()
				.get("/yes/{value}", (request, response) -> response.sendString(Mono.just("/yes/{value}")))
				.get("/yes/value", (request, response) -> response.sendString(Mono.just("/yes/value")));

		try {
			disposableServer = HttpServer.create().handle(serverRoutes.comparator(comparator)).bindNow();

			StepVerifier.create(createClient(disposableServer.port()).get().uri("/yes/value")
					.responseSingle((response, bufferMono) -> bufferMono.asString()))
					.expectNext("/yes/value")
					.verifyComplete();
		}
		finally {
			if (disposableServer != null) {
				disposableServer.disposeNow();
			}
		}

		disposableServer = HttpServer.create().handle(serverRoutes.comparator(comparator).comparator(comparator.reversed()))
				.bindNow();

		StepVerifier.create(createClient(disposableServer.port()).get().uri("/yes/value")
				.responseSingle((response, bufferMono) -> bufferMono.asString()))
				.expectNext("/yes/{value}")
				.verifyComplete();
	}

	@Test
	void testUseRoutesConfiguredOrder() {
		HttpServerRoutes serverRoutes = HttpServerRoutes.newRoutes()
				.get("/yes/{value}", (request, response) -> response.sendString(Mono.just("/yes/{value}")))
				.get("/yes/value", (request, response) -> response.sendString(Mono.just("/yes/value")));

		try {
			disposableServer = HttpServer.create().handle(serverRoutes.comparator(comparator)).bindNow();

			StepVerifier.create(createClient(disposableServer.port()).get().uri("/yes/value")
					.responseSingle((response, bufferMono) -> bufferMono.asString()))
					.expectNext("/yes/value")
					.verifyComplete();
		}
		finally {
			if (disposableServer != null) {
				disposableServer.disposeNow();
			}
		}

		disposableServer = HttpServer.create().handle(serverRoutes.comparator(comparator).noComparator())
				.bindNow();

		StepVerifier.create(createClient(disposableServer.port()).get().uri("/yes/value")
				.responseSingle((response, bufferMono) -> bufferMono.asString()))
				.expectNext("/yes/{value}")
				.verifyComplete();
	}

	private static final Comparator<HttpRouteHandlerMetadata> comparator = (o1, o2) -> {
		if (o1.getPath().contains("{")) {
			return 1;
		}
		else if (o1.getPath().contains("{") && o2.getPath().contains("{")) {
			return 0;
		}
		return -1;
	};

	private void doTestConnectionClosePropagatedAsError(String request) throws Exception {
		AtomicReference<Throwable> error = new AtomicReference<>();
		CountDownLatch msgLatch = new CountDownLatch(1);
		CountDownLatch errLatch = new CountDownLatch(1);
		disposableServer =
				createServer()
				        .handle((req, res) -> req.receive()
				                                 .doOnNext(b -> msgLatch.countDown())
				                                 .doOnError(t -> {
				                                     error.set(t);
					                                 errLatch.countDown();
				                                 })
				                                 .then(res.send()))
				        .bindNow();

		int port = disposableServer.port();
		Connection connection =
				TcpClient.create()
				         .remoteAddress(disposableServer::address)
				         .wiretap(true)
				         .connectNow();

		String address = HttpUtil.formatHostnameForHttp((InetSocketAddress) disposableServer.address()) + ":" + port;
		connection.outbound()
		          .sendString(Mono.just(String.format(request, address, address)))
		          .then()
		          .subscribe();

		assertThat(msgLatch.await(5, TimeUnit.SECONDS)).as("Wait for the first message").isTrue();

		connection.dispose();

		assertThat(errLatch.await(5, TimeUnit.SECONDS)).as("Wait for the close connection error").isTrue();
		assertThat(error.get()).isNotNull()
				.isInstanceOf(AbortedException.class)
				.hasMessage("Connection has been closed");
	}

	@Test
	void testRemoveRoutes() {
		HttpServerRoutes serverRoutes = HttpServerRoutes.newRoutes()
				.get("/route1", (request, response) -> response.sendString(Mono.just("/route1")))
				.get("/route2", (request, response) -> response.sendString(Mono.just("/route2")));

		try {
			disposableServer = HttpServer.create().handle(serverRoutes).bindNow();

			StepVerifier.create(createClient(disposableServer.port()).get().uri("/route1")
					.responseSingle((response, bufferMono) -> bufferMono.asString()))
					.expectNext("/route1")
					.verifyComplete();

			StepVerifier.create(createClient(disposableServer.port()).get().uri("/route2")
					.responseSingle((response, bufferMono) -> bufferMono.asString()))
					.expectNext("/route2")
					.verifyComplete();
		}
		finally {
			if (disposableServer != null) {
				disposableServer.disposeNow();
			}
		}

		HttpServerRoutes serverRoutes1 = serverRoutes.removeIf(metadata -> Objects.equals(metadata.getPath(), "/route1")
				&& metadata.getMethod().equals(HttpMethod.GET));

		disposableServer = HttpServer.create().handle(serverRoutes1)
				.bindNow();

		StepVerifier.create(createClient(disposableServer.port()).get().uri("/route1")
				.response())
				.expectNextMatches(response -> response.status().equals(HttpResponseStatus.NOT_FOUND))
				.verifyComplete();

		StepVerifier.create(createClient(disposableServer.port()).get().uri("/route2")
				.responseSingle((response, bufferMono) -> bufferMono.asString()))
				.expectNext("/route2")
				.verifyComplete();
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(ints = {-1, 1, 2})
	void testMaxKeepAliveRequests(int maxKeepAliveRequests) {
		HttpServer server = createServer().handle((req, res) -> res.sendString(Mono.just("testMaxKeepAliveRequests")));
		assertThat(server.configuration().maxKeepAliveRequests()).isEqualTo(-1);

		server = server.maxKeepAliveRequests(maxKeepAliveRequests);
		assertThat(server.configuration().maxKeepAliveRequests()).isEqualTo(maxKeepAliveRequests);

		disposableServer = server.bindNow();

		ConnectionProvider provider = ConnectionProvider.create("testMaxKeepAliveRequests", 1);
		HttpClient client = createClient(provider, disposableServer.port());
		Flux.range(0, 2)
		    .flatMap(i ->
		        client.get()
		              .uri("/")
		              .responseSingle((res, bytes) ->
		                  bytes.asString()
		                       .zipWith(Mono.just(getHeader(res.responseHeaders(), HttpHeaderNames.CONNECTION, "persistent")))))
		    .collectList()
		    .as(StepVerifier::create)
		    .expectNextMatches(l -> {
		        boolean result = l.size() == 2 &&
		                "testMaxKeepAliveRequests".equals(l.get(0).getT1()) &&
		                "testMaxKeepAliveRequests".equals(l.get(1).getT1());

		        if (maxKeepAliveRequests == -1) {
		            return result &&
		                    "persistent".equals(l.get(0).getT2()) &&
		                    "persistent".equals(l.get(1).getT2());
		        }
		        else if (maxKeepAliveRequests == 1) {
		            return result &&
		                    "close".equals(l.get(0).getT2()) &&
		                    "close".equals(l.get(1).getT2());
		        }
		        else if (maxKeepAliveRequests == 2) {
		            return result &&
		                    "persistent".equals(l.get(0).getT2()) &&
		                    "close".equals(l.get(1).getT2());
		        }
		        return false;
		    })
		    .expectComplete()
		    .verify(Duration.ofSeconds(5));

		provider.disposeLater().block(Duration.ofSeconds(5));
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(ints = {-2, 0})
	void testMaxKeepAliveRequestsBadValues(int maxKeepAliveRequests) {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> createServer().maxKeepAliveRequests(maxKeepAliveRequests))
				.withMessage("maxKeepAliveRequests must be positive or -1");
	}

	@Test
	void testIsFormUrlencodedWithCharset() {
		doTestIsFormUrlencoded("application/x-www-form-urlencoded;charset=UTF-8", true);
		doTestIsFormUrlencoded("application/x-www-form-urlencoded ;charset=UTF-8", true);
	}

	@Test
	void testIsFormUrlencodedWithoutCharset() {
		doTestIsFormUrlencoded("application/x-www-form-urlencoded", true);
	}

	@Test
	void testIsNotFormUrlencoded() {
		doTestIsFormUrlencoded("", false);
		doTestIsFormUrlencoded("application/json", false);
		doTestIsFormUrlencoded("application/x-www-form-urlencoded-bad", false);
	}

	private void doTestIsFormUrlencoded(String headerValue, boolean expectation) {
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
		request.headers().set(HttpHeaderNames.CONTENT_TYPE, headerValue);
		HttpServerOperations ops = new HttpServerOperations(
				Connection.from(channel),
				ConnectionObserver.emptyListener(),
				request,
				null,
				null,
				DEFAULT_FORM_DECODER_SPEC,
				ReactorNettyHttpMessageLogFactory.INSTANCE,
				null,
				false);
		assertThat(ops.isFormUrlencoded()).isEqualTo(expectation);
		channel.close();
	}

	/**
	 * This test verifies if server h2 streams are closed properly when the server does not consume client post data chunks
	 */
	@ParameterizedTest
	@MethodSource("h2cCompatibleCombinations")
	void testIssue1978H2CNoDelay(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) throws Exception {
		doTestIssue1978(serverProtocols, clientProtocols, null, null, 0, 0);
	}

	@ParameterizedTest
	@MethodSource("h2cCompatibleCombinations")
	void testIssue1978H2CWithDelay(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) throws Exception {
		doTestIssue1978(serverProtocols, clientProtocols, null, null, 50, 20);
	}

	/**
	 * This test verifies if server h2 streams are closed properly when the server does not consume client post data chunks
	 */
	@ParameterizedTest
	@MethodSource("h2CompatibleCombinations")
	void testIssue1978H2NoDelay(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1978(serverProtocols, clientProtocols, serverCtx, clientCtx, 0, 0);
	}

	@ParameterizedTest
	@MethodSource("h2CompatibleCombinations")
	void testIssue1978H2WithDelay(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1978(serverProtocols, clientProtocols, serverCtx, clientCtx, 50, 20);
	}

	private void doTestIssue1978(
			HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx,
			long serverDelay, long clientDelay) throws Exception {
		int count = 5;
		CountDownLatch latch = new CountDownLatch(count);

		Mono<String> mono = Mono.just("testIssue1978");
		Mono<String> serverResponse = serverDelay == 0 ? mono : mono.delayElement(Duration.ofMillis(serverDelay));

		HttpServer mainServer =
				HttpServer.create()
				          .protocol(serverProtocols)
				          .httpRequestDecoder(spec -> spec.h2cMaxContentLength(8 * 1024));
		HttpServer server = serverCtx != null ?
				mainServer.secure(sslContextSpec -> sslContextSpec.sslContext(serverCtx)) : mainServer;

		disposableServer =
				server.handle((req, res) -> {
				          req.withConnection(conn -> conn.channel().closeFuture().addListener(f -> latch.countDown()));
				          return res.sendString(serverResponse);
				      })
				      .bindNow();

		byte[] content = new byte[1024];
		Random rndm = new Random();
		rndm.nextBytes(content);
		String strContent = new String(content, Charset.defaultCharset());

		Flux<String> flux = Flux.just(strContent, strContent, strContent, strContent);
		Flux<String> clientRequest = clientDelay == 0 ? flux : flux.delayElements(Duration.ofMillis(clientDelay));

		HttpClient mainClient = HttpClient.create().port(disposableServer.port()).protocol(clientProtocols);
		HttpClient client = clientCtx != null ?
				mainClient.secure(sslContextSpec -> sslContextSpec.sslContext(clientCtx)) : mainClient;

		Flux.range(0, count)
		    .flatMap(i ->
		        client.post()
		              .uri("/")
		              .send(BufferFlux.fromString(clientRequest))
		              .responseContent()
		              .aggregate()
		              .asString())
		    .blockLast(Duration.ofSeconds(10));

		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	/**
	 * The test simulates a situation where a connection is idle and available in the client connection pool,
	 * and then the client receives a close_notify, but the server has not yet closed the connection.
	 * In this case, the connection should be closed immediately and removed from the pool, in order to avoid
	 * any "SslClosedEngineException: SSLEngine closed already exception" the next time the connection will be
	 * acquired and written.
	 * So, in the test, a secured server responds to a first client request, and when the response is flushed, it sends a
	 * close_notify to the client without closing the connection.
	 * The first client should get its response OK, but when receiving the close_notify, it should immediately
	 * close the connection, which should not re-enter into the pool again.
	 * The next client request will work because the previous connection should have been closed.
	 */
	@Test
	void test2498_close_notify_after_response_two_clients() throws Exception {
		SslContext serverCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
				.build();

		// Ensure that the server has sent the close_notify, and the client connection is closed after the 1st response.
		CountDownLatch latch = new CountDownLatch(2);

		disposableServer = createServer()
				.secure(spec -> spec.sslContext(serverCtx))
				.doOnConnection(cnx -> {
					// will send a close_notify after the last response is sent, but won't close the connection
					SendCloseNotifyAfterLastResponseHandler.register(cnx, latch);
					// avoid closing the connection when the server receives the close_notify ack from the client
					IgnoreCloseNotifyHandler.register(cnx);
				})
				.handle((req, res) -> {
					return res.sendString(Mono.just("test"));
				})
				.bindNow();

		// create the client
		SslContext clientCtx = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE)
				.build();

		HttpClient client =  createClient(disposableServer::address)
				.secure(spec -> spec.sslContext(clientCtx));

		// send a first request
		String resp = client
				.doOnConnected(cnx -> cnx.channel().closeFuture().addListener(l -> latch.countDown()))
				.get()
				.uri("/")
				.responseContent()
				.aggregate()
				.asString()
				.block(Duration.ofSeconds(30));

		assertThat(resp).isEqualTo("test");

		// double check if close_notify was sent by server and if the client channel has been closed
		assertThat(latch.await(40, TimeUnit.SECONDS)).as("latch await").isTrue();

		// send a new request, which should succeed because at reception of previous close_notify we should have
		// immediately closed the connection, else, if the connection is reused here, we would then get a
		// "SslClosedEngineException: SSLEngine closed already" exception

		String resp2 = client
				.get()
				.uri("/")
				.responseContent()
				.aggregate()
				.asString()
				.block(Duration.ofSeconds(30));
		assertThat(resp2).isEqualTo("test");
	}

	/**
	 * The test simulates a situation where the client has fully written its request to a connection,
	 * but while waiting for the response, then a close_notify is received, and the server has not
	 * closed the connection.
	 *
	 * The client should then be aborted with a PrematureCloseException.
	 */
	@Test
	void test2498_close_notify_on_request() throws Exception {
		SslContext serverCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
				.build();

		CountDownLatch latch = new CountDownLatch(1);

		disposableServer = createServer()
				.secure(spec -> spec.sslContext(serverCtx))
				// avoid closing the connection when the server receives the close_notify ack from the client
				.doOnConnection(IgnoreCloseNotifyHandler::register)
				.handle((req, res) -> {
					req.receive()
							.aggregate()
							.subscribe(request -> req.withConnection(c -> {
								SslHandler sslHandler = c.channel().pipeline().get(SslHandler.class);
								Objects.requireNonNull(sslHandler, "sslHandler not found from pipeline");
								// send a close_notify but do not close the connection
								sslHandler.closeOutbound().addListener(future -> latch.countDown());
							}));
					return Mono.never();
				})
				.bindNow();

		// create the client, which should be aborted since the server responds with a close_notify
		Flux<String> postFlux = Flux.just("content1", "content2", "content3", "content4")
				.delayElements(Duration.ofMillis(10));

		SslContext clientCtx = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE)
				.build();

		createClient(disposableServer::address)
				.secure(spec -> spec.sslContext(clientCtx))
				.post()
				.send(BufferFlux.fromString(postFlux))
				.uri("/")
				.responseContent()
				.aggregate()
				.as(StepVerifier::create)
				.expectErrorMatches(t -> t instanceof PrematureCloseException || t instanceof AbortedException)
				.verify(Duration.ofSeconds(40));

		// double check if the server has sent its close_notify
		assertThat(latch.await(40, TimeUnit.SECONDS)).as("latch await").isTrue();
	}

	/**
	 * The test simulates a situation where the client is receiving a close_notify while
	 * writing request body parts to the connection.
	 * The server immediately sends a close_notify when the client is connecting.
	 * the client request is not consumed and the client connection is not closed.
	 * While writing the request body parts, the client should be aborted with a
	 * PrematureCloseException or an AbortedException, but not with an
	 * "SslClosedEngineException: SSLEngine closed already" exception.
	 */
	@Test
	void test2498_close_notify_on_connect() throws Exception {
		SslContext serverCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
				.build();

		CountDownLatch latch = new CountDownLatch(1);

		disposableServer = createServer()
				.secure(spec -> spec.sslContext(serverCtx))
				.doOnConnection(cnx -> {
					// avoid closing the client connection when receiving the close_notify ack from client
					IgnoreCloseNotifyHandler.register(cnx);
					// sends a close_notify immediately, without closing the connection
					SslHandler sslHandler = cnx.channel().pipeline().get(SslHandler.class);
					Objects.requireNonNull(sslHandler, "sslHandler not found from pipeline");
					sslHandler.closeOutbound().addListener(future -> latch.countDown());
				})
				.handle((req, res) -> Mono.never())
				.bindNow();

		// create the client, which should be aborted since the server responds with a close_notify
		Flux<String> postFlux = Flux.range(0, 100)
				.map(count -> "content" + count)
				.delayElements(Duration.ofMillis(100));

		SslContext clientCtx = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE)
				.build();

		createClient(disposableServer::address)
				.secure(spec -> spec.sslContext(clientCtx))
				.post()
				.send(BufferFlux.fromString(postFlux))
				.uri("/")
				.responseContent()
				.aggregate()
				.as(StepVerifier::create)
				.expectErrorMatches(t -> t instanceof PrematureCloseException || t instanceof AbortedException)
				.verify(Duration.ofSeconds(40));

		// double check if the server has sent its close_notify
		assertThat(latch.await(40, TimeUnit.SECONDS)).as("latch await").isTrue();
	}
}
