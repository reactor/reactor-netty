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
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.buffer.Buffer;
import reactor.ipc.netty.common.NettyCodec;
import reactor.ipc.netty.config.ClientOptions;
import reactor.ipc.netty.http.HttpClient;
import reactor.ipc.netty.tcp.TcpClient;
import reactor.ipc.netty.util.SocketUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Stephane Maldini
 * @since 2.5
 */
public class TcpClientTests {

	private final ExecutorService threadPool = Executors.newCachedThreadPool();
	int                     echoServerPort;
	EchoServer              echoServer;
	int                     abortServerPort;
	ConnectionAbortServer   abortServer;
	int                     timeoutServerPort;
	ConnectionTimeoutServer timeoutServer;
	int                     heartbeatServerPort;
	HeartbeatServer         heartbeatServer;

	@Before
	public void setup() {
		echoServerPort = SocketUtils.findAvailableTcpPort();
		echoServer = new EchoServer(echoServerPort);
		threadPool.submit(echoServer);

		abortServerPort = SocketUtils.findAvailableTcpPort();
		abortServer = new ConnectionAbortServer(abortServerPort);
		threadPool.submit(abortServer);

		timeoutServerPort = SocketUtils.findAvailableTcpPort();
		timeoutServer = new ConnectionTimeoutServer(timeoutServerPort);
		threadPool.submit(timeoutServer);

		heartbeatServerPort = SocketUtils.findAvailableTcpPort();
		heartbeatServer = new HeartbeatServer(heartbeatServerPort);
		threadPool.submit(heartbeatServer);
	}

	@After
	public void cleanup() throws InterruptedException, IOException {
		echoServer.close();
		abortServer.close();
		timeoutServer.close();
		heartbeatServer.close();
		threadPool.shutdown();
		threadPool.awaitTermination(5, TimeUnit.SECONDS);
		Thread.sleep(500);
	}

