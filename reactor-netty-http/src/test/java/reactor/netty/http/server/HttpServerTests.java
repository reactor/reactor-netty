/*
 * Copyright (c) 2011-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.security.cert.CertificateException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FixedRecvByteBufAllocator;
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
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.SniCompletionEvent;
import io.netty.handler.ssl.SslCloseCompletionEvent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufFlux;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;
import reactor.netty.LogTracker;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientMetricsRecorder;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.http.client.PrematureCloseException;
import reactor.netty.http.logging.ReactorNettyHttpMessageLogFactory;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpServer;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.TransportConfig;
import reactor.test.StepVerifier;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;

import javax.net.ssl.SNIHostName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assumptions.assumeThat;
import static reactor.netty.http.server.HttpServerFormDecoderProvider.DEFAULT_FORM_DECODER_SPEC;
import static reactor.netty.http.server.ConnectionInfo.DEFAULT_HOST_NAME;
import static reactor.netty.http.server.ConnectionInfo.DEFAULT_HTTP_PORT;
import static reactor.netty.resources.LoopResources.DEFAULT_SHUTDOWN_TIMEOUT;

/**
 * This test class verifies {@link HttpServer}.
 *
 * @author Stephane Maldini
 */
class HttpServerTests extends BaseHttpTest {

	static SelfSignedCertificate ssc;
	static final EventExecutor executor = new DefaultEventExecutor();
	static final Logger log = Loggers.getLogger(HttpServerTests.class);
	static final String DATA_STRING = String.join("", Collections.nCopies(128, "X"));
	static final byte[] DATA = DATA_STRING.getBytes(Charset.defaultCharset());
	ChannelGroup group;

	/**
	 * Server Handler used to send a TLS close_notify after the server last response has been flushed.
	 * The close_notify is sent without closing the connection.
	 */
	static final class SendCloseNotifyAfterLastResponseHandler extends ChannelOutboundHandlerAdapter {
		static final String NAME = "handler.send_close_notify_after_response";
		final CountDownLatch latch;

		SendCloseNotifyAfterLastResponseHandler(CountDownLatch latch) {
			this.latch = latch;
		}

		static void register(Connection cnx, CountDownLatch latch) {
			SendCloseNotifyAfterLastResponseHandler handler = new SendCloseNotifyAfterLastResponseHandler(latch);
			cnx.channel().pipeline().addBefore(NettyPipeline.HttpTrafficHandler, NAME, handler);
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			if (msg instanceof LastHttpContent) {
				SslHandler sslHandler = ctx.channel().pipeline().get(SslHandler.class);
				Objects.requireNonNull(sslHandler, "sslHandler not found from pipeline");
				// closeOutbound sends a close_notify but don't close the connection.
				promise.addListener(future -> sslHandler.closeOutbound().addListener(f -> latch.countDown()));
			}
			ctx.write(msg, promise);
		}
	}

	/**
	 * Handler used by secured servers which don't want to close client connection when receiving a client close_notify ack.
	 * The handler is placed just before the ReactiveBridge (ChannelOperationsHandler), and will block
	 * any received SslCloseCompletionEvent events. Hence, ChannelOperationsHandler won't get the close_notify ack,
	 * and won't close the channel.
	 */
	static final class IgnoreCloseNotifyHandler extends ChannelInboundHandlerAdapter {
		static final String NAME = "handler.ignore_close_notify";

		static void register(Connection cnx) {
			cnx.channel().pipeline().addBefore(NettyPipeline.ReactiveBridge, NAME, new IgnoreCloseNotifyHandler());
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
			if (!(evt instanceof SslCloseCompletionEvent) || !((SslCloseCompletionEvent) evt).isSuccess()) {
				ctx.fireUserEventTriggered(evt);
			}
		}
	}

	/**
	 * Handler used to delay a bit outgoing HTTP/2 server responses. This handler will be placed
	 * at the head of the server channel pipeline.
	 */
	static final class DelayH2FlushHandler extends ChannelOutboundHandlerAdapter {
		static final String NAME = "handler.h2flush_delay";
		static final DelayH2FlushHandler INSTANCE = new DelayH2FlushHandler();

		static void register(Connection cnx) {
			Channel channel = cnx.channel();
			assertThat(channel).isInstanceOf(Http2StreamChannel.class);
			Channel parent = cnx.channel().parent();
			if (parent.pipeline().get(NAME) == null) {
				parent.pipeline().addFirst(NAME, INSTANCE);
			}
		}

		@Override
		public boolean isSharable() {
			return true;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void flush(ChannelHandlerContext ctx) {
			ctx.executor().schedule(ctx::flush, 1, TimeUnit.MILLISECONDS);
		}
	}

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@AfterAll
	static void cleanup() throws ExecutionException, InterruptedException, TimeoutException {
		executor.shutdownGracefully()
				.get(30, TimeUnit.SECONDS);
	}

