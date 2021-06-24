/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

package reactor.netty.tcp;

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
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.DefaultEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.SocketUtils;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;
import reactor.test.StepVerifier;
import reactor.util.Logger;
import reactor.util.Loggers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class TcpServerTests {

	final Logger log     = Loggers.getLogger(TcpServerTests.class);
	final int    msgs    = 10;
	final int    threads = 4;

	ExecutorService threadPool;
	CountDownLatch  latch;

	@BeforeEach
	public void loadEnv() {
		latch = new CountDownLatch(msgs * threads);
		threadPool = Executors.newCachedThreadPool();
	}

	@AfterEach
	public void cleanup() {
		threadPool.shutdownNow();
		Schedulers.shutdownNow();
	}

	@Test
		public void tcpServerHandlesJsonPojosOverSsl() throws Exception {
		final CountDownLatch latch = new CountDownLatch(2);

		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey())
		                                                   .sslProvider(SslProvider.JDK);
		SslContext clientOptions = SslContextBuilder.forClient()
		                                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                            .sslProvider(SslProvider.JDK)
		                                            .build();

		log.debug("Using SslContext: {}", clientOptions);

		final TcpServer server =
				TcpServer.create()
				         .host("localhost")
				         .secure(sslContextSpec -> sslContextSpec.sslContext(serverOptions));

		ObjectMapper m = new ObjectMapper();

		DisposableServer connectedServer = server.handle((in, out) -> {
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
		                                         .wiretap(true)
		                                         .bindNow();

		assertThat(connectedServer).isNotNull();

		final TcpClient client = TcpClient.create()
		                                  .host("localhost")
		                                  .port(connectedServer.address().getPort())
		                                  .secure(spec -> spec.sslContext(clientOptions));

		Connection connectedClient = client.handle((in, out) -> {
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
		})
		                                   .wiretap(true)
		                                   .connectNow();

		assertThat(connectedClient).isNotNull();

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("Latch was counted down").isTrue();

		connectedClient.disposeNow();
		connectedServer.disposeNow();
	}

	@Test
	@Timeout(2)
	public void testHang() {
		DisposableServer httpServer =
				HttpServer.create()
				          .port(0)
				          .host("0.0.0.0")
				          .route(r -> r.get("/data", (request, response) -> response.send(Mono.empty())))
				          .wiretap(true)
				          .bindNow();

		assertThat(httpServer).isNotNull();

		httpServer.disposeNow();
	}

	@Test
	public void exposesRemoteAddress() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(1);

		DisposableServer server = TcpServer.create()
		                                   .port(port)
		                                   .handle((in, out) -> {

		    in.withConnection(c -> {
		        InetSocketAddress addr = c.address();
		        assertThat(addr.getAddress()).as("remote address is not null").isNotNull();
		        latch.countDown();
		    });

			return Flux.never();
		                                   })
		                                   .wiretap(true)
		                                   .bindNow();

		assertThat(server).isNotNull();

		Connection client = TcpClient.create().port(port)
		                             .handle((in, out) -> out.sendString(Flux.just("Hello World!")))
		                             .wiretap(true)
		                             .connectNow();

		assertThat(client).isNotNull();

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("Latch was counted down").isTrue();

		client.disposeNow();
		server.disposeNow();
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

		DisposableServer connected = server.handle(serverHandler)
		                                   .wiretap(true)
		                                   .bindNow();

		assertThat(connected).isNotNull();

		Connection clientContext =
				client.handle((in, out) -> out.sendString(Flux.just("Hello World!\n", "Hello 11!\n")))
				      .wiretap(true)
				      .connectNow();

		assertThat(clientContext).isNotNull();

		assertThat(latch.await(10, TimeUnit.SECONDS)).as("Latch was counted down").isTrue();

		connected.disposeNow();
		clientContext.disposeNow();
	}

	@Test
	public void testIssue462() throws InterruptedException {

		final CountDownLatch countDownLatch = new CountDownLatch(1);

		DisposableServer server = TcpServer.create()
		                                   .port(0)
		                                   .handle((in, out) -> {
		                                       in.receive()
		                                         .log("channel")
		                                         .subscribe(trip -> countDownLatch.countDown());
		                                       return Flux.never();
		                                   })
		                                   .wiretap(true)
		                                   .bindNow();

		assertThat(server).isNotNull();

		System.out.println("PORT +" + server.address()
		                                    .getPort());

		Connection client = TcpClient.create()
		                             .port(server.address()
		                                         .getPort())
		                             .handle((in, out) -> out.sendString(Flux.just("test")))
		                             .wiretap(true)
		                             .connectNow();

		assertThat(client).isNotNull();

		assertThat(countDownLatch.await(5, TimeUnit.SECONDS)).as("Latch was counted down").isTrue();

		client.disposeNow();
		server.disposeNow();
	}

	@Test
	@Disabled
	public void proxyTest() {
		HttpServer server = HttpServer.create();
		server.route(r -> r.get("/search/{search}",
		                        (in, out) -> HttpClient.create()
		                                               .wiretap(true)
		                                               .get()
		                                               .uri("foaas.herokuapp.com/life/" + in.param("search"))
		                                               .response((repliesOut, buf) -> out.send(buf))))
		      .wiretap(true)
		      .bindNow()
		      .onDispose()
		      .block(Duration.ofSeconds(30));
	}

	@Test
	@Disabled
	public void wsTest() {
		HttpServer server = HttpServer.create();
		server.route(r -> r.get("/search/{search}",
		                        (in, out) -> HttpClient.create()
		                                               .wiretap(true)
		                                               .post()
		                                               .uri("ws://localhost:3000")
		                                               .send((requestOut, o) -> o.sendString(Mono.just("ping")))
		                                               .response((repliesOut, buf) ->  out.sendGroups(buf.window(100)))))
		      .wiretap(true)
		      .bindNow()
		      .onDispose()
		      .block(Duration.ofSeconds(30));
	}

	@Test
	public void gettingOptionsDuplicates() {
		TcpServer server = TcpServer.create().host("example.com").port(123);
		assertThat(server.configure())
		          .isNotSameAs(TcpServerBind.INSTANCE.serverBootstrap)
		          .isNotSameAs(server.configure());
	}

	@Test
	public void sendFileSecure()
			throws CertificateException, SSLException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();

		DisposableServer context =
				TcpServer.create()
				         .secure(spec -> spec.sslContext(sslServer))
				         .handle((in, out) ->
				                 in.receive()
				                   .asString()
				                   .flatMap(word -> "GOGOGO".equals(word) ?
				                            out.sendFile(largeFile).then() :
				                            out.sendString(Mono.just("NOPE"))))
				         .wiretap(true)
				         .bindNow();

		assertThat(context).isNotNull();

		MonoProcessor<String> m1 = MonoProcessor.create();
		MonoProcessor<String> m2 = MonoProcessor.create();

		Connection client1 =
				TcpClient.create()
				         .port(context.address().getPort())
				         .secure(spec -> spec.sslContext(sslClient))
				         .handle((in, out) -> {
				             in.receive()
				               .asString()
				               .log("-----------------CLIENT1")
				               .subscribe(m1::onNext);

				             return out.sendString(Mono.just("gogogo"))
				                       .neverComplete();
				         })
				         .wiretap(true)
				         .connectNow();

		Connection client2 =
				TcpClient.create()
				         .port(context.address().getPort())
				         .secure(spec -> spec.sslContext(sslClient))
				         .handle((in, out) -> {
				             in.receive()
				               .asString(StandardCharsets.UTF_8)
				               .takeUntil(d -> d.contains("<- 1024 mark here"))
				               .reduceWith(String::new, String::concat)
				               .log("-----------------CLIENT2")
				               .subscribe(m2::onNext);

				             return out.sendString(Mono.just("GOGOGO"))
				                       .neverComplete();
				         })
				         .wiretap(true)
				         .connectNow();

		assertThat(client2).isNotNull();

		String client1Response = m1.block();
		String client2Response = m2.block();

		client1.disposeNow();
		client2.disposeNow();
		context.disposeNow();

		assertThat(client1Response).isEqualTo("NOPE");

		assertThat(client2Response)
		          .startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
		          .contains("1024 mark here ->")
		          .contains("<- 1024 mark here")
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

		try (FileSystem zipFs = FileSystems.newFileSystem(path, (ClassLoader) null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);
			assertSendFile(out -> out.sendFileChunked(fromZipFile, 0, fileSize));
		}
	}

	@Test
	public void sendZipFileDefault() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);

		try (FileSystem zipFs = FileSystems.newFileSystem(path, (ClassLoader) null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);
			assertSendFile(out -> out.sendFile(fromZipFile, 0, fileSize));
		}
	}

	private void assertSendFile(Function<NettyOutbound, NettyOutbound> fn) {
		DisposableServer context =
				TcpServer.create()
				         .handle((in, out) ->
				                 in.receive()
				                   .asString()
				                   .flatMap(word -> "GOGOGO".equals(word) ?
				                            fn.apply(out).then() :
				                            out.sendString(Mono.just("NOPE"))))
				         .wiretap(true)
				         .bindNow();

		assertThat(context).isNotNull();

		MonoProcessor<String> m1 = MonoProcessor.create();
		MonoProcessor<String> m2 = MonoProcessor.create();

		Connection client1 =
				TcpClient.create()
				         .port(context.address().getPort())
				         .option(ChannelOption.RCVBUF_ALLOCATOR, new AdaptiveRecvByteBufAllocator(64, 1024, 65536))
				         .handle((in, out) -> {
				             in.receive()
				               .asString()
				               .log("-----------------CLIENT1")
				               .subscribe(m1::onNext);

				             return out.sendString(Mono.just("gogogo"))
				                       .neverComplete();
				         })
				         .wiretap(true)
				         .connectNow();

		Connection client2 =
				TcpClient.create()
				         .port(context.address().getPort())
				         .option(ChannelOption.RCVBUF_ALLOCATOR, new AdaptiveRecvByteBufAllocator(64, 1024, 65536))
				         .handle((in, out) -> {
				             in.receive()
				               .asString(StandardCharsets.UTF_8)
				               .take(2)
				               .reduceWith(String::new, String::concat)
				               .log("-----------------CLIENT2")
				               .subscribe(m2::onNext);

				             return out.sendString(Mono.just("GOGOGO"))
				                       .neverComplete();
				         })
				         .wiretap(true)
				         .connectNow();

		assertThat(client2).isNotNull();

		String client1Response = m1.block();
		String client2Response = m2.block();

		client1.disposeNow();
		client2.disposeNow();
		context.disposeNow();

		assertThat(client1Response).isEqualTo("NOPE");

		assertThat(client2Response)
		          .startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
		          .contains("1024 mark here ->")
		          .contains("<- 1024 mark here")
		          .endsWith("End of File");
	}

	@Test
	@Timeout(2)
	public void startAndAwait() throws InterruptedException {
		AtomicReference<DisposableServer> conn = new AtomicReference<>();
		CountDownLatch startLatch = new CountDownLatch(1);

		Thread t = new Thread(() -> TcpServer.create()
		                                     .handle((in, out) -> out.sendString(Mono.just("foo")))
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
		assertThat(t.isAlive()).isTrue();

		//check that stopping the bnc stops the server
		conn.get().disposeNow();
		t.join();
		assertThat(t.isAlive()).isFalse();
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
		                 .handle((in, out) -> out.send(in.receive()
		                                                 .asString()
		                                                 .map(jsonDecoder)
		                                                 .log()
		                                                 .take(1)
		                                                 .map(pojo -> {
		                                                         assertThat(pojo.getName()).isEqualTo("John Doe");
		                                                         return new Pojo("Jane Doe");
		                                                     })
		                                                 .map(jsonEncoder)))
		                 .wiretap(true)
		                 .bindNow();

		assertThat(server).isNotNull();

		SimpleClient client = new SimpleClient(server.address().getPort(), dataLatch, "{\"name\":\"John Doe\"}");
		client.start();

		assertThat(dataLatch.await(5, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(dataLatch.getCount()).isEqualTo(0);

		assertThat(client.e).isNull();
		assertThat(client.data).isNotNull();
		assertThat(client.data.remaining()).isEqualTo(19);
		assertThat(new String(client.data.array(), Charset.defaultCharset()))
		          .isEqualTo("{\"name\":\"Jane Doe\"}");

		server.disposeNow();
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
				         .handle((in, out) -> in.withConnection(c -> c.addHandler(new JsonObjectDecoder()))
				                                .receive()
				                                .asString()
				                                .log("serve")
				                                .map(jsonDecoder)
				                                .concatMap(Flux::fromArray)
				                                .window(5)
				                                .concatMap(w -> out.send(w.collectList().map(jsonEncoder))))
				         .wiretap(true)
				         .bindNow();

		assertThat(server).isNotNull();

		Connection client = TcpClient.create()
		                             .port(server.address().getPort())
		                             .handle((in, out) -> {
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
		                             .wiretap(true)
		                             .connectNow();

		assertThat(client).isNotNull();

		assertThat(dataLatch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(dataLatch.getCount()).isEqualTo(0);

		server.disposeNow();
		client.disposeNow();
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
		                 .handle((in, out) -> out.sendGroups(in.receive()
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
		                 .wiretap(true)
		                 .bindNow();

		assertThat(server).isNotNull();

		Connection client = TcpClient.create()
		                             .remoteAddress(server::address)
		                             .handle((in, out) -> {
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
		                             .wiretap(true)
		                             .connectNow();

		assertThat(client).isNotNull();

		assertThat(latch.await(10, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(latch.getCount()).isEqualTo(0);

		server.disposeNow();
		client.disposeNow();
	}

	@Test
	public void testEchoWithLineBasedFrameDecoder() throws Exception {
		CountDownLatch latch = new CountDownLatch(2);
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .doOnConnection(c -> c.addHandlerLast("codec",
				                                               new LineBasedFrameDecoder(256)))
				         .handle((in, out) ->
				                 out.sendString(in.receive()
				                                  .asString()
				                                  .doOnNext(s -> {
				                                      if ("4".equals(s)) {
				                                          latch.countDown();
				                                      }
				                                  })
				                                  .map(s -> s + "\n")))
				         .bindNow();

		assertThat(server).isNotNull();

		Connection client =
				TcpClient.create()
				         .port(server.address().getPort())
				         .doOnConnected(c -> c.addHandlerLast("codec",
				                                              new LineBasedFrameDecoder(256)))
				         .handle((in, out) ->
				                 out.sendString(Flux.just("1\n", "2\n", "3\n", "4\n"))
				                    .then(in.receive()
				                            .asString()
				                            .doOnNext(s -> {
				                                if ("4".equals(s)) {
				                                    latch.countDown();
				                                }
				                            })
				                            .then()))
				         .connectNow();

		assertThat(client).isNotNull();

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
	}

	@Test
	public void testChannelGroupClosesAllConnections() throws Exception {
		MonoProcessor<Void> serverConnDisposed = MonoProcessor.create();

		ChannelGroup group = new DefaultChannelGroup(new DefaultEventExecutor());

		CountDownLatch latch = new CountDownLatch(1);

		DisposableServer boundServer =
				TcpServer.create()
				         .port(0)
				         .doOnConnection(c -> {
				             c.onDispose()
				              .subscribe(serverConnDisposed);
				             group.add(c.channel());
				             latch.countDown();
				         })
				         .wiretap(true)
				         .bindNow();

		TcpClient.create()
		         .remoteAddress(boundServer::address)
		         .wiretap(true)
		         .connect()
		         .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		boundServer.disposeNow();

		FutureMono.from(group.close())
		          .block(Duration.ofSeconds(30));

		serverConnDisposed.block(Duration.ofSeconds(5));
	}

	@Test
	public void testIssue688() throws Exception {
		CountDownLatch connected = new CountDownLatch(1);
		CountDownLatch configured = new CountDownLatch(1);
		CountDownLatch disconnected = new CountDownLatch(1);

		ChannelGroup group = new DefaultChannelGroup(new DefaultEventExecutor());

		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .observe((connection, newState) -> {
				             if (newState == ConnectionObserver.State.CONNECTED) {
				                 group.add(connection.channel());
				                 connected.countDown();
				             }
				             else if (newState == ConnectionObserver.State.CONFIGURED) {
				                 configured.countDown();
				             }
				             else if (newState == ConnectionObserver.State.DISCONNECTING) {
				                 disconnected.countDown();
				             }
				         })
				         .wiretap(true)
				         .bindNow();

		TcpClient.create()
		         .remoteAddress(server::address)
		         .wiretap(true)
		         .connect()
		         .subscribe();

		assertThat(connected.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(configured.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		FutureMono.from(group.close())
		          .block(Duration.ofSeconds(30));

		assertThat(disconnected.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		server.disposeNow();
	}

	@Test
	public void testGracefulShutdown() throws Exception {
		CountDownLatch latch1 = new CountDownLatch(2);
		CountDownLatch latch2 = new CountDownLatch(2);
		LoopResources loop = LoopResources.create("testGracefulShutdown");
		DisposableServer disposableServer =
				TcpServer.create()
				         .port(0)
				         .runOn(loop)
				         .doOnConnection(c -> {
				             c.onDispose().subscribe(null, null, latch2::countDown);
				             latch1.countDown();
				         })
				         // Register a channel group, when invoking disposeNow()
				         // the implementation will wait for the active requests to finish
				         .channelGroup(new DefaultChannelGroup(new DefaultEventExecutor()))
				         .handle((in, out) -> out.sendString(Mono.just("delay1000")
				                                                 .delayElement(Duration.ofSeconds(1))))
				         .wiretap(true)
				         .bindNow(Duration.ofSeconds(30));

		TcpClient client = TcpClient.create()
		                            .remoteAddress(disposableServer::address)
		                            .wiretap(true);

		MonoProcessor<String> result = MonoProcessor.create();
		Flux.merge(client.connect(), client.connect())
		    .flatMap(conn ->
		            conn.inbound()
		                .receive()
		                .asString())
		    .collect(Collectors.joining())
		    .subscribe(result);

		assertThat(latch1.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		// Stop accepting incoming requests, wait at most 3s for the active requests to finish
		disposableServer.disposeNow();

		// Dispose the event loop
		loop.disposeLater()
		    .block(Duration.ofSeconds(30));

		assertThat(latch2.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		StepVerifier.create(result)
		            .expectNext("delay1000delay1000")
		            .verifyComplete();
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
				assertThat(ch.isConnected()).isTrue();
				data = ByteBuffer.allocate(len);
				int read = ch.read(data);
				assertThat(read).isGreaterThan(0);
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
