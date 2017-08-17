/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.net.ssl.SSLException;

import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.Test;
import org.testng.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.resources.PoolResources;
import reactor.ipc.netty.tcp.BlockingNettyContext;
import reactor.ipc.netty.tcp.TcpClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Stephane Maldini
 */
public class HttpServerTests {

	@Test
	public void sendFileSecure()
			throws CertificateException, SSLException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient().trustManager(ssc.cert()).build();

		NettyContext context =
				HttpServer.create(opt -> opt.sslContext(sslServer))
				          .newHandler((req, resp) -> resp.sendFile(largeFile))
				          .block();


		HttpClientResponse response =
				HttpClient.create(opt -> opt.port(context.address().getPort())
				                            .sslContext(sslClient))
				          .get("/foo")
				          .block(Duration.ofSeconds(120));

		context.dispose();
		context.onClose().block();

		String body = response.receive().aggregate().asString(StandardCharsets.UTF_8).block();

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
	public void sendZipFileChunked() throws IOException, URISyntaxException {
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
			throws URISyntaxException, IOException, InterruptedException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);

			assertSendFile(out -> out.sendFile(fromZipFile, 0, fileSize));
		}
	}

	private void assertSendFile(Function<HttpServerResponse, NettyOutbound> fn) throws IOException, URISyntaxException {
		NettyContext context =
				HttpServer.create(opt -> opt.host("localhost"))
				          .newHandler((req, resp) -> fn.apply(resp))
				          .block();


		HttpClientResponse response =
				HttpClient.create(opt -> opt.port(context.address().getPort()))
				          .get("/foo")
				          .block(Duration.ofSeconds(120));

		context.dispose();
		context.onClose().block();

		String body = response.receive().aggregate().asString(StandardCharsets.UTF_8).block();

		assertThat(body)
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
				.contains("1024 mark here -><- 1024 mark here")
				.endsWith("End of File");
	}

	//from https://github.com/reactor/reactor-netty/issues/90
	@Test
	public void testRestart() {
		// start a first server with a handler that answers HTTP 200 OK
		NettyContext context = HttpServer.create(8080)
		                                 .newHandler((req, resp) -> resp.status(200)
		                                                                .send().log())
		                                 .block();

		HttpClientResponse response = HttpClient.create(8080).get("/").block();

		// checking the response status, OK
		assertThat(response.status().code()).isEqualTo(200);
		// dispose the Netty context and wait for the channel close
		context.dispose();
		context.onClose().block();

		//REQUIRED - bug pool does not detect/translate properly lifecycle
		HttpResources.reset();

		// create a totally new server instance, with a different handler that answers HTTP 201
		context = HttpServer.create(8080)
		                    .newHandler((req, resp) -> resp.status(201).send()).block();

		response = HttpClient.create(8080).get("/").block();

		// fails, response status is 200 and debugging shows the the previous handler is called
		assertThat(response.status().code()).isEqualTo(201);
		context.dispose();
		context.onClose().block();
	}

	@Test
	public void errorResponseAndReturn() throws Exception {
		NettyContext c = HttpServer.create(0)
		                           .newHandler((req, resp) -> Mono.error(new Exception("returnError")))
		                           .block();

		assertThat(HttpClient.create(c.address()
		                              .getPort())
		                     .get("/return", r -> r.failOnServerError(false))
		                     .block()
		                     .status()
		                     .code()).isEqualTo(500);

		c.dispose();

	}

	@Test
	public void httpPipelining() throws Exception {

		AtomicInteger i = new AtomicInteger();

		NettyContext c = HttpServer.create(0)
		                           .newHandler((req, resp) -> resp.header(HttpHeaderNames.CONTENT_LENGTH, "1")
		                                                          .sendString(Mono.just(i.incrementAndGet())
		                                                                          .flatMap(d -> Mono.delay(
				                                                                          Duration.ofSeconds(
						                                                                          4 - d))
		                                                                                         .map(x -> d + "\n"))))
		                           .block(Duration.ofSeconds(30));

		DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
				HttpMethod.GET,
				"/plaintext");

		CountDownLatch latch = new CountDownLatch(6);

		TcpClient.create(c.address()
		                  .getPort())
		         .newHandler((in, out) -> {
			         in.context()
			           .addHandlerFirst(new HttpClientCodec());

			         in.receiveObject()
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
		         .block(Duration.ofSeconds(30));

		Assert.assertTrue(latch.await(45, TimeUnit.SECONDS));

	}

	@Test
	public void flushOnComplete() {

		Flux<String> test = Flux.range(0, 100)
		                        .map(n -> String.format("%010d", n));

		NettyContext c = HttpServer.create(0)
		                           .newHandler((req, resp) -> resp.sendString(test.map(s -> s + "\n")))
		                           .block(Duration.ofSeconds(30));

		Flux<String> client = HttpClient.create(c.address()
		                                         .getPort())
		                                .get("/")
		                                .block(Duration.ofSeconds(30))
		                                .addHandler(new LineBasedFrameDecoder(10))
		                                .receive()
		                                .asString();

		StepVerifier.create(client)
		            .expectNextSequence(test.toIterable())
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void keepAlive() throws URISyntaxException {
		Path resource = Paths.get(getClass().getResource("/public").toURI());
		NettyContext c = HttpServer.create(0)
		                           .newRouter(routes -> routes.directory("/test", resource))
		                           .block(Duration.ofSeconds(30));

		HttpResources.set(PoolResources.fixed("http", 1));

		HttpClientResponse response0 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/index.html")
		                                         .block(Duration.ofSeconds(30));

		HttpClientResponse response1 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/test.css")
		                                         .block(Duration.ofSeconds(30));

		HttpClientResponse response2 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/test1.css")
		                                         .block(Duration.ofSeconds(30));

		HttpClientResponse response3 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/test2.css")
		                                         .block(Duration.ofSeconds(30));

		HttpClientResponse response4 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/test3.css")
		                                         .block(Duration.ofSeconds(30));

		HttpClientResponse response5 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/test4.css")
		                                         .block(Duration.ofSeconds(30));

		HttpClientResponse response6 = HttpClient.create(opts -> opts.port(c.address().getPort())
		                                                             .disablePool())
		                                         .get("/test/test5.css")
		                                         .block(Duration.ofSeconds(30));

		Assert.assertEquals(response0.channel(), response1.channel());
		Assert.assertEquals(response0.channel(), response2.channel());
		Assert.assertEquals(response0.channel(), response3.channel());
		Assert.assertEquals(response0.channel(), response4.channel());
		Assert.assertEquals(response0.channel(), response5.channel());
		Assert.assertNotEquals(response0.channel(), response6.channel());

		HttpResources.reset();
	}


	@Test
	public void toStringShowsOptions() {
		HttpServer server = HttpServer.create(opt -> opt.host("foo")
		                                                .port(123)
		                                                .compression(987));

		assertThat(server.toString()).isEqualTo("HttpServer: listening on foo:123, gzip over 987 bytes");
	}

	@Test
	public void gettingOptionsDuplicates() {
		HttpServer server = HttpServer.create(opt -> opt.host("foo").port(123).compression(true));
		assertThat(server.options())
		          .isNotSameAs(server.options)
		          .isNotSameAs(server.options());
	}

	@Test
	public void startRouter() {
		BlockingNettyContext facade = HttpServer.create(0)
		                                        .startRouter(routes -> routes.get("/hello",
				                                        (req, resp) -> resp.sendString(Mono.just("hello!"))));

		try {
			assertThat(HttpClient.create(facade.getPort())
			                     .get("/hello")
			                     .block()
			                     .status()
			                     .code()).isEqualTo(200);

			assertThat(HttpClient.create(facade.getPort())
			                     .get("/helloMan", req -> req.failOnClientError(false))
			                     .block()
			                     .status()
			                     .code()).isEqualTo(404);
		}
		finally {
			facade.shutdown();
		}
	}

	@Test
	public void startRouterAndAwait()
			throws InterruptedException, ExecutionException, TimeoutException {
		ExecutorService ex = Executors.newSingleThreadExecutor();
		AtomicReference<BlockingNettyContext> ref = new AtomicReference<>();

		Future<?> f = ex.submit(() -> HttpServer.create(0)
		                                        .startRouterAndAwait(routes -> routes.get("/hello", (req, resp) -> resp.sendString(Mono.just("hello!"))),
				                                        ref::set)
		);

		//if the server cannot be started, a ExecutionException will be thrown instead
		assertThatExceptionOfType(TimeoutException.class)
				.isThrownBy(() -> f.get(1, TimeUnit.SECONDS));

		//the router is not done and is still blocking the thread
		assertThat(f.isDone()).isFalse();
		assertThat(ref.get()).isNotNull().withFailMessage("Server is not initialized after 1s");

		//shutdown the router to unblock the thread
		ref.get().shutdown();
		assertThat(f.isDone()).isTrue();
	}
}
