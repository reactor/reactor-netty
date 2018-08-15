/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty.http.server;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.testng.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.TcpClient;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Stephane Maldini
 */
public class HttpServerTests {

	@Test
	public void httpPort() {
		DisposableServer blockingFacade = HttpServer.create()
		                                            .port(8080)
		                                            .handle((req, resp) -> resp.sendNotFound())
		                                            .wiretap()
		                                            .bindNow();
		blockingFacade.disposeNow();

		assertThat(blockingFacade.address().getPort())
				.isEqualTo(8080);
	}

	@Test
	public void httpPortWithAddress() {
		DisposableServer blockingFacade = HttpServer.create()
		                                            .port(8080)
		                                            .host("localhost")
		                                            .handle((req, resp) -> resp.sendNotFound())
		                                            .wiretap()
		                                            .bindNow();
		blockingFacade.disposeNow();

		assertThat(blockingFacade.address().getPort())
				.isEqualTo(8080);
	}

	@Test
	public void releaseInboundChannelOnNonKeepAliveRequest() {
		DisposableServer c = HttpServer.create()
		                               .port(0)
		                               .handle((req, resp) -> req.receive().then(resp.status(200).send()))
		                               .wiretap()
		                               .bindNow();

		Flux<ByteBuf> src = Flux.range(0, 3)
		                        .map(n -> Unpooled.wrappedBuffer(Integer.toString(n)
		                                                                .getBytes(Charset.defaultCharset())));

		Flux.range(0, 100)
		    .concatMap(n -> HttpClient.create()
		                              .port(c.address().getPort())
		                              .tcpConfiguration(TcpClient::noSSL)
		                              .wiretap()
		                              .post()
		                              .uri("/return")
		                              .send((r, out) -> {
		                                  r.keepAlive(false);
		                                  return out.send(src);
		                              })
		                              .responseSingle((res, buf) -> Mono.just(res.status().code())))
		    .collectList()
		    .block();

		c.dispose();
	}

	//from https://github.com/reactor/reactor-netty/issues/90
	@Test
	public void testRestart() {
		// start a first server with a handler that answers HTTP 200 OK
		DisposableServer context = HttpServer.create()
		                                     .port(8080)
		                                     .handle((req, resp) -> resp.status(200)
		                                                                .send().log())
		                                     .wiretap()
		                                     .bindNow();

		int code = HttpClient.create()
		                     .port(8080)
		                     .wiretap()
		                     .get()
		                     .uri("/")
		                     .response()
		                     .map(res -> res.status().code())
//		                     .responseSingle((res, buf) -> Mono.just(res.status().code()))
		                     .block();

		// checking the response status, OK
		assertThat(code).isEqualTo(200);
		// dispose the Netty context and wait for the channel close
		context.disposeNow();

		// create a totally new server instance, with a different handler that answers HTTP 201
		context = HttpServer.create()
		                    .port(8080)
		                    .handle((req, resp) -> resp.status(201).send())
		                    .wiretap()
		                    .bindNow();

		code = HttpClient.create()
		                 .port(8080)
		                 .wiretap()
		                 .get()
		                 .uri("/")
		                 .responseSingle((res, buf) -> Mono.just(res.status().code()))
		                 .block();

		// fails, response status is 200 and debugging shows the the previous handler is called
		assertThat(code).isEqualTo(201);
		context.disposeNow();
	}

	@Test
	public void errorResponseAndReturn() {
		DisposableServer c = HttpServer.create()
		                               .port(0)
		                               .handle((req, resp) -> Mono.error(new Exception("returnError")))
		                               .wiretap()
		                               .bindNow();

		int code =
				HttpClient.create()
				          .port(c.address().getPort())
				          .wiretap()
				          .get()
				          .uri("/return")
				          .responseSingle((res, buf) -> Mono.just(res.status().code()))
				          .block();
		assertThat(code).isEqualTo(500);

		c.dispose();

	}

	@Test
	public void httpPipelining() throws Exception {

		AtomicInteger i = new AtomicInteger();

		DisposableServer server = HttpServer.create()
		                                    .port(0)
		                                    .handle((req, resp) ->
		                                            resp.header(HttpHeaderNames.CONTENT_LENGTH, "1")
		                                                .sendString(Mono.just(i.incrementAndGet())
		                                                                .flatMap(d ->
		                                                                        Mono.delay(Duration.ofSeconds(4 - d))
		                                                                            .map(x -> d + "\n"))))
		                                    .wiretap()
		                                    .bindNow();

		DefaultFullHttpRequest request =
				new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
				                           HttpMethod.GET,
				                           "/plaintext");

		CountDownLatch latch = new CountDownLatch(6);

		Connection client =
				TcpClient.create()
				         .port(server.address().getPort())
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
				         .wiretap()
				         .connectNow();