	@AfterEach
	void tearDown() throws ExecutionException, InterruptedException, TimeoutException {
		if (disposableServer != null) {
			disposableServer.disposeNow();
			disposableServer = null; // avoid to dispose again from BaseHttpTest.disposeServer()
		}
		if (group != null) {
			group.close()
					.get(30, TimeUnit.SECONDS);
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
		    .block(Duration.ofSeconds(5));
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
			                 .block(Duration.ofSeconds(5));

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
			                 .block(Duration.ofSeconds(5));

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
				          .block(Duration.ofSeconds(5));
		assertThat(code).isEqualTo(500);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void httpPipelining(boolean enableAccessLog) throws Exception {
		AtomicInteger i = new AtomicInteger();

		disposableServer = createServer()
		                             .accessLog(enableAccessLog)
		                             .route(r -> r.get("/plaintext", (req, resp) ->
		                                     resp.header(HttpHeaderNames.CONTENT_LENGTH, "1")
		                                         .sendString(Mono.just(i.incrementAndGet())
		                                                         .flatMap(d ->
		                                                                 Mono.delay(Duration.ofSeconds(4 - d))
		                                                                     .map(x -> d + "\n")))))
		                             .bindNow();

		DefaultFullHttpRequest request =
				new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/plaintext");

		DefaultFullHttpRequest requestNotFound =
				new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/not_found");

		CountDownLatch latch = new CountDownLatch(10);

		String okMessage = "GET /plaintext HTTP/1.1\" 200";
		String notFoundMessage = "GET /not_found HTTP/1.1\" 404";
		try (LogTracker logTracker = new LogTracker("reactor.netty.http.server.AccessLog", 4, okMessage, notFoundMessage)) {
			Connection client =
					TcpClient.create()
					         .port(disposableServer.port())
					         .handle((in, out) -> {
					                 in.withConnection(x ->
					                         x.addHandlerFirst(new HttpClientCodec()))
					                   .receiveObject()
					                   .map(o -> {
					                       if (o == LastHttpContent.EMPTY_LAST_CONTENT) {
					                           return new DefaultHttpContent(
					                               Unpooled.wrappedBuffer((i.incrementAndGet() + "").getBytes(Charset.defaultCharset())));
					                       }
					                       return o;
					                   })
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
					                                                 request.retain(),
					                                                 requestNotFound))
					                           .neverComplete();
					         })
					         .wiretap(true)
					         .connectNow();

			assertThat(latch.await(45, TimeUnit.SECONDS)).as("latch await").isTrue();

			client.disposeNow();

			if (enableAccessLog) {
				assertThat(logTracker.latch.await(5, TimeUnit.SECONDS)).isTrue();

				assertThat(logTracker.actualMessages).hasSize(4);
				assertThat(logTracker.actualMessages.get(0).getFormattedMessage()).contains(okMessage);
				assertThat(logTracker.actualMessages.get(1).getFormattedMessage()).contains(okMessage);
				assertThat(logTracker.actualMessages.get(2).getFormattedMessage()).contains(okMessage);
				assertThat(logTracker.actualMessages.get(3).getFormattedMessage()).contains(notFoundMessage);
			}
		}
	}

	@Test
	@SuppressWarnings("deprecation")
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
				          .block(Duration.ofSeconds(5));
		assertThat(code).isEqualTo(200);