	@Test
	public void testTcpClient() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);

		TcpClient client = TcpClient.create("localhost", echoServerPort);

		client.startAndAwait(conn -> {
			conn.receive()
			    .log("conn")
			    .subscribe(s -> latch.countDown());

			conn.sendString(Flux.just("Hello World!"))
			    .subscribe();

			return Flux.never();
		});

		latch.await(30, TimeUnit.SECONDS);

		client.shutdown();

		assertThat("latch was counted down", latch.getCount(), is(0L));
	}

	@Test
	public void testTcpClientWithInetSocketAddress() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);

		TcpClient client = TcpClient.create(ClientOptions.create()
		                                                 .connect(new InetSocketAddress(echoServerPort)));

		client.start(input -> {
			input.receive()
			     .subscribe(d -> latch.countDown());
			input.sendString(Flux.just("Hello"))
			     .subscribe();

			return Flux.never();
		}).block(Duration.ofSeconds(5));

		latch.await(5, TimeUnit.SECONDS);

		client.shutdown();

		assertThat("latch was counted down", latch.getCount(), is(0L));
	}

	@Test
	public void tcpClientHandlesLineFeedData() throws InterruptedException {
		final int messages = 100;
		final CountDownLatch latch = new CountDownLatch(messages);
		final List<String> strings = new ArrayList<String>();

		TcpClient client = TcpClient.create("localhost", echoServerPort);

		client.start(channel -> {
			channel.receive(NettyCodec.linefeed())
			       .log("received")
			       .subscribe(s -> {
						strings.add(s);
						latch.countDown();
					});

			channel.map(
			  Flux.range(1, messages)
				.map(i -> "Hello World!")
				.subscribeOn(Schedulers.parallel())
			, NettyCodec.linefeed()).subscribe();

			return Flux.never();
		}).block(Duration.ofSeconds(5));

		assertTrue("Expected messages not received. Received " + strings.size() + " messages: " + strings,
		  latch.await(5, TimeUnit.SECONDS));
		client.shutdown();

		assertEquals(messages, strings.size());
		Set<String> uniqueStrings = new HashSet<String>(strings);
		assertEquals(1, uniqueStrings.size());
		assertEquals("Hello World!", uniqueStrings.iterator().next());
	}

	@Test
	public void closingPromiseIsFulfilled() throws InterruptedException {
		TcpClient client = TcpClient.create("localhost", abortServerPort);

		client.start(null);

		client.shutdown().block(Duration.ofSeconds(30));
	}

	@Test
	public void connectionWillRetryConnectionAttemptWhenItFails() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicLong totalDelay = new AtomicLong();

		TcpClient.create("localhost", abortServerPort + 3)
		         .start(null)
		         .retryWhen(errors -> errors.zipWith(Flux.range(1, 4), (a, b) -> b)
		                                    .flatMap(attempt -> {
			                                    switch (attempt) {
				                                    case 1:
					                                    totalDelay.addAndGet(100);
					                                    return Mono.delayMillis(100L);
				                                    case 2:
					                                    totalDelay.addAndGet(500);
					                                    return Mono.delayMillis(500L);
				                                    case 3:
					                                    totalDelay.addAndGet(1000);
					                                    return Mono.delayMillis(1000L);
				                                    default:
					                                    latch.countDown();
					                                    return Mono.<Long>empty();
			                                    }
		                                    }))
		         .subscribe(System.out::println);

		latch.await(5, TimeUnit.SECONDS);
		assertTrue("latch was counted down:"+latch.getCount(), latch.getCount() == 0 );
		assertThat("totalDelay was >1.6s", totalDelay.get(), greaterThanOrEqualTo(1600L));
	}

	@Test
	public void connectionWillAttemptToReconnectWhenItIsDropped() throws InterruptedException, IOException {
		final CountDownLatch connectionLatch = new CountDownLatch(1);
		final CountDownLatch reconnectionLatch = new CountDownLatch(1);
		TcpClient tcpClient = TcpClient.create("localhost", abortServerPort);

		tcpClient.start(connection -> {
			System.out.println("Start");
			connectionLatch.countDown();
			connection.receive()
			          .subscribe();
			return Flux.never();
		})
		         .repeatWhenEmpty(tries -> tries.take(1).doOnNext(s -> reconnectionLatch
				         .countDown()))
		         .block();

		assertTrue("Initial connection is made", connectionLatch.await(5, TimeUnit.SECONDS));
		assertTrue("A reconnect attempt was made", reconnectionLatch.await(5, TimeUnit.SECONDS));
	}

	@Test
	public void consumerSpecAssignsEventHandlers() throws InterruptedException, IOException {
		final CountDownLatch latch = new CountDownLatch(2);
		final CountDownLatch close = new CountDownLatch(1);
		final AtomicLong totalDelay = new AtomicLong();
		final long start = System.currentTimeMillis();

		TcpClient client = TcpClient.create("localhost", timeoutServerPort);

		client.startAndAwait(p -> {
			  p.on()
				.close(close::countDown)
				.readIdle(500, () -> {
					totalDelay.addAndGet(System.currentTimeMillis() - start);
					latch.countDown();
				})
				.writeIdle(500, () -> {
					totalDelay.addAndGet(System.currentTimeMillis() - start);
					latch.countDown();
				});

			  return Mono.delayMillis(3000).then().log();
		  }
		);

		assertTrue("latch was counted down", latch.await(5, TimeUnit.SECONDS));
		assertTrue("close was counted down", close.await(30, TimeUnit.SECONDS));
		assertThat("totalDelay was >500ms", totalDelay.get(), greaterThanOrEqualTo(500L));
	}

	@Test
	public void readIdleDoesNotFireWhileDataIsBeingRead() throws InterruptedException, IOException {
		final CountDownLatch latch = new CountDownLatch(1);
		long start = System.currentTimeMillis();

		TcpClient client = TcpClient.create("localhost", heartbeatServerPort);

		client.startAndAwait(p -> {
			  p.on()
				.readIdle(500, latch::countDown);
			  return Flux.never();
		  }
		);

		Thread.sleep(700);
		heartbeatServer.close();

		assertTrue(latch.await(500, TimeUnit.SECONDS));

		long duration = System.currentTimeMillis() - start;

		assertThat(duration, is(greaterThanOrEqualTo(500L)));
	}

	@Test
	public void writeIdleDoesNotFireWhileDataIsBeingSent() throws InterruptedException, IOException {
		final CountDownLatch latch = new CountDownLatch(1);
		long start = System.currentTimeMillis();

		TcpClient client = TcpClient.create("localhost", echoServerPort);

		client.startAndAwait(connection -> {
			System.out.println("hello");
			  connection.on()
				.writeIdle(500, latch::countDown);

			  List<Publisher<Void>> allWrites = new ArrayList<>();
			  for (int i = 0; i < 5; i++) {
				  allWrites.add(connection.sendString(Flux.just("a")
				                                          .delayMillis(750)));
			  }
			  return Flux.merge(allWrites);
		  }
		);
		System.out.println("Started");

		assertTrue(latch.await(5, TimeUnit.SECONDS));

		long duration = System.currentTimeMillis() - start;

		assertThat(duration, is(greaterThanOrEqualTo(500l)));
		client.shutdown();
	}

	@Test
	public void nettyNetChannelAcceptsNettyChannelHandlers() throws InterruptedException {
		HttpClient client = HttpClient.create();

		final CountDownLatch latch = new CountDownLatch(1);
		System.out.println(client.get("http://www.google.com/?q=test%20d%20dq")
		      .then(r -> r.receiveString().collectList())
		      .doOnSuccess(v -> latch.countDown())
		      .block());


		assertTrue("Latch didn't time out", latch.await(15, TimeUnit.SECONDS));
	}

	private static final class EchoServer implements Runnable {
		private final    int                 port;
		private final    ServerSocketChannel server;
		private volatile Thread              thread;

		private EchoServer(int port) {
			this.port = port;
			try {
				server = ServerSocketChannel.open();
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void run() {
			try {
				server.socket().bind(new InetSocketAddress(port));
				server.configureBlocking(true);
				thread = Thread.currentThread();
				while (true) {
					SocketChannel ch = server.accept();

					ByteBuffer buffer = ByteBuffer.allocate(Buffer.SMALL_IO_BUFFER_SIZE);
					while (true) {
						int read = ch.read(buffer);
						if (read > 0) {
							buffer.flip();
						}

						int written = ch.write(buffer);
						if (written < 0) {
							throw new IOException("Cannot write to client");
						}
						buffer.rewind();
					}
				}
			} catch (IOException e) {
				// Server closed
			}
		}

		public void close() throws IOException {
			Thread thread = this.thread;
			if (thread != null) {
				thread.interrupt();
			}
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

	private static final class ConnectionAbortServer implements Runnable {
		final            int                 port;
		private final ServerSocketChannel server;

		private ConnectionAbortServer(int port) {
			this.port = port;
			try {
				server = ServerSocketChannel.open();
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void run() {
			try {
				server.configureBlocking(true);
				server.socket().bind(new InetSocketAddress(port));
				while (true) {
					SocketChannel ch = server.accept();
					System.out.println("ABORTING");
					ch.close();
				}
			} catch (Exception e) {
				// Server closed
			}
		}

		public void close() throws IOException {
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

	private static final class ConnectionTimeoutServer implements Runnable {
		final            int                 port;
		private final ServerSocketChannel server;

		private ConnectionTimeoutServer(int port) {
			this.port = port;
			try {
				server = ServerSocketChannel.open();
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void run() {
			try {
				server.configureBlocking(true);
				server.socket().bind(new InetSocketAddress(port));
				while (true) {
					SocketChannel ch = server.accept();
					ByteBuffer buff = ByteBuffer.allocate(1);
					ch.read(buff);
				}
			} catch (IOException e) {
			}
		}

		public void close() throws IOException {
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

	private static final class HeartbeatServer implements Runnable {
		final            int                 port;
		private final ServerSocketChannel server;

		private HeartbeatServer(int port) {
			this.port = port;
			try {
				server = ServerSocketChannel.open();
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void run() {
			try {
				server.configureBlocking(true);
				server.socket().bind(new InetSocketAddress(port));
				while (true) {
					SocketChannel ch = server.accept();
					while (server.isOpen()) {
						ByteBuffer out = ByteBuffer.allocate(1);
						out.put((byte) '\n');
						out.flip();
						ch.write(out);
						Thread.sleep(100);
					}
				}
			} catch (IOException e) {
				// Server closed
			} catch (InterruptedException ie) {

			}
		}

		public void close() throws IOException {
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

}
