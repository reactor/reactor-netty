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

package reactor.ipc.netty.http.server;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.net.ssl.SSLException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
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
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ResourceLeakDetector;
import org.junit.Ignore;
import org.junit.Test;
import org.testng.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.DisposableChannel;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.resources.PoolResources;
import reactor.ipc.netty.tcp.TcpClient;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Stephane Maldini
 */
public class HttpServerTests {

	@Test
	@Ignore
	public void defaultHttpPort() {
		DisposableServer blockingFacade = HttpServer.create()
		                                      .handler((req, resp) -> resp.sendNotFound())
		                                      .wiretap()
		                                      .bindNow();
		blockingFacade.disposeNow();

		assertThat(blockingFacade.address().getPort())
				.isEqualTo(8080);
	}

	@Test
	public void defaultHttpPortWithAddress() {
		DisposableServer blockingFacade = HttpServer.create()
		                                      .tcpConfiguration(tcpServer -> tcpServer.host("localhost"))
		                                      .handler((req, resp) -> resp.sendNotFound())
		                                      .wiretap()
		                                      .bindNow();
		blockingFacade.disposeNow();

		assertThat(blockingFacade.address().getPort())
				.isEqualTo(8080);
	}

	@Test
	public void releaseInboundChannelOnNonKeepAliveRequest() throws Exception {
		ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

		DisposableServer c = HttpServer.create()
		                         .port(0)
		                         .handler((req, resp) -> resp.status(200).send())
		                         .wiretap()
		                         .bindNow();

		Flux<ByteBuf> src = Flux.range(0, 3)
		                        .map(n -> Unpooled.wrappedBuffer(Integer.toString(n)
		                                                                .getBytes()));

		Flux.range(0, 100)
		    .concatMap(n -> HttpClient.prepare()
		                              .port(c.address().getPort())
		                              .tcpConfiguration(tcpClient -> tcpClient.noSSL())
		                              .wiretap()
		                              .post()
		                              .uri("/return")
		                              .send((r, out) -> r.keepAlive(false)
		                                                 .send(src))
		                              .responseSingle((res, buf) -> Mono.just(res.status().code())))
		    .collectList()
		    .block();

		c.dispose();
	}

	@Test
	public void sendFileSecure()
			throws CertificateException, SSLException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient().trustManager(ssc.cert()).build();

		DisposableServer context =
				HttpServer.create()
				          .tcpConfiguration(tcpServer -> tcpServer.secure(sslServer))
				          .handler((req, resp) -> resp.sendFile(largeFile))
				          .wiretap()
				          .bindNow();


		String body =
				HttpClient.prepare()
				          .port(context.address().getPort())
				          .tcpConfiguration(tcpClient -> tcpClient.secure(sslClient))
				          .wiretap()
				          .get()
				          .uri("/foo")
				          .responseSingle((res, buf) -> buf.asString(StandardCharsets.UTF_8))
				          .block(Duration.ofSeconds(120));

		context.dispose();
		context.onDispose().block();