		code = createClient(disposableServer.port())
		                 .get()
		                 .uri("/helloMan")
		                 .responseSingle((res, buf) -> Mono.just(res.status().code()))
		                 .block(Duration.ofSeconds(5));
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
		            .expectNextMatches(HttpResponseStatus.REQUEST_URI_TOO_LONG::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));
	}

	@Test
	void httpServerRequestHeadersTooLong() {
		HttpServer httpServer = HttpServer.create()
				          .port(0)
				          .httpRequestDecoder(c -> c.maxHeaderSize(20));
		disposableServer =
				httpServer.handle((req, res) -> res.sendString(Mono.just("Should not be reached")))
				          .bindNow();

		Mono<HttpResponseStatus> status =
				createClient(disposableServer.port())
				          .headers(h -> h.set("content-type", "somethingtooolooong"))
				          .get()
				          .uri("/path")
				          .responseSingle((res, byteBufMono) -> Mono.just(res.status()));

		StepVerifier.create(status)
		            .expectNextMatches(HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));
	}

	@Test
	@SuppressWarnings("deprecation")
	void httpServerRequestConfigInjectAttributes() {
		AtomicReference<Channel> channelRef = new AtomicReference<>();
		AtomicBoolean validate = new AtomicBoolean();
		AtomicInteger chunkSize = new AtomicInteger();
		AtomicBoolean allowDuplicateContentLengths = new AtomicBoolean();
		disposableServer =
				createServer()
				          .httpRequestDecoder(opt -> opt.maxInitialLineLength(123)
				                                        .maxHeaderSize(456)
				                                        .maxChunkSize(789)
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
				                      chunkSize.set((Integer) getValueReflection(decoder, "maxChunkSize", 2));
				                      validate.set((Boolean) getValueReflection(decoder, "validateHeaders", 2));
				                      allowDuplicateContentLengths.set((Boolean) getValueReflection(decoder, "allowDuplicateContentLengths", 2));
				                  })
				          .bindNow();

		createClient(disposableServer::address)
		          .post()
		          .uri("/")
		          .send(ByteBufFlux.fromString(Mono.just("bodysample")))
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block(Duration.ofSeconds(30));

		assertThat(channelRef.get()).isNotNull();
		assertThat(chunkSize).as("line length").hasValue(789);
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
				},
				HttpProtocol.HTTP11);
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
				},
				HttpProtocol.HTTP11);
		assertThat(ReferenceCountUtil.refCnt(data)).isEqualTo(0);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void testDropPublisher_1(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		ByteBuf data = ByteBufAllocator.DEFAULT.buffer();
		data.writeCharSequence("test", Charset.defaultCharset());
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .send(Flux.defer(() -> Flux.just(data, data.retain(), data.retain()))
				                           .doFinally(s -> latch.countDown()))
				                 .then(),
				(req, out) -> out,
				protocol);
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
				(req, out) -> out,
				HttpProtocol.HTTP11);
		assertThat(ReferenceCountUtil.refCnt(data)).isEqualTo(0);
	}

	@Test
	void testDropMessage() throws Exception {
		ByteBuf data = ByteBufAllocator.DEFAULT.buffer();
		data.writeCharSequence("test", Charset.defaultCharset());
		doTestDropData(
				(req, res) -> res.header("Content-Length", "0")
				                 .sendObject(data),
				(req, out) -> out,
				HttpProtocol.HTTP11);
		assertThat(ReferenceCountUtil.refCnt(data)).isEqualTo(0);
	}

	private void doTestDropData(
			BiFunction<? super HttpServerRequest, ? super
					HttpServerResponse, ? extends Publisher<Void>> serverFn,
			BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>> clientFn,
			HttpProtocol protocol)
			throws Exception {
		disposableServer =
				createServer()
				          .protocol(protocol)
				          .handle(serverFn)
				          .bindNow(Duration.ofSeconds(30));

		CountDownLatch latch = new CountDownLatch(1);
		String response =
				createClient(disposableServer.port())
				          .protocol(protocol)
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
				    .expectError(PrematureCloseException.class)
				    .verify(Duration.ofSeconds(10));

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
		          .blockLast(Duration.ofSeconds(5));

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
		          .blockLast(Duration.ofSeconds(5));

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
		AtomicReference<Throwable> error = new AtomicReference<>();
		disposableServer =
				createServer()
				          .doOnConnection(conn -> conn.channel().pipeline().addAfter(NettyPipeline.HttpTrafficHandler, null,
				                  new ChannelInboundHandlerAdapter() {

				                      @Override
				                      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
				                          error.set(cause);
				                          ctx.fireExceptionCaught(cause);
				                      }
				          }))
				          .route(r -> r.put("/1", (req, res) -> req.receive()
				                                                   .then(res.sendString(Mono.just("test"))
				                                                            .then()))
				                       .put("/2", (req, res) -> res.send(req.receive().retain())))
				          .bindNow();

		doTestDecodingFailureLastHttpContent("PUT /1 HTTP/1.1\r\nHost: a.example.com\r\n" +
				"Transfer-Encoding: chunked\r\n\r\nsomething\r\n\r\n", 1, "400 Bad Request", "connection: close");

		assertThat(error.get()).isNull();

		doTestDecodingFailureLastHttpContent("PUT /2 HTTP/1.1\r\nHost: a.example.com\r\n" +
				"Transfer-Encoding: chunked\r\n\r\nsomething\r\n\r\n", 2, "200 OK");

		assertThat(error.get()).isNull();
	}

	private void doTestDecodingFailureLastHttpContent(String message, int expectedSize, String... expectations) throws Exception {
		TcpClient tcpClient =
				TcpClient.create()
				         .port(disposableServer.port())
				         .wiretap(true);

		Connection connection = tcpClient.connectNow();

		CountDownLatch latch = new CountDownLatch(1);
		connection.channel()
		          .closeFuture()
		          .addListener(f -> latch.countDown());

		AtomicReference<List<String>> result = new AtomicReference<>(new ArrayList<>());
		connection.inbound()
		          .receive()
		          .asString()
		          .doOnNext(s -> result.get().add(s))
		          .subscribe();

		connection.outbound()
		          .sendString(Mono.just(message))
		          .then()
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(result.get()).hasSize(expectedSize);
		assertThat(result.get().get(0)).contains(expectations);
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
	@SuppressWarnings("FutureReturnValueIgnored")
	void testIssue1001() throws Exception {
		AtomicReference<Throwable> err = new AtomicReference<>();
		disposableServer =
				createServer()
				          .host("localhost")
				          .doOnChannelInit((obs, ch, addr) ->
				              ch.pipeline().addBefore(NettyPipeline.HttpTrafficHandler, "", new ChannelDuplexHandler() {

				                  HttpRequest request;

				                  @Override
				                  public void channelRead(ChannelHandlerContext ctx, Object msg) {
				                      if (msg instanceof HttpRequest) {
				                          request = (HttpRequest) msg;
				                      }
				                      ctx.fireChannelRead(msg);
				                  }

				                  @Override
				                  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
				                      if (msg instanceof HttpResponse && request != null && request.decoderResult().isFailure()) {
				                          err.set(request.decoderResult().cause());
				                      }
				                      //"FutureReturnValueIgnored" this is deliberate
				                      ctx.write(msg, promise);
				                  }
				              }))
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

		assertThat(err.get()).isNotNull()
				.isInstanceOf(URISyntaxException.class);

		StepVerifier.create(
		        createClient(disposableServer::address)
		                  .get()
		                  .uri("/<")
		                  .response())
		            .expectError(IllegalArgumentException.class)
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testGracefulShutdownHttp11() throws Exception {
		doTestGracefulShutdown(createServer(), createClient(() -> disposableServer.address()));
	}

	@ParameterizedTest
	@MethodSource("h2cCompatibleCombinations")
	void testGracefulShutdownH2C(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) throws Exception {
		doTestGracefulShutdown(createServer().protocol(serverProtocols),
				createClient(() -> disposableServer.address()).protocol(clientProtocols));
	}

	@ParameterizedTest
	@MethodSource("h2CompatibleCombinations")
	@SuppressWarnings("deprecation")
	void testGracefulShutdownH2(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestGracefulShutdown(createServer().protocol(serverProtocols).secure(spec -> spec.sslContext(serverCtx)),
				createClient(() -> disposableServer.address()).protocol(clientProtocols).secure(spec -> spec.sslContext(clientCtx)));
	}

	private void doTestGracefulShutdown(HttpServer server, HttpClient client) throws Exception {
		CountDownLatch latch1 = new CountDownLatch(2);
		CountDownLatch latch2 = new CountDownLatch(2);
		CountDownLatch latchGoAway = new CountDownLatch(2);
		CountDownLatch latch3 = new CountDownLatch(1);
		LoopResources loop = LoopResources.create("testGracefulShutdown");
		group = new DefaultChannelGroup(executor);
		disposableServer =
				server.runOn(loop)
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

		AtomicReference<String> result = new AtomicReference<>();
		Flux.just("/delay500", "/delay1000")
		    .flatMap(s ->
		            client
				            .doOnConnected(conn -> conn.addHandlerLast(new ChannelInboundHandlerAdapter() {
					            @Override
					            public void channelRead(@NotNull ChannelHandlerContext ctx, @NotNull Object msg) {
									if (msg instanceof Http2GoAwayFrame) {
										latchGoAway.countDown();
						            }
									ctx.fireChannelRead(msg);
					            }
				            }))
				            .doOnResponse((res, conn) -> {
					            if (!(conn.channel() instanceof Http2StreamChannel)) {
						            latchGoAway.countDown(); // we are not using neither H2C nor H2
					            }
				            })
				          .get()
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

		assertThat(latchGoAway.await(30, TimeUnit.SECONDS)).as("2 GOAWAY should have been received").isTrue();
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
	@SuppressWarnings("deprecation")
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
		assumeThat(LoopResources.hasNativeSupport()).isTrue();
		disposableServer =
				server.bindAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
				      .wiretap(true)
				      .handle((req, res) -> {
				          req.withConnection(conn -> {
				              assertThat(conn.channel().localAddress()).isNotNull();
				              assertThat(conn.channel().remoteAddress()).isNotNull();
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
		group = new DefaultChannelGroup(executor);

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
		group = new DefaultChannelGroup(executor);
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
		InetSocketAddress localSocketAddress = AddressUtils.createUnresolved("localhost", 80);
		InetSocketAddress remoteSocketAddress = AddressUtils.createUnresolved("localhost", 9999);
		HttpServerOperations ops = new HttpServerOperations(
				Connection.from(channel),
				ConnectionObserver.emptyListener(),
				new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
				null,
				new ConnectionInfo(localSocketAddress, DEFAULT_HOST_NAME, DEFAULT_HTTP_PORT, remoteSocketAddress, "http", true),
				ServerCookieDecoder.STRICT,
				ServerCookieEncoder.STRICT,
				DEFAULT_FORM_DECODER_SPEC,
				ReactorNettyHttpMessageLogFactory.INSTANCE,
				false,
				null,
				null,
				null,
				false,
				ZonedDateTime.now(ReactorNetty.ZONE_ID_SYSTEM));
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
		doTestSniSupport(Function.identity(), Function.identity());
	}

	@Test
	void testIssue3022() throws Exception {
		TestHttpClientMetricsRecorder clientMetricsRecorder = new TestHttpClientMetricsRecorder();
		TestHttpServerMetricsRecorder serverMetricsRecorder = new TestHttpServerMetricsRecorder();
		doTestSniSupport(server -> server.metrics(true, () -> serverMetricsRecorder, Function.identity()),
				client -> client.metrics(true, () -> clientMetricsRecorder, Function.identity()));
		assertThat(clientMetricsRecorder.tlsHandshakeTime).isNotNull().isGreaterThan(Duration.ZERO);
		assertThat(serverMetricsRecorder.tlsHandshakeTime).isNotNull().isGreaterThan(Duration.ZERO);
	}

	@SuppressWarnings("deprecation")
	private void doTestSniSupport(Function<HttpServer, HttpServer> serverCustomizer,
			Function<HttpClient, HttpClient> clientCustomizer) throws Exception {
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
				serverCustomizer.apply(createServer())
				          .secure(spec -> spec.sslContext(defaultSslContextBuilder)
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

		clientCustomizer.apply(createClient(disposableServer::address))
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
	@SuppressWarnings("deprecation")
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
				                              .setSniAsyncMappings((input, promise) -> promise.setSuccess(testSslProvider)))
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
	@SuppressWarnings("deprecation")
	void testSniSupportHandshakeTimeout() {
		Http11SslContextSpec defaultSslContextBuilder =
				Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());

		Http11SslContextSpec clientSslContextBuilder =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		Sinks.One<Throwable> error = Sinks.one();
		disposableServer =
				createServer()
				        .childOption(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(64))
				        .secure(spec -> spec.sslContext(defaultSslContextBuilder)
				                            .handshakeTimeout(Duration.ofMillis(1))
				                            .addSniMapping("*.test.com", domainSpec -> domainSpec.sslContext(defaultSslContextBuilder)))
				        .doOnChannelInit((obs, ch, addr) ->
				                ch.pipeline().addBefore(NettyPipeline.ReactiveBridge, "testSniSupportHandshakeTimeout",
				                        new ChannelInboundHandlerAdapter() {

				                            @Override
				                            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
				                                if (evt instanceof SniCompletionEvent) {
				                                    error.tryEmitValue(((SniCompletionEvent) evt).cause());
				                                }
				                                ctx.fireUserEventTriggered(evt);
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
		        .as(StepVerifier::create)
		        .expectError()
		        .verify(Duration.ofSeconds(10));

		StepVerifier.create(error.asMono())
		            .expectNextMatches(t -> t instanceof SslHandshakeTimeoutException)
		            .expectComplete()
		            .verify(Duration.ofSeconds(10));
	}

	@Test
	void testIssue1286_HTTP11() throws Exception {
		doTestIssue1286(Function.identity(), Function.identity(), false, false);
	}

	@Test
	void testIssue1286_H2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C),
				client -> client.protocol(HttpProtocol.H2C),
				false, false);
	}

	@Test
	void testIssue1286_ServerHTTP11AndH2CClientH2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11),
				client -> client.protocol(HttpProtocol.H2C),
				false, false);
	}

	@Test
	void testIssue1286_ServerHTTP11AndH2CClientHTTP11AndH2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
				                .httpRequestDecoder(spec -> spec.h2cMaxContentLength(256)),
				client -> client.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11),
				false, false);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286_H2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
				false, false);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286_ServerHTTP11AndH2ClientH2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
				false, false);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286_ServerHTTP11AndH2ClientHTTP11AndH2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(clientCtx)),
				false, false);
	}

	@Test
	void testIssue1286ErrorResponse_HTTP11() throws Exception {
		doTestIssue1286(Function.identity(), Function.identity(), false, true);
	}

	@Test
	void testIssue1286ErrorResponse_H2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C),
				client -> client.protocol(HttpProtocol.H2C),
				false, true);
	}

	@Test
	void testIssue1286ErrorResponse_ServerHTTP11AndH2CAndClientH2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11),
				client -> client.protocol(HttpProtocol.H2C),
				false, true);
	}

	@Test
	void testIssue1286ErrorResponse_ServerHTTP11AndH2CClientHTTP11AndH2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
				                .httpRequestDecoder(spec -> spec.h2cMaxContentLength(256)),
				client -> client.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11),
				false, true);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286ErrorResponse_H2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
				false, true);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286ErrorResponse_ServerHTTP11AndH2ClientH2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
				false, true);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286ErrorResponse_ServerHTTP11AndH2ClientHTTP11AndH2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(clientCtx)),
				false, true);
	}

	@Test
	void testIssue1286ConnectionClose_HTTP11() throws Exception {
		doTestIssue1286(Function.identity(), Function.identity(), true, false);
	}

	@Test
	void testIssue1286ConnectionClose_H2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C),
				client -> client.protocol(HttpProtocol.H2C),
				true, false);
	}

	@Test
	void testIssue1286ConnectionClose_ServerHTTP11AndH2CClientH2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11),
				client -> client.protocol(HttpProtocol.H2C),
				true, false);
	}

	@Test
	void testIssue1286ConnectionClose_ServerHTTP11AndH2CClientHTTP11AndH2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
				                .httpRequestDecoder(spec -> spec.h2cMaxContentLength(256)),
				client -> client.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11),
				true, false);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286ConnectionClose_H2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
				true, false);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286ConnectionClose_ServerHTTP11AndH2ClientH2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
				true, false);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286ConnectionClose_ServerHTTP11AndH2ClientHTTP11AndH2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(clientCtx)),
				true, false);
	}

	@Test
	void testIssue1286ConnectionCloseErrorResponse_HTTP11() throws Exception {
		doTestIssue1286(Function.identity(), Function.identity(), true, true);
	}

	@Test
	void testIssue1286ConnectionCloseErrorResponse_H2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C),
				client -> client.protocol(HttpProtocol.H2C),
				true, true);
	}

	@Test
	void testIssue1286ConnectionCloseErrorResponse_ServerHTTP11AndH2CClientH2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11),
				client -> client.protocol(HttpProtocol.H2C),
				true, true);
	}

	@Test
	void testIssue1286ConnectionCloseErrorResponse_ServerHTTP11AndH2CClientHTTP11AndH2C() throws Exception {
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
				                .httpRequestDecoder(spec -> spec.h2cMaxContentLength(256)),
				client -> client.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11),
				true, true);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286ConnectionCloseErrorResponse_H2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
				true, true);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286ConnectionCloseErrorResponse_ServerHTTP11AndH2ClientH2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
				true, true);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testIssue1286ConnectionCloseErrorResponse_ServerHTTP11AndH2ClientHTTP11AndH2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1286(
				server -> server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure(spec -> spec.sslContext(clientCtx)),
				true, true);
	}

	private void doTestIssue1286(
			Function<HttpServer, HttpServer> serverCustomizer,
			Function<HttpClient, HttpClient> clientCustomizer,
			boolean connectionClose, boolean throwException) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<ByteBuf>> replay = new AtomicReference<>(new ArrayList<>());
		HttpServer server = serverCustomizer.apply(createServer());
		disposableServer =
				server.doOnConnection(conn -> conn.addHandlerLast(new ChannelInboundHandlerAdapter() {

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

		HttpClient client = clientCustomizer.apply(createClient(disposableServer.port()));

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
	void testIdleTimeout_DelayFirstRequest_NoSSL() {
		doTestIdleTimeout_DelayFirstRequest(false);
	}

	@Test
	void testIdleTimeout_DelayFirstRequest() {
		doTestIdleTimeout_DelayFirstRequest(true);
	}

	@SuppressWarnings("deprecation")
	private void doTestIdleTimeout_DelayFirstRequest(boolean withSecurity) {
		HttpServer server =
				createServer()
				          .idleTimeout(Duration.ofMillis(200))
				          .handle((req, resp) -> resp.send(req.receive().retain()));

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
				.responseSingle((response, byteBufMono) -> byteBufMono.asString()))
				.expectNext("/yes/{value}")
				.expectComplete()
				.verify(Duration.ofSeconds(5));
	}

	@Test
	void testUseComparatorOrderRoutes() {
		HttpServerRoutes serverRoutes = HttpServerRoutes.newRoutes()
				.get("/yes/{value}", (request, response) -> response.sendString(Mono.just("/yes/{value}")))
				.get("/yes/value", (request, response) -> response.sendString(Mono.just("/yes/value")));

		disposableServer = HttpServer.create().handle(serverRoutes.comparator(comparator)).bindNow();

		StepVerifier.create(createClient(disposableServer.port()).get().uri("/yes/value")
				.responseSingle((response, byteBufMono) -> byteBufMono.asString()))
				.expectNext("/yes/value")
				.expectComplete()
				.verify(Duration.ofSeconds(5));
	}

	@Test
	void testOverrideRouteOrder() {
		HttpServerRoutes serverRoutes = HttpServerRoutes.newRoutes()
				.get("/yes/{value}", (request, response) -> response.sendString(Mono.just("/yes/{value}")))
				.get("/yes/value", (request, response) -> response.sendString(Mono.just("/yes/value")));

		try {
			disposableServer = HttpServer.create().handle(serverRoutes.comparator(comparator)).bindNow();

			StepVerifier.create(createClient(disposableServer.port()).get().uri("/yes/value")
					.responseSingle((response, byteBufMono) -> byteBufMono.asString()))
					.expectNext("/yes/value")
					.expectComplete()
					.verify(Duration.ofSeconds(5));
		}
		finally {
			if (disposableServer != null) {
				disposableServer.disposeNow();
			}
		}

		disposableServer = HttpServer.create().handle(serverRoutes.comparator(comparator).comparator(comparator.reversed()))
				.bindNow();

		StepVerifier.create(createClient(disposableServer.port()).get().uri("/yes/value")
				.responseSingle((response, byteBufMono) -> byteBufMono.asString()))
				.expectNext("/yes/{value}")
				.expectComplete()
				.verify(Duration.ofSeconds(5));
	}

	@Test
	void testUseRoutesConfiguredOrder() {
		HttpServerRoutes serverRoutes = HttpServerRoutes.newRoutes()
				.get("/yes/{value}", (request, response) -> response.sendString(Mono.just("/yes/{value}")))
				.get("/yes/value", (request, response) -> response.sendString(Mono.just("/yes/value")));

		try {
			disposableServer = HttpServer.create().handle(serverRoutes.comparator(comparator)).bindNow();

			StepVerifier.create(createClient(disposableServer.port()).get().uri("/yes/value")
					.responseSingle((response, byteBufMono) -> byteBufMono.asString()))
					.expectNext("/yes/value")
					.expectComplete()
					.verify(Duration.ofSeconds(5));
		}
		finally {
			if (disposableServer != null) {
				disposableServer.disposeNow();
			}
		}

		disposableServer = HttpServer.create().handle(serverRoutes.comparator(comparator).noComparator())
				.bindNow();

		StepVerifier.create(createClient(disposableServer.port()).get().uri("/yes/value")
				.responseSingle((response, byteBufMono) -> byteBufMono.asString()))
				.expectNext("/yes/{value}")
				.expectComplete()
				.verify(Duration.ofSeconds(5));
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
					.responseSingle((response, byteBufMono) -> byteBufMono.asString()))
					.expectNext("/route1")
					.expectComplete()
					.verify(Duration.ofSeconds(5));

			StepVerifier.create(createClient(disposableServer.port()).get().uri("/route2")
					.responseSingle((response, byteBufMono) -> byteBufMono.asString()))
					.expectNext("/route2")
					.expectComplete()
					.verify(Duration.ofSeconds(5));
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
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		StepVerifier.create(createClient(disposableServer.port()).get().uri("/route2")
				.responseSingle((response, byteBufMono) -> byteBufMono.asString()))
				.expectNext("/route2")
				.expectComplete()
				.verify(Duration.ofSeconds(5));
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
		                       .zipWith(Mono.just(res.responseHeaders().get(HttpHeaderNames.CONNECTION, "persistent")))))
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

	@SuppressWarnings("FutureReturnValueIgnored")
	private void doTestIsFormUrlencoded(String headerValue, boolean expectation) {
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
		request.headers().set(HttpHeaderNames.CONTENT_TYPE, headerValue);
		InetSocketAddress localSocketAddress = AddressUtils.createUnresolved("localhost", 80);
		InetSocketAddress remoteSocketAddress = AddressUtils.createUnresolved("localhost", 9999);
		HttpServerOperations ops = new HttpServerOperations(
				Connection.from(channel),
				ConnectionObserver.emptyListener(),
				request,
				null,
				new ConnectionInfo(localSocketAddress, DEFAULT_HOST_NAME, DEFAULT_HTTP_PORT, remoteSocketAddress, "http", true),
				ServerCookieDecoder.STRICT,
				ServerCookieEncoder.STRICT,
				DEFAULT_FORM_DECODER_SPEC,
				ReactorNettyHttpMessageLogFactory.INSTANCE,
				false,
				null,
				null,
				null,
				false,
				ZonedDateTime.now(ReactorNetty.ZONE_ID_SYSTEM));
		assertThat(ops.isFormUrlencoded()).isEqualTo(expectation);
		// "FutureReturnValueIgnored" is suppressed deliberately
		channel.close();
	}

	/**
	 * This test verifies if server h2 streams are closed properly when the server does not consume client post data chunks.
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
	 * This test verifies if server h2 streams are closed properly when the server does not consume client post data chunks.
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

	@SuppressWarnings("deprecation")
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
		              .send(ByteBufFlux.fromString(clientRequest))
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
				.send(ByteBufFlux.fromString(postFlux))
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
				.send(ByteBufFlux.fromString(postFlux))
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
	 * This scenario tests the following (HTTP chunk transfer encoding is used):
	 *
	 * <ul>
	 * <li>server is configured with a line based decoder in order to parse request body chunks that contains some newlines.</li>
	 * <li>client sends one single chunk: DATA1\nDATA2\n</li>
	 * <li> server will cancel inbound receiver on DATA1, and will respond 400 bad request.
	 * <li>DATA2 is expected to be discarded and released</li>
	 * </ul>
	 */
	@Test
	@SuppressWarnings("deprecation")
	void testHttpServerCancelled() throws InterruptedException {
		// logged by server when cancelled while reading http request body
		String serverCancelLog = "[HttpServer] Channel inbound receiver cancelled (operation cancelled).";
		// logged by client when cancelled after having received the whole response from the server
		String clientCancelLog = "Http client inbound receiver cancelled, closing channel.";

		try (LogTracker lt = new LogTracker(ChannelOperations.class, serverCancelLog);
		     LogTracker lt2 = new LogTracker("reactor.netty.http.client.HttpClientOperations", clientCancelLog)) {
			CountDownLatch serverInboundReleased = new CountDownLatch(1);
			AtomicReference<Subscription> subscription = new AtomicReference<>();
			disposableServer = createServer()
					.handle((req, res) -> {
						req.withConnection(conn -> {
							conn.addHandler(new LineBasedFrameDecoder(128));
							conn.addHandlerLast(new ChannelInboundHandlerAdapter() {
								@Override
								public void channelRead(@NotNull ChannelHandlerContext ctx, @NotNull Object msg) {
									ByteBuf buf = (msg instanceof ByteBufHolder) ? ((ByteBufHolder) msg).content() :
											((msg instanceof ByteBuf) ? (ByteBuf) msg : null);
									ctx.fireChannelRead(msg);
									// At this point, the message has been handled and must have been released.
									if (buf != null && buf.refCnt() == 0) {
										serverInboundReleased.countDown();
										log.debug("Server handled received message, which is now released");
									}
								}
							});
						});
						return req.receive()
								.asString()
								.log("server.receive")
								.doOnSubscribe(subscription::set)
								.doOnNext(n -> {
									subscription.get().cancel();
									res.status(400).send().subscribe();
								})
								.then(Mono.never());
					})
					.bindNow();

			ByteBuf data = ByteBufAllocator.DEFAULT.buffer();
			data.writeCharSequence("DATA1\nDATA2\n", Charset.defaultCharset());

			createClient(disposableServer::address)
					.wiretap(true)
					.post()
					.send(Flux.just(data))
					.uri("/")
					.responseSingle((rsp, buf) -> Mono.just(rsp))
					.as(StepVerifier::create)
					.expectNextMatches(r -> r.status().code() == 400 && "0".equals(r.responseHeaders().get("Content-Length")))
					.expectComplete()
					.verify(Duration.ofSeconds(30));

			assertThat(serverInboundReleased.await(30, TimeUnit.SECONDS)).as("serverInboundReleased await").isTrue();
			assertThat(lt.latch.await(30, TimeUnit.SECONDS)).as("logTrack await").isTrue();
			assertThat(lt2.latch.await(30, TimeUnit.SECONDS)).as("logTrack2 await").isTrue();
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	void testHttpServerCancelledOnClientClose() throws InterruptedException {
		// logged by client when disposing the connection before send the 2nd request data chunk
		String clientCancelLog = "Http client inbound receiver cancelled, closing channel.";
		// logged by server when cancelled while reading request body (because connection has been closed by client)
		String serverCancelLog = "[HttpServer] Channel inbound receiver cancelled (channel disconnected).";

		try (LogTracker lt = new LogTracker(ChannelOperations.class, serverCancelLog);
		     LogTracker lt2 = new LogTracker("reactor.netty.http.client.HttpClientOperations", clientCancelLog)) {
			disposableServer = createServer()
					.handle((req, res) -> {
						return req.receive()
								.aggregate()
								.asString()
								.log("server.receive")
								.then(res.status(200).sendString(Mono.just("OK")).neverComplete());
					})
					.bindNow();

			AtomicReference<Connection> clientConn = new AtomicReference<>();
			AtomicInteger counter = new AtomicInteger();
			CountDownLatch clientClosed = new CountDownLatch(1);

			createClient(disposableServer::address)
					.wiretap(true)
					.doOnRequest((req, conn) -> {
						clientConn.set(conn);
						conn.onDispose(() -> clientClosed.countDown());
					})
					.post()
					.send(ByteBufFlux.fromString(Flux.just("foo", "bar"))
							.doOnNext(byteBuf -> {
								if (counter.incrementAndGet() == 2) {
									log.warn("Client: disposing connection before sending 2nd chunk");
									clientConn.get().dispose();
								}
							}))
					.uri("/")
					.responseSingle((rsp, buf) -> Mono.just(rsp))
					.subscribe();

			assertThat(lt.latch.await(30, TimeUnit.SECONDS)).as("logTracker await").isTrue();
			assertThat(lt2.latch.await(30, TimeUnit.SECONDS)).as("logTracker2 await").isTrue();
			assertThat(clientClosed.await(30, TimeUnit.SECONDS)).as("clientClosed await").isTrue();
		}
	}

	@ParameterizedTest
	@MethodSource("h2CompatibleCombinations")
	@SuppressWarnings("deprecation")
	void testIssue2760_H2(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx = Http2SslContextSpec.forClient()
				.configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		testIssue2760(
				server -> server.protocol(serverProtocols).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(clientProtocols).secure(spec -> spec.sslContext(clientCtx)));
	}

	@ParameterizedTest
	@MethodSource("h2cCompatibleCombinations")
	void testIssue2760_H2C(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) {
		testIssue2760(server -> server.protocol(serverProtocols), client -> client.protocol(clientProtocols));
	}

	private void testIssue2760(Function<HttpServer, HttpServer> serverCustomizer,
	                           Function<HttpClient, HttpClient> clientCustomizer) {
		ConnectionProvider provider = ConnectionProvider.create("testIssue2760", 1);
		LoopResources loopServer = null;
		LoopResources loopClient = null;

		try {
			loopClient = LoopResources.create("client", 1, false);
			loopServer = LoopResources.create("server", 1, false);
			doTestIssue2760(
					serverCustomizer
							.apply(createServer().runOn(loopServer)),
					clientCustomizer
							.apply(createClient(provider, () -> disposableServer.address()).runOn(loopClient)));
		}
		finally {
			provider.disposeLater().block(Duration.ofSeconds(5));
			if (loopServer != null) {
				loopServer.disposeLater(Duration.ofSeconds(0), Duration.ofSeconds(DEFAULT_SHUTDOWN_TIMEOUT))
						.block(Duration.ofSeconds(5));
			}
			if (loopClient != null) {
				loopClient.disposeLater(Duration.ofSeconds(0), Duration.ofSeconds(DEFAULT_SHUTDOWN_TIMEOUT))
						.block(Duration.ofSeconds(5));
			}
		}
	}

	private void doTestIssue2760(HttpServer server, HttpClient client) {
		disposableServer = server
				.doOnConnection(DelayH2FlushHandler::register)
				.route(r -> r.get("/issue-2760", (req, res) -> res
						.header("Content-Type", "text/plain")
						.sendObject(ByteBufAllocator.DEFAULT.buffer().writeBytes(DATA))
				))
				.bindNow();

		int messages = 100;
		Flux.range(0, messages)
				.flatMap(i -> client
						.wiretap(false)
						.get()
						.uri("/issue-2760")
						.responseSingle((res, bytes) -> bytes.asString()
								.zipWith(Mono.just(res.status()))))
				.collectList()
				.as(StepVerifier::create)
				.expectNextMatches(l -> l.size() == messages &&
						l.stream().allMatch(tpl -> tpl.getT2().code() == 200 && tpl.getT1().equals(DATA_STRING))
				)
				.expectComplete()
				.verify(Duration.ofSeconds(60));
	}

	@ParameterizedTest
	@MethodSource("h2CompatibleCombinations")
	@SuppressWarnings("deprecation")
	void testIssue2927_H2(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx = Http2SslContextSpec.forClient()
				.configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		testIssue2927(
				server -> server.protocol(serverProtocols).secure(spec -> spec.sslContext(serverCtx)),
				client -> client.protocol(clientProtocols).secure(spec -> spec.sslContext(clientCtx)));
	}

	@ParameterizedTest
	@MethodSource("h2cCompatibleCombinations")
	void testIssue2927_H2C(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) {
		testIssue2927(server -> server.protocol(serverProtocols), client -> client.protocol(clientProtocols));
	}

	private void testIssue2927(Function<HttpServer, HttpServer> serverCustomizer, Function<HttpClient, HttpClient> clientCustomizer) {
		HttpServer httpServer = serverCustomizer.apply(HttpServer.create())
				.http2Settings(spec -> spec.maxHeaderListSize(1024));

		disposableServer =
				httpServer
						.handle((req, res) -> res.sendString(Mono.just("Hello")))
						.bindNow();

		HttpClient client = clientCustomizer.apply(createClient(disposableServer.port()));

		Flux<HttpResponseStatus> responseFlux = Flux.range(0, 2)
				.flatMap(i -> i == 1 ?
						// For the second request, add some extra headers in order to exceed server max header list size
						Flux.range(0, 100).reduce(client, (c, j) -> c.headers(h -> h.set("Foo" + j, "Bar" + j)))
						:
						Mono.just(client))
				.flatMap(cl -> cl
						.get()
						.uri("/test")
						.responseSingle((res, byteBufMono) -> Mono.just(res.status())), 1);

		StepVerifier.create(responseFlux)
				.expectNextMatches(HttpResponseStatus.OK::equals)
				.expectErrorMatches(t -> t instanceof PrematureCloseException && t.getCause() instanceof Http2Exception.HeaderListSizeException)
				.verify(Duration.ofSeconds(30));
	}

	static final class TestHttpServerMetricsRecorder implements HttpServerMetricsRecorder {

		Duration tlsHandshakeTime;

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
			tlsHandshakeTime = time;
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
	}

	static final class TestHttpClientMetricsRecorder implements HttpClientMetricsRecorder {

		Duration tlsHandshakeTime;

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
			tlsHandshakeTime = time;
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
		}

		@Override
		public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		}

		@Override
		public void recordDataReceivedTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		}

		@Override
		public void recordDataSentTime(SocketAddress remoteAddress, String uri, String method, Duration time) {
		}

		@Override
		public void recordResponseTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		}
	}
}
