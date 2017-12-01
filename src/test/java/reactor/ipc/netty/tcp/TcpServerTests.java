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

package reactor.ipc.netty.tcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.net.ssl.SSLException;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.NetUtil;

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
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
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
		final TcpServer server = TcpServer.create().host("localhost").secure();

		ObjectMapper m = new ObjectMapper();

		DisposableServer connectedServer = server.handler((in, out) -> {
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
		                                   .wiretap()
		                                   .bindNow();

		final TcpClient client = TcpClient.create()
		                                  .host("localhost")
		                                  .port(connectedServer.address().getPort())
		                                  .secure(clientOptions);

		Connection connectedClient = client.handler((in, out) -> {
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
		                                   .wiretap()
		                                   .connectNow();

		assertTrue("Latch was counted down", latch.await(5, TimeUnit.SECONDS));

		connectedClient.dispose();
		connectedServer.dispose();
	}

	@Test(timeout = 10000)
	public void testHang() throws Exception {
		DisposableServer httpServer =
				HttpServer.create()
				          .port(0)
				          .tcpConfiguration(tcpServer -> tcpServer.host("0.0.0.0"))
				          .router(r -> r.get("/data", (request, response) -> {
				                  return response.send(Mono.empty());
				          }))
				          .wiretap()
				          .bindNow();
		httpServer.dispose();
	}

	@Test
	public void exposesRemoteAddress() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(1);

		DisposableServer server = TcpServer.create().port(port)
		                             .handler((in, out) -> {

			in.withConnection(c -> {
				InetSocketAddress addr = c.address();
				assertNotNull("remote address is not null", addr.getAddress());
				latch.countDown();
			});

			                             return Flux.never();
		                             })
		                             .wiretap()
		                             .bindNow();

		Connection client = TcpClient.create().port(port)
		                             .handler((in, out) -> out.sendString(Flux.just(
				                             "Hello World!")))
		                             .wiretap()
		                             .connectNow(Duration.ofDays(10));

		assertTrue("latch was counted down", latch.await(5, TimeUnit.DAYS));

		client.dispose();
		server.dispose();
	}

	@Test
	public void exposesNettyPipelineConfiguration() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(2);

		final TcpClient client = TcpClient.create().port(port);

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

		TcpServer server = TcpServer.create()
		                            .doOnConnection(c -> c.addHandlerLast("codec",
				                                                new LineBasedFrameDecoder(8 * 1024)))
		                            .port(port);

		DisposableServer connected = server.handler(serverHandler)
		                             .wiretap()
		                             .bindNow();

		Connection clientContext =
				client.handler((in, out) -> out.send(Flux.just("Hello World!\n", "Hello 11!\n")
				                                            .map(b -> out.alloc()
				                                                         .buffer()
				                                                         .writeBytes(b.getBytes()))))
				      .wiretap()
				      .connectNow();

		assertTrue("Latch was counted down", latch.await(10, TimeUnit.SECONDS));

		connected.dispose();
		clientContext.dispose();
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
		DisposableServer s = HttpServer.create()
		                         .port(0)
		                         .router(r -> r.get("/", (request, response) -> {
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
		                         .wiretap()
		                         .bindNow();

		for (int i = 0; i < 50; i++) {
			Thread.sleep(500);
			broadcaster.onNext(System.currentTimeMillis() + "\n");
		}

		s.dispose();

	}

	@Test
	public void testIssue462() throws InterruptedException {

		final CountDownLatch countDownLatch = new CountDownLatch(1);

		DisposableServer server = TcpServer.create().port(0)
		                             .handler((in, out) -> {
			                             in.receive()
			                               .log("channel")
			                               .subscribe(trip -> {
				                               countDownLatch.countDown();
			                               });
			                             return Flux.never();
		                             })
		                             .wiretap()
		                             .bindNow();

		System.out.println("PORT +" + server.address()
		                                    .getPort());

		Connection client = TcpClient.create().port(server.address()
		                                           .getPort())
		                             .handler((in, out) -> out.sendString(Flux.just(
				                             "test")))
		                             .wiretap()
		                             .connectNow();

		client.dispose();
		server.dispose();

		assertThat("countDownLatch counted down",
				countDownLatch.await(5, TimeUnit.SECONDS));
	}

	@Test
	@Ignore
	public void proxyTest() throws Exception {
		HttpServer server = HttpServer.create();
		server.router(r -> r.get("/search/{search}",
				(in, out) -> HttpClient.prepare()
				                       .wiretap()
				                       .get()
				                       .uri("foaas.herokuapp.com/life/" + in.param("search"))
				                       .response((repliesOut, buf) -> out.send(buf))))
		      .wiretap()
		      .bindNow()
		      .onDispose()
		      .block(Duration.ofSeconds(30));
	}

	@Test
	@Ignore
	public void wsTest() throws Exception {
		HttpServer server = HttpServer.create();
		server.router(r -> r.get("/search/{search}",
				(in, out) -> HttpClient.prepare()
				                       .wiretap()
				                       .request(HttpMethod.GET)
				                       .uri("ws://localhost:3000")
				                       .send((requestOut, o) -> requestOut.sendWebsocket()
				                                                          .sendString(Mono.just("ping")))
				                       .response((repliesOut, buf) ->  out.sendGroups(buf.window(100)))))
		      .wiretap()
		      .bindNow()
		      .onDispose()
		      .block(Duration.ofSeconds(30));
	}

	@Test
	public void gettingOptionsDuplicates() {
		TcpServer server = TcpServer.create().host("foo").port(123);
		Assertions.assertThat(server.configure())
		          .isNotSameAs(TcpServer.DEFAULT_BOOTSTRAP)
		          .isNotSameAs(server.configure());
	}

	@Test
	public void sendFileSecure()
			throws CertificateException, SSLException, InterruptedException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient().trustManager(ssc.cert()).build();

		DisposableServer context =
				TcpServer.create().secure(sslServer)
				         .handler((in, out) ->
						         in.receive()
						           .asString()
						           .flatMap(word -> "GOGOGO".equals(word) ?
								           out.sendFile(largeFile).then() :
								           out.sendString(Mono.just("NOPE"))
						           )
				         )
				         .wiretap()
				         .bindNow();

		MonoProcessor<String> m1 = MonoProcessor.create();
		MonoProcessor<String> m2 = MonoProcessor.create();

		Connection client1 =
				TcpClient.create()
				         .port(context.address().getPort())
				         .secure(sslClient)
				         .handler((in, out) -> {
					         in.receive()
					           .asString()
					           .log("-----------------CLIENT1")
					           .subscribe(m1::onNext);

					         return out.sendString(Mono.just("gogogo"))
							         .neverComplete();
				         })
				         .wiretap()
				         .connectNow();

		Connection client2 =
				TcpClient.create()
				         .port(context.address().getPort())
				         .secure(sslClient)
				         .handler((in, out) -> {
					         in.receive()
					           .asString(StandardCharsets.UTF_8)
					           .take(2)
					           .reduceWith(String::new, String::concat)
					           .log("-----------------CLIENT2")
					           .subscribe(m2::onNext);

					         return out.sendString(Mono.just("GOGOGO"))
					                   .neverComplete();
				         })
				         .wiretap()
				         .connectNow();

		String client1Response = m1.block();
		String client2Response = m2.block();

		client1.dispose();
		client1.onDispose().block();

		client2.dispose();
		client2.onDispose().block();

		context.dispose();
		context.onDispose().block();

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



		DisposableServer context =
				TcpServer.create()
				         .handler((in, out) ->
						         in.receive()
						           .asString()
						           .flatMap(word -> "GOGOGO".equals(word) ?
								           fn.apply(out).then() :
								           out.sendString(Mono.just("NOPE"))
						           )
				         )
				         .wiretap()
				         .bindNow();

		MonoProcessor<String> m1 = MonoProcessor.create();
		MonoProcessor<String> m2 = MonoProcessor.create();

		Connection client1 =
				TcpClient.create().port(context.address().getPort())
				         .handler((in, out) -> {
					         in.receive()
					           .asString()
					           .log("-----------------CLIENT1")
					           .subscribe(m1::onNext);

					         return out.sendString(Mono.just("gogogo"))
					                   .neverComplete();
				         })
				         .wiretap()
				         .connectNow();

		Connection client2 =
				TcpClient.create().port(context.address().getPort())
				         .handler((in, out) -> {
					         in.receive()
					           .asString(StandardCharsets.UTF_8)
					           .take(2)
					           .reduceWith(String::new, String::concat)
					           .log("-----------------CLIENT2")
					           .subscribe(m2::onNext);

					         return out.sendString(Mono.just("GOGOGO"))
					                   .neverComplete();
				         })
				         .wiretap()
				         .connectNow();

		String client1Response = m1.block();
		String client2Response = m2.block();

		client1.dispose();
		client1.onDispose().block();

		client2.dispose();
		client2.onDispose().block();

		context.dispose();
		context.onDispose().block();

		Assertions.assertThat(client1Response).isEqualTo("NOPE");

		Assertions.assertThat(client2Response)
		          .startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
		          .contains("1024 mark here ->")
		          .contains("<- 1024 mark here")
		          .endsWith("End of File");
	}

	@Test(timeout = 2000)
	public void startAndAwait() throws InterruptedException {
		AtomicReference<DisposableServer> conn = new AtomicReference<>();
		CountDownLatch startLatch = new CountDownLatch(1);

		Thread t = new Thread(() -> TcpServer.create()
		                                     .handler((in, out) -> out.sendString(Mono.just("foo")))
		                                     .bindUntilJavaShutdown(Duration.ofMillis(200),
		                                                            c -> {
		                                                                  conn.set(c);
		                                                                  startLatch.countDown();
		                                                            }));
		t.start();
		//let the server initialize
		startLatch.await();

		//check nothing happens for 200ms
		t.join(200);
		Assertions.assertThat(t.isAlive()).isTrue();

		//check that stopping the bnc stops the server
		conn.get().disposeNow();
		t.join();
		Assertions.assertThat(t.isAlive()).isFalse();
	}

	@Test
	public void tcpServerCanEncodeAndDecodeJSON() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Function<Pojo, ByteBuf> jsonEncoder = pojo -> {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try {
				mapper.writeValue(out, pojo);
			} catch(Exception e) {
				throw new RuntimeException(e);
			}
			return Unpooled.copiedBuffer(out.toByteArray());
		};
		Function<String, Pojo> jsonDecoder = s -> {
			try {
				return mapper.readValue(s, Pojo.class);
			} catch(Exception e) {
				throw new RuntimeException(e);
			}
		};

		CountDownLatch dataLatch = new CountDownLatch(1);

		DisposableServer server =
		        TcpServer.create()
		                 .handler((in, out) -> out.send(in.receive()
		                                                     .asString()
		                                                     .map(jsonDecoder)
		                                                     .log()
		                                                     .take(1)
		                                                     .map(pojo -> {
		                                                         Assertions.assertThat(pojo.getName()).isEqualTo("John Doe");
		                                                         return new Pojo("Jane Doe");
		                                                     })
		                                                     .map(jsonEncoder)))
		                 .wiretap()
		                 .bindNow();

		SimpleClient client = new SimpleClient(server.address().getPort(), dataLatch, "{\"name\":\"John Doe\"}");
		client.start();

		Assertions.assertThat(dataLatch.await(5, TimeUnit.SECONDS)).isTrue();
		Assertions.assertThat(dataLatch.getCount()).isEqualTo(0);

		Assertions.assertThat(client.e).isNull();
		Assertions.assertThat(client.data).isNotNull();
		Assertions.assertThat(client.data.remaining()).isEqualTo(19);
		Assertions.assertThat(new String(client.data.array())).isEqualTo("{\"name\":\"Jane Doe\"}");

		server.dispose();
	}

	@Test
	public void flushEvery5ElementsWithManualDecoding() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Function<List<Pojo>, ByteBuf> jsonEncoder = pojo -> {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try {
				mapper.writeValue(out, pojo);
			} catch(Exception e) {
				throw new RuntimeException(e);
			}
			return Unpooled.copiedBuffer(out.toByteArray());
		};
		Function<String, Pojo[]> jsonDecoder = s -> {
			try {
				return mapper.readValue(s, Pojo[].class);
			} catch(Exception e) {
				throw new RuntimeException(e);
			}
		};

		CountDownLatch dataLatch = new CountDownLatch(10);

		DisposableServer server =
				TcpServer.create()
				         .handler((in, out) -> in.withConnection(c -> c.addHandler(new JsonObjectDecoder()))
				                                    .receive()
				                                    .asString()
				                                    .log("serve")
				                                    .map(jsonDecoder)
				                                    .concatMap(Flux::fromArray)
				                                    .window(5)
				                                    .concatMap(w -> out.send(w.collectList().map(jsonEncoder))))
				         .wiretap()
				         .bindNow();

		Connection client = TcpClient.create().port(server.address().getPort())
		                               .handler((in, out) -> {
			                               in.withConnection(c -> c.addHandler(new JsonObjectDecoder()))
			                                 .receive()
			                                 .asString()
			                                 .log("receive")
			                                 .map(jsonDecoder)
			                                 .concatMap(Flux::fromArray)
			                                 .subscribe(c -> dataLatch.countDown());

			                               return out.send(Flux.range(1, 10)
			                                                   .map(it -> new Pojo("test" + it))
			                                                   .log("send")
			                                                   .collectList()
			                                                   .map(jsonEncoder))
			                                         .neverComplete();
		                               })
		                               .wiretap()
		                               .connectNow();

		Assertions.assertThat(dataLatch.await(30, TimeUnit.SECONDS)).isTrue();
		Assertions.assertThat(dataLatch.getCount()).isEqualTo(0);

		server.dispose();
		client.dispose();
	}

	@Test
	public void retryStrategiesWhenServerFails() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Function<List<Pojo>, ByteBuf> jsonEncoder = pojo -> {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try {
				mapper.writeValue(out, pojo);
			} catch(Exception e) {
				throw new RuntimeException(e);
			}
			return Unpooled.copiedBuffer(out.toByteArray());
		};
		Function<String, Pojo[]> jsonDecoder = s -> {
			try {
				return mapper.readValue(s, Pojo[].class);
			} catch(Exception e) {
				throw new RuntimeException(e);
			}
		};

		int elem = 10;
		CountDownLatch latch = new CountDownLatch(elem);

		final AtomicInteger j = new AtomicInteger();
		DisposableServer server =
		        TcpServer.create().host("localhost")
		                 .handler((in, out) -> out.sendGroups(in.receive()
		                                                           .asString()
		                                                           .map(jsonDecoder)
		                                                           .map(d -> Flux.fromArray(d)
		                                                                         .doOnNext(pojo -> {
		                                                                             if (j.getAndIncrement() < 2) {
		                                                                                 throw new RuntimeException("test");
		                                                                             }
		                                                                         })
		                                                                         .retry(2)
		                                                                         .collectList()
		                                                                         .map(jsonEncoder))
		                                                           .doOnComplete(() -> System.out.println("wow"))
		                                                           .log("flatmap-retry")))
		                 .wiretap()
		                 .bindNow();

		Connection client = TcpClient.create().addressSupplier(server::address)
		                               .handler((in, out) -> {
		                                   in.receive()
		                                     .asString()
		                                     .map(jsonDecoder)
		                                     .concatMap(Flux::fromArray)
		                                     .log("receive")
		                                     .subscribe(c -> latch.countDown());

		                                   return out.send(Flux.range(1, elem)
		                                                       .map(i -> new Pojo("test" + i))
		                                                       .log("send")
		                                                       .collectList()
		                                                       .map(jsonEncoder))
		                                             .neverComplete();
		                               })
		                               .wiretap()
		                               .connectNow();

		Assertions.assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		Assertions.assertThat(latch.getCount()).isEqualTo(0);

		server.dispose();
		client.dispose();
	}

	private static class SimpleClient extends Thread {
		private final int port;
		private final CountDownLatch latch;
		private final String output;
		private ByteBuffer data;
		private Exception e;

		SimpleClient(int port, CountDownLatch latch, String output) {
			this.port = port;
			this.latch = latch;
			this.output = output;
		}

		@Override
		public void run() {
			try {
				SocketChannel ch =
						SocketChannel.open(new InetSocketAddress(NetUtil.LOCALHOST, port));
				int len = ch.write(ByteBuffer.wrap(output.getBytes(Charset.defaultCharset())));
				Assertions.assertThat(ch.isConnected()).isTrue();
				data = ByteBuffer.allocate(len);
				int read = ch.read(data);
				Assertions.assertThat(read).isGreaterThan(0);
				data.flip();
				latch.countDown();
			} catch(Exception e) {
				this.e = e;
			}
		}
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