		assertThat(body)
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
				.contains("1024 mark here -><- 1024 mark here")
				.endsWith("End of File");
	}

	@Test
	public void sendFileChunked() throws IOException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		long fileSize = Files.size(largeFile);
		assertSendFile(out -> out.sendFileChunked(largeFile, 0, fileSize));
	}

	@Test
	public void sendZipFileChunked() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);
		path.toFile().deleteOnExit();

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);
			assertSendFile(out -> out.sendFileChunked(fromZipFile, 0, fileSize));
		}
	}

	@Test
	public void sendZipFileDefault()
			throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);

			assertSendFile(out -> out.sendFile(fromZipFile, 0, fileSize));
		}
	}

	private void assertSendFile(Function<HttpServerResponse, NettyOutbound> fn) {
		DisposableServer context =
				HttpServer.create()
				          .tcpConfiguration(tcpServer -> tcpServer.host("localhost"))
				          .handler((req, resp) -> fn.apply(resp))
				          .wiretap()
				          .bindNow();


		String body =
				HttpClient.prepare()
				          .addressSupplier(() -> context.address())
				          .wiretap()
				          .get()
				          .uri("/foo")
				          .responseSingle((res, buf) -> buf.asString(StandardCharsets.UTF_8))
				          .block(Duration.ofSeconds(120));

		context.dispose();
		context.onDispose().block();

		assertThat(body)
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
				.contains("1024 mark here -><- 1024 mark here")
				.endsWith("End of File");
	}

	@Test
	public void sendFileAsync4096() throws IOException, URISyntaxException {
		doTestSendFileAsync(4096);
	}

	@Test
	public void sendFileAsync1024() throws IOException, URISyntaxException {
		doTestSendFileAsync(1024);
	}

	private void doTestSendFileAsync(int chunk) throws IOException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		Path tempFile = Files.createTempFile(largeFile.getParent(),"temp", ".txt");
		tempFile.toFile().deleteOnExit();

		byte[] fileBytes = Files.readAllBytes(largeFile);
		for (int i = 0; i < 1000; i++) {
			Files.write(tempFile, fileBytes, StandardOpenOption.APPEND);
		}

		ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;
		AsynchronousFileChannel channel =
				AsynchronousFileChannel.open(tempFile, StandardOpenOption.READ);

		Flux<ByteBuf> content =  Flux.create(fluxSink -> {
			fluxSink.onDispose(() -> {
			    try {
			        if (channel != null) {
			            channel.close();
			        }
			    }
			    catch (IOException ignored) {
			    }
			});

			ByteBuffer buf = ByteBuffer.allocate(chunk);
			channel.read(buf, 0, buf, new TestCompletionHandler(channel, fluxSink, allocator, chunk));
		});

		DisposableServer context =
				HttpServer.create()
				          .port(0)
				          .handler((req, resp) -> resp.sendByteArray(req.receive()
				                                                           .aggregate()
				                                                           .asByteArray()))
				          .bindNow();
		byte[] response =
				HttpClient.prepare()
				          .tcpConfiguration(tcp -> tcp.addressSupplier(context::address))
				          .post()
				          .send(content)
				          .responseContent()
				          .aggregate()
				          .asByteArray()
				          .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo(Files.readAllBytes(tempFile));
		context.dispose();
	}

	private static final class TestCompletionHandler implements CompletionHandler<Integer, ByteBuffer> {

		private final AsynchronousFileChannel channel;

		private final FluxSink<ByteBuf> sink;

		private final ByteBufAllocator allocator;

		private final int chunk;

		private AtomicLong position;

		TestCompletionHandler(AsynchronousFileChannel channel, FluxSink<ByteBuf> sink,
							  ByteBufAllocator allocator, int chunk) {
			this.channel = channel;
			this.sink = sink;
			this.allocator = allocator;
			this.chunk = chunk;
			this.position = new AtomicLong(0);
		}

		@Override
		public void completed(Integer read, ByteBuffer dataBuffer) {
			if (read != -1) {
				long pos = this.position.addAndGet(read);
				dataBuffer.flip();
				ByteBuf buf = allocator.buffer().writeBytes(dataBuffer);
				this.sink.next(buf);

				if (!this.sink.isCancelled()) {
					ByteBuffer newByteBuffer = ByteBuffer.allocate(chunk);
					this.channel.read(newByteBuffer, pos, newByteBuffer, this);
				}
			}
			else {
				try {
					if (channel != null) {
						channel.close();
					}
				}
				catch (IOException ignored) {
				}
				this.sink.complete();
			}
		}

		@Override
		public void failed(Throwable exc, ByteBuffer dataBuffer) {
			try {
				if (channel != null) {
					channel.close();
				}
			}
			catch (IOException ignored) {
			}
			this.sink.error(exc);
		}
	}

	//from https://github.com/reactor/reactor-netty/issues/90
	@Test
	public void testRestart() {
		// start a first server with a handler that answers HTTP 200 OK
		DisposableServer context = HttpServer.create()
		                               .port(8080)
		                               .handler((req, resp) -> resp.status(200)
		                                                                .send().log())
		                               .wiretap()
		                               .bindNow();

		int code = HttpClient.prepare()
		                     .port(8080)
		                     .wiretap()
		                     .get()
		                     .uri("/")
		                     .responseSingle((res, buf) -> Mono.just(res.status().code()))
		                     .block();

		// checking the response status, OK
		assertThat(code).isEqualTo(200);
		// dispose the Netty context and wait for the channel close
		context.dispose();
		context.onDispose().block();

		//REQUIRED - bug pool does not detect/translate properly lifecycle
		HttpResources.reset();

		// create a totally new server instance, with a different handler that answers HTTP 201
		context = HttpServer.create()
		                    .port(8080)
		                    .handler((req, resp) -> resp.status(201).send())
		                    .wiretap()
		                    .bindNow();

		code = HttpClient.prepare()
		                     .port(8080)
		                     .wiretap()
		                     .get()
		                     .uri("/")
		                     .responseSingle((res, buf) -> Mono.just(res.status().code()))
		                     .block();

		// fails, response status is 200 and debugging shows the the previous handler is called
		assertThat(code).isEqualTo(201);
		context.dispose();
		context.onDispose().block();
	}

	@Test
	public void errorResponseAndReturn() throws Exception {
		DisposableServer c = HttpServer.create()
		                         .port(0)
		                         .handler((req, resp) -> Mono.error(new Exception("returnError")))
		                         .wiretap()
		                         .bindNow();

		int code =
				HttpClient.prepare()
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
		                              .handler((req, resp) -> resp.header(HttpHeaderNames.CONTENT_LENGTH, "1")
		                                                          .sendString(Mono.just(i.incrementAndGet())
		                                                                          .flatMap(d -> Mono.delay(
				                                                                          Duration.ofSeconds(
						                                                                          4 - d))
		                                                                                         .map(x -> d + "\n"))))
		                              .wiretap()
		                              .bindNow();

		DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
				HttpMethod.GET,
				"/plaintext");

		CountDownLatch latch = new CountDownLatch(6);

		Connection client =TcpClient.create()
		                            .port(server.address().getPort())
		         .handler((in, out) -> {
			         in.withConnection(x -> x
			           .addHandlerFirst(new HttpClientCodec()))

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
		                         .handler((req, resp) -> resp.sendString(test.map(s -> s + "\n")))
		                         .wiretap()
		                         .bindNow();

		Flux<String> client = HttpClient.prepare()
		                                .port(c.address().getPort())
		                                .wiretap()
		                                .doOnResponse(res -> res.addHandler(new LineBasedFrameDecoder(10)))
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
		DisposableServer c = HttpServer.create()
		                         .port(0)
		                         .router(routes -> routes.directory("/test", resource))
		                         .wiretap()
		                         .bindNow();

		HttpResources.set(PoolResources.fixed("http", 1));

		Channel response0 = HttpClient.prepare()
		                              .port(c.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/index.html")
		                              .responseSingle((res, buf) -> Mono.just(res.channel()))
		                              .block(Duration.ofSeconds(30));

		Channel response1 = HttpClient.prepare()
		                              .port(c.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/test.css")
		                              .responseSingle((res, buf) -> Mono.just(res.channel()))
		                              .block(Duration.ofSeconds(30));

		Channel response2 = HttpClient.prepare()
		                              .port(c.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/test1.css")
		                              .responseSingle((res, buf) -> Mono.just(res.channel()))
		                              .block(Duration.ofSeconds(30));

		Channel response3 = HttpClient.prepare()
		                              .port(c.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/test2.css")
		                              .responseSingle((res, buf) -> Mono.just(res.channel()))
		                              .block(Duration.ofSeconds(30));

		Channel response4 = HttpClient.prepare()
		                              .port(c.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/test3.css")
		                              .responseSingle((res, buf) -> Mono.just(res.channel()))
		                              .block(Duration.ofSeconds(30));

		Channel response5 = HttpClient.prepare()
		                              .port(c.address().getPort())
		                              .wiretap()
		                              .get()
		                              .uri("/test/test4.css")
		                              .responseSingle((res, buf) -> Mono.just(res.channel()))
		                              .block(Duration.ofSeconds(30));
/* TODO disable pool?
		HttpClientResponse response6 = HttpClient.create(opts -> opts.port(c.address().getPort())
		                                                             .disablePool())
		                                         .get("/test/test5.css")
		                                         .block(Duration.ofSeconds(30));
*/
		Assert.assertEquals(response0, response1);
		Assert.assertEquals(response0, response2);
		Assert.assertEquals(response0, response3);
		Assert.assertEquals(response0, response4);
		Assert.assertEquals(response0, response5);
		//TODO
		//Assert.assertNotEquals(response0, response6);

		HttpResources.reset();
		c.dispose();
	}

	@Test
	public void gettingOptionsDuplicates() {
		HttpServer server = HttpServer.create()
		                              .port(123)
		                              .tcpConfiguration(tcpServer -> tcpServer.host("foo"))
		                              .compress();
		assertThat(server.tcpConfiguration().configure())
		          .isNotSameAs(HttpServer.DEFAULT_TCP_SERVER)
		          .isNotSameAs(server.tcpConfiguration().configure());
	}

	@Test
	public void startRouter() {
		DisposableServer facade = HttpServer.create()
		                              .port(0)
		                              .router(routes -> routes.get("/hello",
				                                        (req, resp) -> resp.sendString(Mono.just("hello!"))))
		                              .wiretap()
		                              .bindNow();

		try {
			int code =
					HttpClient.prepare()
					          .port(facade.address().getPort())
					          .wiretap()
					          .get()
					          .uri("/hello")
					          .responseSingle((res, buf) -> Mono.just(res.status().code()))
					          .block();
			assertThat(code).isEqualTo(200);

			code = HttpClient.prepare()
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
	public void startRouterAndAwait()
			throws InterruptedException {
		ExecutorService ex = Executors.newSingleThreadExecutor();
		AtomicReference<DisposableServer> ref = new AtomicReference<>();

		Future<?> f = ex.submit(() ->
			    HttpServer.create()
			              .port(0)
			              .router(routes -> routes.get("/hello", (req, resp) -> resp.sendString(Mono.just("hello!"))))
			              .wiretap()
			              .bindUntilJavaShutdown(Duration.ofSeconds(2), c -> ref.set(c))
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
				          .tcpConfiguration(tcpServer -> tcpServer.host("localhost"))
				          .router(r -> r.get("/204-1", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
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
				HttpClient.prepare()
				          .addressSupplier(() -> address)
				          .wiretap()
				          .get()
				          .uri(url)
				          .responseSingle((res, buf) ->
						          Mono.zip(
						          		Mono.just(res.status().code()),
								          Mono.just(res.responseHeaders()),
								          buf.asString().defaultIfEmpty("NO BODY")));

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
		                    return h.contains("Transfer-Encoding") &&
		                           !h.contains("Content-Length") &&
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
				          .tcpConfiguration(tcpServer -> tcpServer.host("localhost"))
				          .router(r -> r.route(req -> req.uri().startsWith("/1"),
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
				                                                })
				                        )
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
				HttpClient.prepare()
				          .addressSupplier(() -> address)
				          .wiretap()
				          .request(method)
				          .send((req, out) -> {
				                 req.send();
				                 return out;
				          })
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
				    .verify();
	}

	@Test
	public void testIssue186() {
		DisposableChannel server =
				HttpServer.create()
				          .port(0)
				          .handler((req, res) -> res.status(200).send())
				          .wiretap()
				          .bindNow();

		HttpClient client =
				HttpClient.prepare(PoolResources.fixed("test", 1))
				          .addressSupplier(() -> server.address())
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
	public void testConnectionCloseOnServerError() throws Exception {
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
				          .handler((req, res) -> res.sendString(content))
				          .bindNow();

		HttpClientResponse r =
				HttpClient.prepare()
						  .port(server.address().getPort())
				          .get()
				          .uri("/")
				          .response()
				          .block(Duration.ofSeconds(30));

		ByteBufFlux response = r.receive();

		StepVerifier.create(response)
		            .expectNextCount(2)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		FutureMono.from(r.channel().closeFuture()).block(Duration.ofSeconds(30));

		r.dispose();
		server.dispose();
	}

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
		                                .handler((req, res) -> {
			                                if (req.uri()
			                                       .contains("/login") && req.method()
			                                                                 .equals(HttpMethod.POST)) {
				                                return Mono.<Void>fromRunnable(() -> {
					                                res.header("Location",
							                                "http://localhost:9999" +
									                                url).status(HttpResponseStatus.FOUND);
				                                })
						                                .publishOn(Schedulers.elastic());
			                                }
			                                else {
				                                return Mono.fromRunnable(() -> {
				                                })
				                                           .publishOn(Schedulers.elastic())
				                                           .then(res.status(200)
				                                                    .sendHeaders()
				                                                    .then());
			                                }
		                                })
		                                .bindNow(Duration.ofSeconds(30));

		PoolResources pool = PoolResources.fixed("test", 1);

		HttpClient client =
				HttpClient.prepare(pool)
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

	}

	@Test
	public void httpServerRequestConfigInjectAttributes() {
		AtomicReference<Channel> channelRef = new AtomicReference<>();
		HttpServer server =
				HttpServer.create()
				          .httpRequestDecoder(opt -> opt.maxInitialLineLength(123)
				                                        .maxHeaderSize(456)
				                                        .maxChunkSize(789)
				                                        .validateHeaders(false)
				                                        .initialBufferSize(10))
				          .handler((req, resp) -> resp.sendNotFound())
				          .tcpConfiguration(tcp -> tcp.doOnConnection(c -> channelRef.set(c.channel())))
				          .wiretap();

		DisposableServer ds = server.bindNow();

		HttpClient.prepare()
		          .addressSupplier(ds::address)
		          .post()
		          .uri("/")
		          .send(ByteBufFlux.fromString(Mono.just("bodysample")))
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block();

		assertThat(channelRef.get()).isNotNull();
		Channel c = channelRef.get();
		HttpServerCodec codec = c.pipeline().get(HttpServerCodec.class);
		HttpObjectDecoder decoder = (HttpObjectDecoder) getValueReflection(codec, "inboundHandler", 1);
		int chunkSize = (Integer) getValueReflection(decoder, "maxChunkSize", 2);
		boolean validate = (Boolean) getValueReflection(decoder, "validateHeaders", 2);

		ds.disposeNow();

		assertThat(chunkSize).as("line length").isEqualTo(789);
		assertThat(validate).as("validate headers").isFalse();
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