		Assert.assertTrue(latch.await(45, TimeUnit.SECONDS));

		server.dispose();
		client.dispose();
	}

	@Test
	public void flushOnComplete() {

		Flux<String> test = Flux.range(0, 100)
		                        .map(n -> String.format("%010d", n));

		DisposableServer c = HttpServer.create()
		                               .port(0)
		                               .handle((req, resp) -> resp.sendString(test.map(s -> s + "\n")))
		                               .wiretap()
		                               .bindNow();

		Flux<String> client = HttpClient.create()
		                                .port(c.address().getPort())
		                                .wiretap()
		                                .tcpConfiguration(tcp -> tcp.doOnConnected(res ->
		                                        res.addHandler(new LineBasedFrameDecoder(10))))
		                                .get()
		                                .uri("/")
		                                .responseContent()
		                                .asString();

		StepVerifier.create(client)
		            .expectNextSequence(test.toIterable())
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		c.dispose();
	}

	@Test
	public void keepAlive() throws URISyntaxException {
		Path resource = Paths.get(getClass().getResource("/public").toURI());
		DisposableServer s = HttpServer.create()
		                               .port(0)
		                               .route(routes -> routes.directory("/test", resource))
		                               .wiretap()
		                               .bindNow();

		ConnectionProvider p = ConnectionProvider.fixed("http", 1);

		Channel response0 = HttpClient.create(p)
		                              .port(s.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/index.html")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(3099));

		Channel response1 = HttpClient.create(p)
		                              .port(s.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/test.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(3099));

		Channel response2 = HttpClient.create(p)
		                              .port(s.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/test1.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Channel response3 = HttpClient.create(p)
		                              .port(s.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/test2.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Channel response4 = HttpClient.create(p)
		                              .port(s.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/test3.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                         .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Channel response5 = HttpClient.create(p)
		                              .port(s.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/test4.css")
		                              .responseConnection((res, c) -> Mono.just(c.channel())
		                                                                  .delayUntil(ch -> c.inbound().receive()))
		                              .blockLast(Duration.ofSeconds(30));

		Assert.assertEquals(response0, response1);
		Assert.assertEquals(response0, response2);
		Assert.assertEquals(response0, response3);
		Assert.assertEquals(response0, response4);
		Assert.assertEquals(response0, response5);

		p.dispose();
		s.dispose();
	}

	@Test
	public void gettingOptionsDuplicates() {
		HttpServer server = HttpServer.create()
		                              .port(123)
		                              .host(("foo"))
		                              .compress();
		assertThat(server.tcpConfiguration().configure())
		          .isNotSameAs(HttpServer.DEFAULT_TCP_SERVER)
		          .isNotSameAs(server.tcpConfiguration().configure());
	}

	@Test
	public void startRouter() {
		DisposableServer facade = HttpServer.create()
		                                    .port(0)
		                                    .route(routes ->
		                                            routes.get("/hello",
		                                                    (req, resp) -> resp.sendString(Mono.just("hello!"))))
		                                    .wiretap()
		                                    .bindNow();

		try {
			int code =
					HttpClient.create()
					          .port(facade.address().getPort())
					          .wiretap()
					          .get()
					          .uri("/hello")
					          .responseSingle((res, buf) -> Mono.just(res.status().code()))
					          .block();
			assertThat(code).isEqualTo(200);

			code = HttpClient.create()
			                 .port(facade.address().getPort())
			                 .wiretap()
			                 .get()
			                 .uri("/helloMan")
			                 .responseSingle((res, buf) -> Mono.just(res.status().code()))
			                 .block();
			assertThat(code).isEqualTo(404);
		}
		finally {
			facade.disposeNow();
		}
	}

	@Test
	public void startRouterAndAwait() throws InterruptedException {
		ExecutorService ex = Executors.newSingleThreadExecutor();
		AtomicReference<DisposableServer> ref = new AtomicReference<>();

		Future<?> f = ex.submit(() ->
			    HttpServer.create()
			              .port(0)
			              .route(routes -> routes.get("/hello", (req, resp) -> resp.sendString(Mono.just("hello!"))))
			              .wiretap()
			              .bindUntilJavaShutdown(Duration.ofSeconds(2), ref::set)
		);

		//if the server cannot be started, a ExecutionException will be thrown instead
		assertThatExceptionOfType(TimeoutException.class)
				.isThrownBy(() -> f.get(1, TimeUnit.SECONDS));

		//the router is not done and is still blocking the thread
		assertThat(f.isDone()).isFalse();
		assertThat(ref.get()).isNotNull().withFailMessage("Server is not initialized after 1s");

		//shutdown the router to unblock the thread
		ref.get().disposeNow();
		Thread.sleep(100);
		assertThat(f.isDone()).isTrue();
	}

	@Test
	public void nonContentStatusCodes() {
		DisposableServer server =
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
				                       .get("/304-2", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED)))
				          .wiretap()
				          .bindNow();

		checkResponse("/204-1", server.address());
		checkResponse("/204-2", server.address());
		checkResponse("/205-1", server.address());
		checkResponse("/205-2", server.address());
		checkResponse("/304-1", server.address());
		checkResponse("/304-2", server.address());

		server.dispose();
	}

	private void checkResponse(String url, InetSocketAddress address) {
		Mono<Tuple3<Integer, HttpHeaders, String>> response =
				HttpClient.create()
				          .addressSupplier(() -> address)
				          .wiretap()
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
		DisposableServer server =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.route(req -> req.uri().startsWith("/1"),
				                                  (req, res) -> res.sendString(Mono.just("OK")))
				                       .route(req -> req.uri().startsWith("/2"),
				                                  (req, res) -> res.chunkedTransfer(false)
				                                                   .sendString(Mono.just("OK")))
				                       .route(req -> req.uri().startsWith("/3"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set("Content-Length", 2);
				                                                return res.sendString(Mono.just("OK"));
				                                                })
				                       .route(req -> req.uri().startsWith("/4"),
				                                  (req, res) -> res.sendHeaders())
				                       .route(req -> req.uri().startsWith("/5"),
				                                  (req, res) -> res.chunkedTransfer(false)
				                                                   .sendHeaders())
				                       .route(req -> req.uri().startsWith("/6"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set("Content-Length", 2);
				                                                return res.sendHeaders();
				                                                })
				                       .route(req -> req.uri().startsWith("/7"),
				                                  (req, res) -> res.send())
				                       .route(req -> req.uri().startsWith("/8"),
				                                  (req, res) -> res.chunkedTransfer(false)
				                                                   .send())
				                       .route(req -> req.uri().startsWith("/9"),
				                                  (req, res) -> {
				                                                res.responseHeaders().set("Content-Length", 2);
				                                                return res.send();
				                                                }))
				          .wiretap()
				          .bindNow();

		doTestContentLengthHeadRequest("/1", server.address(), HttpMethod.GET, true, false);
		doTestContentLengthHeadRequest("/1", server.address(), HttpMethod.HEAD, true, false);
		doTestContentLengthHeadRequest("/2", server.address(), HttpMethod.GET, false, true);
		doTestContentLengthHeadRequest("/2", server.address(), HttpMethod.HEAD, false, true);
		doTestContentLengthHeadRequest("/3", server.address(), HttpMethod.GET, false, false);
		doTestContentLengthHeadRequest("/3", server.address(), HttpMethod.HEAD, false, false);
		doTestContentLengthHeadRequest("/4", server.address(), HttpMethod.HEAD, true, false);
		doTestContentLengthHeadRequest("/5", server.address(), HttpMethod.HEAD, false, true);
		doTestContentLengthHeadRequest("/6", server.address(), HttpMethod.HEAD, false, false);
		doTestContentLengthHeadRequest("/7", server.address(), HttpMethod.HEAD, true, false);
		doTestContentLengthHeadRequest("/8", server.address(), HttpMethod.HEAD, false, true);
		doTestContentLengthHeadRequest("/9", server.address(), HttpMethod.HEAD, false, false);

		server.dispose();
	}

	private void doTestContentLengthHeadRequest(String url, InetSocketAddress address,
			HttpMethod method, boolean chunk, boolean close) {
		Mono<Tuple2<HttpHeaders, String>> response =
				HttpClient.create()
				          .addressSupplier(() -> address)
				          .wiretap()
				          .request(method)
				          .uri(url)
				          .responseSingle((res, buf) -> Mono.zip(Mono.just(res.responseHeaders()),
				                                                 buf.asString()
				                                                    .defaultIfEmpty("NO BODY")));

		StepVerifier.create(response)
				    .expectNextMatches(t -> {
				        if (chunk) {
				            String chunked = t.getT1().get("Transfer-Encoding");
				            if (HttpMethod.GET.equals(method)) {
				                return chunked != null && "OK".equals(t.getT2());
				            }
				            else {
				                return chunked == null && "NO BODY".equals(t.getT2());
				            }
				        }
				        else if (close) {
				            String connClosed = t.getT1().get("Connection");
				            if (HttpMethod.GET.equals(method)) {
				                return "close".equals(connClosed) && "OK".equals(t.getT2());
				            }
				            else {
				                return "close".equals(connClosed) && "NO BODY".equals(t.getT2());
				            }
				        }
				        else {
				            String length = t.getT1().get("Content-Length");
				            if (HttpMethod.GET.equals(method)) {
				                return Integer.parseInt(length) == 2 && "OK".equals(t.getT2());
				            }
				            else {
				                return Integer.parseInt(length) == 2 && "NO BODY".equals(t.getT2());
				            }
				        }
				    })
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue186() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.status(200).send())
				          .wiretap()
				          .bindNow();

		HttpClient client =
				HttpClient.create(ConnectionProvider.fixed("test", 1))
				          .addressSupplier(server::address)
				          .wiretap();

		try {
			doTestIssue186(client);
			doTestIssue186(client);
		}
		finally {
			server.dispose();
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
	public void testConnectionCloseOnServerError() {
		Flux<String> content =
				Flux.range(1, 3)
				    .doOnNext(i -> {
				        if (i == 3) {
				            throw new RuntimeException("test");
				        }
				    })
				    .map(i -> "foo " + i);

		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.sendString(content))
				          .bindNow();

		AtomicReference<Channel> ch = new AtomicReference<>();
		Flux<ByteBuf> r =
				HttpClient.create()
				          .doOnResponse((res, c) -> ch.set(c.channel()))
						  .port(server.address().getPort())
				          .get()
				          .uri("/")
				          .responseContent();

		StepVerifier.create(r)
		            .expectNextCount(2)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		FutureMono.from(ch.get().closeFuture()).block(Duration.ofSeconds(30));

		server.dispose();
	}

	@Test
	public void contextShouldBeTransferredFromDownStreamToUpsream() {
		AtomicReference<Context> context = new AtomicReference<>();
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.status(200).send())
				          .bindNow();

		HttpClient client =
				HttpClient.create(ConnectionProvider.fixed("test", 1))
				          .addressSupplier(server::address);

		try {

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
		finally {
			server.dispose();
		}

	}

/*
	final int numberOfTests = 1000;

	@Test
	@Ignore
	public void deadlockWhenRedirectsToSameUrl(){
		redirectTests("/login");
	}

	@Test
	@Ignore
	public void okWhenRedirectsToOther(){
		redirectTests("/other");
	}

	public void redirectTests(String url) {
		DisposableServer server = HttpServer.create()
		                                    .tcpConfiguration(tcp -> tcp.host("localhost"))
		                                    .port(9999)
		                                    .handle((req, res) -> {
		                                        if (req.uri()
		                                               .contains("/login") && req.method()
		                                                                         .equals(HttpMethod.POST)) {
		                                            return Mono.<Void>fromRunnable(() -> {
		                                                res.header("Location",
		                                                           "http://localhost:9999" + url)
		                                                   .status(HttpResponseStatus.FOUND);
		                                                       })
		                                                       .publishOn(Schedulers.elastic());
		                                        }
		                                        else {
		                                            return Mono.fromRunnable(() -> {})
		                                                       .publishOn(Schedulers.elastic())
		                                                       .then(res.status(200)
		                                                                .sendHeaders()
		                                                                .then());
		                                        }
		                                    })
		                                    .bindNow(Duration.ofSeconds(30));

		ConnectionProvider pool = ConnectionProvider.fixed("test", 1);

		HttpClient client =
				HttpClient.create(pool)
				          .addressSupplier(() -> server.address());

		try {
			Flux.range(0, this.numberOfTests)
			    .concatMap(i -> client.followRedirect()
			                          .post()
			                          .uri("/login")
			                          .responseContent()
			                          .log("reactor.req."+i)
			                          .then())
			    .blockLast();
		}
		finally {
			server.dispose();
		}

	}*/

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
				DisposableServer d = HttpServer.create()
				          .port(0)
				          .bindNow();

				try {
					HttpServer.create()
					          .port(d.port())
					          .bindNow();
					Assert.fail("illegal-success");
				}
				catch (ChannelBindException e){
					Assert.assertEquals(e.localPort(), d.port());
					e.printStackTrace();
				}
				d.dispose();
	}

	private void doTestIssue309(String path, HttpServer httpServer) {
		DisposableServer server =
				httpServer.handle((req, res) -> res.sendString(Mono.just("Should not be reached")))
				          .bindNow();

		Mono<HttpResponseStatus> status =
				HttpClient.create()
				          .port(server.address().getPort())
				          .get()
				          .uri(path)
				          .responseSingle((res, byteBufMono) -> Mono.just(res.status()));

		StepVerifier.create(status)
		            .expectNextMatches(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE::equals)
		            .expectComplete()
		            .verify();

		server.dispose();
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
				          .wiretap();

		DisposableServer ds = server.bindNow();

		HttpClient.create()
		          .addressSupplier(ds::address)
		          .post()
		          .uri("/")
		          .send(ByteBufFlux.fromString(Mono.just("bodysample")))
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block();

		assertThat(channelRef.get()).isNotNull();
		ds.disposeNow();

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
			return null;
		}
	}
}
