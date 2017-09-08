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

package reactor.ipc.netty.tcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.net.ssl.SSLException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.WorkQueueProcessor;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.SocketUtils;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.util.Logger;
import reactor.util.Loggers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class TcpServerTests {

	final Logger log = Loggers.getLogger(TcpServerTests.class);
	ExecutorService threadPool;
	final int msgs    = 10;
	final int threads = 4;

	CountDownLatch latch;
	AtomicLong count = new AtomicLong();
	AtomicLong start = new AtomicLong();
	AtomicLong end   = new AtomicLong();

	@Before
	public void loadEnv() {
		latch = new CountDownLatch(msgs * threads);
		threadPool = Executors.newCachedThreadPool();
	}

	@After
	public void cleanup() {
		threadPool.shutdownNow();
		Schedulers.shutdownNow();
	}

	@Test
	public void tcpServerHandlesJsonPojosOverSsl() throws Exception {
		final CountDownLatch latch = new CountDownLatch(2);

		SslContext clientOptions = SslContextBuilder.forClient()
		                                            .trustManager(
				                                            InsecureTrustManagerFactory.INSTANCE)
		                                            .build();
		final TcpServer server = TcpServer.create(opts -> opts.host("localhost")
		                                                      .sslSelfSigned());

		ObjectMapper m = new ObjectMapper();

		NettyContext connectedServer = server.newHandler((in, out) -> {
			in.receive()
			  .asByteArray()
			  .map(bb -> {
				  try {
					  return m.readValue(bb, Pojo.class);
				  }
				  catch (IOException io) {
					  throw Exceptions.propagate(io);
				  }
			  })
			  .log("conn")
			  .subscribe(data -> {
				  if ("John Doe".equals(data.getName())) {
					  latch.countDown();
				  }
			  });

			return out.sendString(Mono.just("Hi"))
			          .neverComplete();
		})
		                                     .block(Duration.ofSeconds(30));

		final TcpClient client = TcpClient.create(opts -> opts.host("localhost")
		                                                      .port(connectedServer.address().getPort())
		                                                      .sslContext(clientOptions));

		NettyContext connectedClient = client.newHandler((in, out) -> {
			//in
			in.receive()
			  .asString()
			  .log("receive")
			  .subscribe(data -> {
				  if (data.equals("Hi")) {
					  latch.countDown();
				  }
			  });

			//out
			return out.send(Flux.just(new Pojo("John" + " Doe"))
			                    .map(s -> {
				                    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
					                    m.writeValue(os, s);
					                    return out.alloc()
					                             .buffer()
					                             .writeBytes(os.toByteArray());
				                    }
				                    catch (IOException ioe) {
					                    throw Exceptions.propagate(ioe);
				                    }
			                    }))
			          .neverComplete();
//			return Mono.empty();
		})
		                                     .block(Duration.ofSeconds(30));

		assertTrue("Latch was counted down", latch.await(5, TimeUnit.SECONDS));

		connectedClient.dispose();
		connectedServer.dispose();
	}

	@Test(timeout = 10000)
	public void testHang() throws Exception {
		NettyContext httpServer = HttpServer
				.create(opts -> opts.host("0.0.0.0").port(0))
				.newRouter(r -> r.get("/data", (request, response) -> {
					return response.send(Mono.empty());
				})).block(Duration.ofSeconds(30));
		httpServer.dispose();
	}

	@Test
	public void exposesRemoteAddress() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(1);

		NettyContext server = TcpServer.create(port)
		                               .newHandler((in, out) -> {
			                             InetSocketAddress remoteAddr =
					                             in.remoteAddress();
			                             assertNotNull("remote address is not null",
					                             remoteAddr.getAddress());
			                             latch.countDown();

			                             return Flux.never();
		                             })
		                               .block(Duration.ofSeconds(30));

		NettyContext client = TcpClient.create(port)
		                               .newHandler((in, out) -> out.sendString(Flux.just(
				                             "Hello World!")))
		                               .block(Duration.ofSeconds(30));

		assertTrue("latch was counted down", latch.await(5, TimeUnit.SECONDS));

		client.dispose();
		server.dispose();
	}

	@Test
	public void exposesNettyPipelineConfiguration() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(2);

		final TcpClient client = TcpClient.create(port);

		BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>>
				serverHandler = (in, out) -> {
			in.receive()
			  .asString()
			  .subscribe(data -> {
				  log.info("data " + data + " on " + in);
				  latch.countDown();
			  });
			return Flux.never();
		};

		TcpServer server = TcpServer.create(opts -> opts
		                                                 .afterChannelInit(c -> c.pipeline()
		                                                                         .addBefore(
				                                                 NettyPipeline.ReactiveBridge,
				                                                 "codec",
				                                                 new LineBasedFrameDecoder(
						                                                 8 * 1024)))
		                                                 .port(port));

		NettyContext connected = server.newHandler(serverHandler)
		                               .block(Duration.ofSeconds(30));

		client.newHandler((in, out) -> out.send(Flux.just("Hello World!\n", "Hello 11!\n")
		                                            .map(b -> out.alloc()
		                                                        .buffer()
		                                                        .writeBytes(b.getBytes()))))
		      .block(Duration.ofSeconds(30));

		assertTrue("Latch was counted down", latch.await(10, TimeUnit.SECONDS));

		connected.dispose();
	}

	@Test
	@Ignore
	public void test5() throws Exception {
		//Hot stream of data, could be injected from anywhere
		EmitterProcessor<String> broadcaster =
				EmitterProcessor.create();

		//Get a reference to the tail of the operation pipeline (microbatching + partitioning)
		final Processor<List<String>, List<String>> processor =
				WorkQueueProcessor.<List<String>>builder().autoCancel(false).build();

		broadcaster

				//transform 10 data in a [] of 10 elements or wait up to 1 Second before emitting whatever the list contains
				.bufferTimeout(10, Duration.ofSeconds(1))
				.log("broadcaster")
				.subscribe(processor);

		//on a server dispatching data on the default shared dispatcher, and serializing/deserializing as string
		//Listen for anything exactly hitting the root URI and route the incoming connection request to the callback
		NettyContext s = HttpServer.create(0)
		                           .newRouter(r -> r.get("/", (request, response) -> {
			                         //prepare a response header to be appended first before any reply
			                         response.addHeader("X-CUSTOM", "12345");
			                         //attach to the shared tail, take the most recent generated substream and merge it to the high level stream
			                         //returning a stream of String from each microbatch merged
			                         return response.sendString(Flux.from(processor)
			                                                        //split each microbatch data into individual data
			                                                        .flatMap(Flux::fromIterable)
			                                                        .take(Duration.ofSeconds(
					                                                        5))
			                                                        .concatWith(Flux.just(
					                                                        "end\n")));
		                         }))
		                           .block(Duration.ofSeconds(30));

		for (int i = 0; i < 50; i++) {
			Thread.sleep(500);
			broadcaster.onNext(System.currentTimeMillis() + "\n");
		}

		s.dispose();

	}

	@Test
	public void testIssue462() throws InterruptedException {

		final CountDownLatch countDownLatch = new CountDownLatch(1);

		NettyContext server = TcpServer.create(0)
		                               .newHandler((in, out) -> {
			                             in.receive()
			                               .log("channel")
			                               .subscribe(trip -> {
				                               countDownLatch.countDown();
			                               });
			                             return Flux.never();
		                             })
		                               .block(Duration.ofSeconds(30));

		System.out.println("PORT +" + server.address()
		                                    .getPort());

		NettyContext client = TcpClient.create(server.address()
		                                             .getPort())
		                               .newHandler((in, out) -> out.sendString(Flux.just(
				                             "test")))
		                               .block(Duration.ofSeconds(30));

		client.dispose();
		server.dispose();

		assertThat("countDownLatch counted down",
				countDownLatch.await(5, TimeUnit.SECONDS));
	}

	@Test
	@Ignore
	public void proxyTest() throws Exception {
		HttpServer server = HttpServer.create();
		server.newRouter(r -> r.get("/search/{search}",
				(in, out) -> HttpClient.create()
				                       .get("foaas.herokuapp.com/life/" + in.param(
						                       "search"))
				                       .flatMapMany(repliesOut -> out.send(repliesOut.receive()))))
		      .block(Duration.ofSeconds(30))
		      .onClose()
		      .block(Duration.ofSeconds(30));
	}

	@Test
	@Ignore
	public void wsTest() throws Exception {
		HttpServer server = HttpServer.create();
		server.newRouter(r -> r.get("/search/{search}",
				(in, out) -> HttpClient.create()
				                       .get("ws://localhost:3000",
						                       requestOut -> requestOut.sendWebsocket()
						                                               .sendString(Mono.just("ping")))
				                       .flatMapMany(repliesOut -> out.sendGroups(repliesOut.receive()
				                                                                       .window(100)))))
		      .block(Duration.ofSeconds(30))
		      .onClose()
		      .block(Duration.ofSeconds(30));
	}

	@Test
	public void toStringShowsOptions() {
		TcpServer server = TcpServer.create(opt -> opt.host("foo").port(123));

		Assertions.assertThat(server.toString()).isEqualTo("TcpServer: listening on foo:123");
	}

	@Test
	public void gettingOptionsDuplicates() {
		TcpServer server = TcpServer.create(opt -> opt.host("foo").port(123));
		Assertions.assertThat(server.options())
		          .isNotSameAs(server.options)
		          .isNotSameAs(server.options());
	}

	@Test
	public void sendFileSecure()
			throws CertificateException, SSLException, InterruptedException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient().trustManager(ssc.cert()).build();

		NettyContext context =
				TcpServer.create(opt -> opt.sslContext(sslServer))
				         .newHandler((in, out) ->
						         in.receive()
						           .asString()
						           .flatMap(word -> "GOGOGO".equals(word) ?
								           out.sendFile(largeFile).then() :
								           out.sendString(Mono.just("NOPE"))
						           )
				         )
				         .block();

		MonoProcessor<String> m1 = MonoProcessor.create();
		MonoProcessor<String> m2 = MonoProcessor.create();

		NettyContext client1 =
				TcpClient.create(opt -> opt.port(context.address().getPort())
				                           .sslContext(sslClient))
				         .newHandler((in, out) -> {
					         in.receive()
					           .asString()
					           .log("-----------------CLIENT1")
					           .subscribe(m1::onNext);

					         return out.sendString(Mono.just("gogogo"))
							         .neverComplete();
				         })
				         .block();

		NettyContext client2 =
				TcpClient.create(opt -> opt.port(context.address().getPort())
				                           .sslContext(sslClient))
				         .newHandler((in, out) -> {
					         in.receive()
					           .asString(StandardCharsets.UTF_8)
					           .take(2)
					           .reduceWith(String::new, String::concat)
					           .log("-----------------CLIENT2")
					           .subscribe(m2::onNext);

					         return out.sendString(Mono.just("GOGOGO"))
					                   .neverComplete();
				         })
				         .block();

		String client1Response = m1.block();
		String client2Response = m2.block();

		client1.dispose();
		client1.onClose().block();

		client2.dispose();
		client2.onClose().block();

		context.dispose();
		context.onClose().block();

		Assertions.assertThat(client1Response).isEqualTo("NOPE");

		Assertions.assertThat(client2Response)
		          .startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
		          .contains("1024 mark here ->")
		          .contains("<- 1024 mark here")
		          .endsWith("End of File");
	}

	@Test
	public void sendFileChunked() throws InterruptedException, IOException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		long fileSize = Files.size(largeFile);

		assertSendFile(out -> out.sendFileChunked(largeFile, 0, fileSize));
	}

	@Test
	public void sendZipFileChunked()
			throws URISyntaxException, IOException, InterruptedException {
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

	private void assertSendFile(Function<NettyOutbound, NettyOutbound> fn)
			throws InterruptedException {



		NettyContext context =
				TcpServer.create()
				         .newHandler((in, out) ->
						         in.receive()
						           .asString()
						           .flatMap(word -> "GOGOGO".equals(word) ?
								           fn.apply(out).then() :
								           out.sendString(Mono.just("NOPE"))
						           )
				         )
				         .block();

		MonoProcessor<String> m1 = MonoProcessor.create();
		MonoProcessor<String> m2 = MonoProcessor.create();

		NettyContext client1 =
				TcpClient.create(opt -> opt.port(context.address().getPort()))
				         .newHandler((in, out) -> {
					         in.receive()
					           .asString()
					           .log("-----------------CLIENT1")
					           .subscribe(m1::onNext);

					         return out.sendString(Mono.just("gogogo"))
					                   .neverComplete();
				         })
				         .block();

		NettyContext client2 =
				TcpClient.create(opt -> opt.port(context.address().getPort()))
				         .newHandler((in, out) -> {
					         in.receive()
					           .asString(StandardCharsets.UTF_8)
					           .take(2)
					           .reduceWith(String::new, String::concat)
					           .log("-----------------CLIENT2")
					           .subscribe(m2::onNext);

					         return out.sendString(Mono.just("GOGOGO"))
					                   .neverComplete();
				         })
				         .block();

		String client1Response = m1.block();
		String client2Response = m2.block();

		client1.dispose();
		client1.onClose().block();

		client2.dispose();
		client2.onClose().block();

		context.dispose();
		context.onClose().block();

		Assertions.assertThat(client1Response).isEqualTo("NOPE");

		Assertions.assertThat(client2Response)
		          .startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
		          .contains("1024 mark here ->")
		          .contains("<- 1024 mark here")
		          .endsWith("End of File");
	}

	@Test(timeout = 2000)
	public void startAndAwait() throws InterruptedException {
		AtomicReference<BlockingNettyContext> bnc = new AtomicReference<>();
		CountDownLatch startLatch = new CountDownLatch(1);

		Thread t = new Thread(() -> TcpServer.create()
		                                     .startAndAwait((in, out) -> out.sendString(Mono.just("foo")),
				v -> {bnc.set(v);
					                                     startLatch.countDown();
				                                     }));
		t.start();
		//let the server initialize
		startLatch.await();

		//check nothing happens for 200ms
		t.join(200);
		Assertions.assertThat(t.isAlive()).isTrue();

		//check that stopping the bnc stops the server
		bnc.get().shutdown();
		t.join();
		Assertions.assertThat(t.isAlive()).isFalse();
	}

	public static class Pojo {

		private String name;

		private Pojo() {
		}

		private Pojo(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		@Override
		public String toString() {
			return "Pojo{" + "name='" + name + '\'' + '}';
		}
	}

}
