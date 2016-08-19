/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.WorkQueueProcessor;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.buffer.Buffer;
import reactor.ipc.codec.FrameCodec;
import reactor.ipc.codec.LengthFieldCodec;
import reactor.ipc.codec.StandardCodecs;
import reactor.ipc.netty.common.NettyChannel;
import reactor.ipc.netty.common.NettyCodec;
import reactor.ipc.netty.config.ClientOptions;
import reactor.ipc.netty.config.ServerOptions;
import reactor.ipc.netty.http.HttpClient;
import reactor.ipc.netty.http.HttpServer;
import reactor.ipc.netty.util.SocketUtils;
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
	public void tcpServerHandlesJsonPojosOverSsl()
			throws InterruptedException, CertificateException {
		final CountDownLatch latch = new CountDownLatch(2);

		SslContextBuilder clientOptions = SslContextBuilder.forClient()
		                                                   .trustManager(InsecureTrustManagerFactory.INSTANCE);
		final TcpServer server = TcpServer.create(ServerOptions.on("localhost")
		                                                       .sslSelfSigned()
		);

		server.start(channel -> {
			channel.receive(NettyCodec.json(Pojo.class))
			       .log("conn")
			       .subscribe(data -> {
				       if ("John Doe".equals(data.getName())) {
					       latch.countDown();
				       }
			       });
			return channel.sendString(Mono.just("Hi")).concatWith(Flux.never());
		}).block();

		final TcpClient client = TcpClient.create(ClientOptions.to("localhost", server
				.getListenAddress().getPort())
		                                                       .ssl(clientOptions));



		client.start(ch -> {
			ch.receiveString()
			  .log("receive")
			  .subscribe(data -> {
				  if(data.equals("Hi")){
					  latch.countDown();
				  }
			  });
			return ch.map(Flux.just(new Pojo("John" + " Doe")),
					NettyCodec.json(Pojo.class))
			         .concatWith(Flux.never());
//			return Mono.empty();
		}).block();

		assertTrue("Latch was counted down", latch.await(5, TimeUnit.SECONDS));

		server.shutdown().block();
		client.shutdown();
	}

	@Test
	public void tcpServerHandlesLengthFieldData() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();

		TcpServer server = TcpServer.create(ServerOptions.on(port)
		                                                 .backlog(1000)
		                                                 .reuseAddr(true)
		                                                 .tcpNoDelay(true));

		System.out.println(latch.getCount());

		server.start(ch -> {
			ch.receive(NettyCodec.from(new LengthFieldCodec<>(StandardCodecs.BYTE_ARRAY_CODEC)))
			  .subscribe(new Consumer<byte[]>() {
				  long num = 1;

				  @Override
				  public void accept(byte[] bytes) {
					  latch.countDown();
					  ByteBuffer bb = ByteBuffer.wrap(bytes);
					  if (bb.remaining() < 4) {
						  System.err.println("insufficient len: " + bb.remaining());
					  }
					  int next = bb.getInt();
					  if (next != num++) {
						  System.err.println(this + " expecting: " + next + " but got: " + (num - 1));
					  }
					  else {
						  log.info("received " + (num - 1));
					  }
				  }
			  });
			  return Flux.never();
		  }
		).block();

		start.set(System.currentTimeMillis());
		for (int i = 0; i < threads; i++) {
			threadPool.submit(new LengthFieldMessageWriter(port));
		}

		latch.await(10, TimeUnit.SECONDS);
		System.out.println(latch.getCount());

		assertTrue("Latch was counted down: " + latch.getCount(), latch.getCount() == 0);
		end.set(System.currentTimeMillis());

		double elapsed = (end.get() - start.get()) * 1.0;
		System.out.println("elapsed: " + (int) elapsed + "ms");
		System.out.println("throughput: " + (int) ((msgs * threads) / (elapsed / 1000)) + "/sec");

		server.shutdown();
	}

	@Test
	public void tcpServerHandlesFrameData() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();

		TcpServer server = TcpServer.create(ServerOptions.on(port)
		                                                 .backlog(1000)
		                                                 .reuseAddr(true)
		                                                 .tcpNoDelay(true));

		server.start(ch -> {
			ch.receive(NettyCodec.from(new FrameCodec(2, FrameCodec.LengthField.SHORT)))
			  .log()
			  .subscribe(frame -> {
				short prefix = frame.getPrefix().readShort();
				assertThat("prefix is not 128: "+prefix, prefix == 128);
				Buffer data = frame.getData();
				assertThat("len is not 128: "+data.remaining(), data.remaining() == 128);

				latch.countDown();
			});
			return Flux.never();
		}).block();

		System.out.println("Starting on "+port);
		start.set(System.currentTimeMillis());
		for (int i = 0; i < threads; i++) {
			threadPool.submit(new FramedLengthFieldMessageWriter(port));
		}

		latch.await(100, TimeUnit.SECONDS);
		assertTrue("Latch was not counted down enough :" + latch.getCount()+" left on "+(msgs * threads), latch.getCount() == 0);
		end.set(System.currentTimeMillis());

		double elapsed = (end.get() - start.get()) * 1.0;
		System.out.println("elapsed: " + (int) elapsed + "ms");
		System.out.println("throughput: " + (int) ((msgs * threads) / (elapsed / 1000)) + "/sec");

		server.shutdown();
	}

	@Test
	public void exposesRemoteAddress() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(1);

		TcpServer server = TcpServer.create(port);
		TcpClient client = TcpClient.create("localhost", port);

		server.start(ch -> {
			InetSocketAddress remoteAddr = ch.remoteAddress();
			assertNotNull("remote address is not null", remoteAddr.getAddress());
			latch.countDown();

			return Flux.never();
		}).block();

		client.start(ch -> ch.sendString(Flux.just("Hello World!")))
		      .block();

		assertTrue("latch was counted down", latch.await(5, TimeUnit.SECONDS));

		server.shutdown();
	}

	@Test
	public void exposesNettyPipelineConfiguration() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(2);

		final TcpClient client = TcpClient.create("localhost", port);

		Function<? super NettyChannel, ? extends Publisher<Void>>
				serverHandler = ch -> {
			ch.receiveString()
			  .subscribe(data -> {
				log.info("data " + data + " on " + ch);
				latch.countDown();
			});
			return Flux.never();
		};

		TcpServer server = TcpServer.create(ServerOptions.create()
		                                                 .pipelineConfigurer(pipeline -> pipeline.addLast(new LineBasedFrameDecoder(
				                                                 8 * 1024)))
		                                                 .listen(port)
		);

		server.start(serverHandler).block();

		client.start(ch -> ch.map(Flux.just("Hello World!", "Hello 11!"),
				NettyCodec.linefeed()))
		      .block();

		assertTrue("Latch was counted down", latch.await(10, TimeUnit.SECONDS));

		client.shutdown();
		server.shutdown();
	}

	@Test
	@Ignore
	public void test5() throws Exception {
		//Hot stream of data, could be injected from anywhere
		EmitterProcessor<String> broadcaster = EmitterProcessor.<String>create().connect();

		//Get a reference to the tail of the operation pipeline (microbatching + partitioning)
		final Processor<List<String>, List<String>> processor = WorkQueueProcessor.create(false);

		broadcaster

		  //transform 10 data in a [] of 10 elements or wait up to 1 Second before emitting whatever the list contains
		  .buffer(10, Duration.ofSeconds(1))
		  .log("broadcaster")
		  .subscribe(processor);

		//on a server dispatching data on the default shared dispatcher, and serializing/deserializing as string
		HttpServer httpServer = HttpServer.create(0);

		//Listen for anything exactly hitting the root URI and route the incoming connection request to the callback
		httpServer.get("/", (request) -> {
			//prepare a response header to be appended first before any reply
			request.addResponseHeader("X-CUSTOM", "12345");
			//attach to the shared tail, take the most recent generated substream and merge it to the high level stream
			//returning a stream of String from each microbatch merged
			return request.sendString(
				Flux.from(processor)
				  //split each microbatch data into individual data
				  .flatMap(Flux::fromIterable)
				  .take(Duration.ofSeconds(5))
				  .concatWith(Flux.just("end\n"))
			  );
		});

		httpServer.start().block();


		for (int i = 0; i < 50; i++) {
			Thread.sleep(500);
			broadcaster.onNext(System.currentTimeMillis() + "\n");
		}


		httpServer.shutdown();

	}

	@Test
	public void testIssue462() throws InterruptedException {

		final CountDownLatch countDownLatch = new CountDownLatch(1);

		TcpServer server = TcpServer.create(0);

		server.start(ch -> {
			ch.receive()
			  .log("channel")
			  .subscribe(trip -> {
				countDownLatch.countDown();
			});
			return Flux.never();
		})
		      .block();

		System.out.println("PORT +"+server.getListenAddress().getPort());
		TcpClient client = TcpClient.create("127.0.0.1",
				server.getListenAddress()
				      .getPort());

		client.start(ch -> ch.sendString(Flux.just("test")))
		      .block();

		assertThat("countDownLatch counted down", countDownLatch.await(5, TimeUnit.SECONDS));
	}

	@Test
	@Ignore
	public void proxyTest() throws Exception {
		HttpServer server = HttpServer.create();
		server.get("/search/{search}", requestIn ->
			HttpClient.create()
			          .get("foaas.herokuapp.com/life/" + requestIn.param("search"))
			          .flatMap(repliesOut -> requestIn.send(repliesOut.receive())
			  )
		);
		server.start().block();
		//System.in.read();
		Thread.sleep(1000000);
	}

	@Test
	@Ignore
	public void wsTest() throws Exception {
		HttpServer server = HttpServer.create();
		server.get("/search/{search}", requestIn ->
			HttpClient.create()
			          .get("ws://localhost:3000",
					          requestOut -> requestOut.upgradeToTextWebsocket()
					                                  .concatWith(requestOut.sendString(
							                                  Mono.just("ping")))
			  )
			          .flatMap(repliesOut -> requestIn.sendAndFlush(repliesOut.receive()
			                                                                  .window(100)))
		);
		server.start().block();
		//System.in.read();
		Thread.sleep(1000000);
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
			return "Pojo{" +
					"name='" + name + '\'' +
					'}';
		}
	}

	private class LengthFieldMessageWriter implements Runnable {
		private final Random rand = new Random();
		private final int port;
		private final int length;

		private LengthFieldMessageWriter(int port) {
			this.port = port;
			this.length = rand.nextInt(156) + 100;
		}

		@Override
		public void run() {
			try {
				java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(new InetSocketAddress
						("localhost", port));

				System.out.println("writing " + msgs + " messages of " + length + " byte length...");

				int num = 1;
				start.set(System.currentTimeMillis());
				for (int j = 0; j < msgs; j++) {
					ByteBuffer buff = ByteBuffer.allocate(length + 4);
					buff.putInt(length);
					buff.putInt(num++);
					buff.position(0);
					buff.limit(length + 4);

					ch.write(buff);

					count.incrementAndGet();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private class FramedLengthFieldMessageWriter implements Runnable {
		private final short length = 128;
		private final int port;

		private FramedLengthFieldMessageWriter(int port) {
			this.port = port;
		}

		@Override
		public void run() {
			try {
				java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(new InetSocketAddress
						("localhost", port));

				System.out.println("writing " + msgs + " messages of " + length + " byte length...");

				start.set(System.currentTimeMillis());
				for (int j = 0; j < msgs; j++) {
					ByteBuffer buff = ByteBuffer.allocate(length + 2);

					buff.putShort(length);
					for (int i = 0; i < length; i++) {
						buff.put((byte) 1);
					}
					buff.flip();
					buff.limit(length + 2);

					ch.write(buff);

					count.incrementAndGet();
				}
				ch.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
