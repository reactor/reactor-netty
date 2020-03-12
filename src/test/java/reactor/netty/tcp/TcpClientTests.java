/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.LineBasedFrameDecoder;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.NettyOutbound;
import reactor.netty.SocketUtils;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.test.StepVerifier;
import reactor.util.Logger;
import reactor.util.Loggers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

/**
 * @author Stephane Maldini
 * @since 2.5
 */
public class TcpClientTests {

	static final Logger log = Loggers.getLogger(TcpClientTests.class);

	private final ExecutorService threadPool = Executors.newCachedThreadPool();
	int                     echoServerPort;
	EchoServer              echoServer;
	Future<?>               echoServerFuture;
	int                     abortServerPort;
	ConnectionAbortServer   abortServer;
	Future<?>               abortServerFuture;
	int                     timeoutServerPort;
	ConnectionTimeoutServer timeoutServer;
	Future<?>               timeoutServerFuture;
	int                     heartbeatServerPort;
	HeartbeatServer         heartbeatServer;
	Future<?>               heartbeatServerFuture;

	@Before
	public void setup() throws Exception {
		echoServerPort = SocketUtils.findAvailableTcpPort();
		echoServer = new EchoServer(echoServerPort);
		echoServerFuture = threadPool.submit(echoServer);
		if(!echoServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("fail to start test server");
		}

		abortServerPort = SocketUtils.findAvailableTcpPort();
		abortServer = new ConnectionAbortServer(abortServerPort);
		abortServerFuture = threadPool.submit(abortServer);
		if(!abortServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("fail to start test server");
		}

		timeoutServerPort = SocketUtils.findAvailableTcpPort();
		timeoutServer = new ConnectionTimeoutServer(timeoutServerPort);
		timeoutServerFuture = threadPool.submit(timeoutServer);
		if(!timeoutServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("fail to start test server");
		}

		heartbeatServerPort = SocketUtils.findAvailableTcpPort();
		heartbeatServer = new HeartbeatServer(heartbeatServerPort);
		heartbeatServerFuture = threadPool.submit(heartbeatServer);
		if(!heartbeatServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("fail to start test server");
		}
	}

	@After
	public void cleanup() throws Exception {
		echoServer.close();
		abortServer.close();
		timeoutServer.close();
		heartbeatServer.close();
		assertNull(echoServerFuture.get());
		assertNull(abortServerFuture.get());
		assertNull(timeoutServerFuture.get());
		assertNull(heartbeatServerFuture.get());
		threadPool.shutdown();
		threadPool.awaitTermination(5, TimeUnit.SECONDS);
		Thread.sleep(500);
	}

	@Test
	public void disableSsl() {
		TcpClient secureClient = TcpClient.create()
		                                  .secure();

		assertTrue(secureClient.isSecure());
		assertFalse(secureClient.noSSL().isSecure());
	}

	@Test
	public void testTcpClient() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);

		Connection client = TcpClient.create()
		                             .host("localhost")
		                             .port(echoServerPort)
		                             .handle((in, out) -> {
			                               in.receive()
			                                 .log("conn")
			                                 .subscribe(s -> latch.countDown());

			                               return out.sendString(Flux.just("Hello World!"))
			                                  .neverComplete();
		                               })
		                             .wiretap(true)
		                             .connectNow();

		assertTrue(latch.await(30, TimeUnit.SECONDS));

		client.disposeNow();

		assertThat("latch was counted down", latch.getCount(), is(0L));
	}


	@Test
	public void testTcpClient1ThreadAcquire() {

		LoopResources resources = LoopResources.create("test", 1, true);


		Connection client = TcpClient.create()
		                             .host("localhost")
		                             .port(echoServerPort)
		                             .runOn(resources)
		                             .wiretap(true)
		                             .connectNow();

		client.disposeNow();
		resources.dispose();

		assertThat("client was configured", client instanceof ChannelOperations);
	}

	@Test
	public void testTcpClientWithInetSocketAddress() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);

		TcpClient client =
				TcpClient.create().port(echoServerPort);

		Connection s = client.handle((in, out) -> {
			in.receive()
			  .subscribe(d -> latch.countDown());

			return out.sendString(Flux.just("Hello"))
			   .neverComplete();
		})
		                     .wiretap(true)
		                     .connectNow(Duration.ofSeconds(5));

		assertTrue(latch.await(5, TimeUnit.SECONDS));

		s.disposeNow();

		assertThat("latch was counted down", latch.getCount(), is(0L));
	}

	@Test
	public void tcpClientHandlesLineFeedData() throws InterruptedException {
		final int messages = 100;
		final CountDownLatch latch = new CountDownLatch(messages);
		final List<String> strings = new ArrayList<>();

		Connection client =
				TcpClient.create()
				         .host("localhost")
				         .port(echoServerPort)
				         .doOnConnected(c -> c.addHandlerLast("codec",
						                                 new LineBasedFrameDecoder(8 * 1024)))
				         .handle((in, out) ->
					        out.sendString(Flux.range(1, messages)
					                            .map(i -> "Hello World!" + i + "\n")
					                            .subscribeOn(Schedulers.parallel()))
					            .then( in.receive()
					                     .asString()
					                     .take(100)
					                     .flatMapIterable(s -> Arrays.asList(s.split("\\n")))
					                     .doOnNext(s -> {
						                     strings.add(s);
						                     latch.countDown();
					                     }).then())
				         )
				         .wiretap(true)
				         .connectNow(Duration.ofSeconds(15));

		assertTrue("Expected messages not received. Received " + strings.size() + " messages: " + strings,
				latch.await(15, TimeUnit.SECONDS));

		assertEquals(messages, strings.size());
		client.disposeNow();
	}

	@Test
	public void tcpClientHandlesLineFeedDataFixedPool() throws InterruptedException {
		Consumer<? super Connection> channelInit = c -> c
				.addHandler("codec",
				            new LineBasedFrameDecoder(8 * 1024));

//		ConnectionProvider p = ConnectionProvider.fixed
//				("tcpClientHandlesLineFeedDataFixedPool", 1);

		ConnectionProvider p = ConnectionProvider.newConnection();

		tcpClientHandlesLineFeedData(
				TcpClient.create(p)
				         .host("localhost")
				         .port(echoServerPort)
				         .doOnConnected(channelInit)
		);

	}

	@Test
	public void tcpClientHandlesLineFeedDataElasticPool() throws InterruptedException {
		Consumer<? super Connection> channelInit = c -> c
				.addHandler("codec",
				            new LineBasedFrameDecoder(8 * 1024));

		tcpClientHandlesLineFeedData(
				TcpClient.create(ConnectionProvider.create("tcpClientHandlesLineFeedDataElasticPool", Integer.MAX_VALUE))
				         .host("localhost")
				         .port(echoServerPort)
				         .doOnConnected(channelInit)
		);
	}

	private void tcpClientHandlesLineFeedData(TcpClient client) throws InterruptedException {
		final int messages = 100;
		final CountDownLatch latch = new CountDownLatch(messages);
		final List<String> strings = new ArrayList<>();

		Connection c = client.handle((in, out) ->
					        out.sendString(Flux.range(1, messages)
					                            .map(i -> "Hello World!" + i + "\n")
					                            .subscribeOn(Schedulers.parallel()))
					            .then( in.receive()
					                     .asString()
					                     .take(100)
					                     .flatMapIterable(s -> Arrays.asList(s.split("\\n")))
					                     .doOnNext(s -> {
						                     strings.add(s);
						                     latch.countDown();
					                     }).then())
				         )
				         .wiretap(true)
				         .connectNow(Duration.ofSeconds(30));

		log.debug("Connected");

		c.onDispose()
		 .log()
		 .block(Duration.ofSeconds(30));

		assertTrue("Expected messages not received. Received " + strings.size() + " messages: " + strings,
				latch.await(15, TimeUnit.SECONDS));

		assertEquals(messages, strings.size());
	}

	@Test
	public void closingPromiseIsFulfilled() {
		TcpClient client =
				TcpClient.newConnection()
				         .host("localhost")
				         .port(abortServerPort);

		client.handle((in, out) -> Mono.empty())
		      .wiretap(true)
		      .connectNow()
		      .disposeNow();
	}

	/*Check in details*/
	private void connectionWillRetryConnectionAttemptWhenItFails(TcpClient client)
			throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicLong totalDelay = new AtomicLong();

		client.handle((in, out) -> Mono.never())
		         .wiretap(true)
		         .connect()
		         .retryWhen(errors -> errors.zipWith(Flux.range(1, 4), (a, b) -> b)
		                                    .flatMap(attempt -> {
			                                    switch (attempt) {
				                                    case 1:
					                                    totalDelay.addAndGet(100);
					                                    return Mono.delay(Duration
							                                    .ofMillis(100));
				                                    case 2:
					                                    totalDelay.addAndGet(500);
					                                    return Mono.delay(Duration
							                                    .ofMillis(500));
				                                    case 3:
					                                    totalDelay.addAndGet(1000);
					                                    return Mono.delay(Duration
							                                    .ofSeconds(1));
				                                    default:
					                                    latch.countDown();
					                                    return Mono.<Long>empty();
			                                    }
		                                    }))
		         .subscribe(System.out::println);

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertEquals("latch was counted down:" + latch.getCount(), 0, latch.getCount());
		assertThat("totalDelay was >1.6s", totalDelay.get(), greaterThanOrEqualTo(1600L));
	}

	/*Check in details*/
	@Test
	public void connectionWillRetryConnectionAttemptWhenItFailsElastic()
			throws InterruptedException {
		connectionWillRetryConnectionAttemptWhenItFails(
				TcpClient.create()
				         .host("localhost")
				         .port(abortServerPort + 3)
				         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100));
	}

	//see https://github.com/reactor/reactor-netty/issues/289
	@Test
	public void connectionWillRetryConnectionAttemptWhenItFailsFixedChannelPool()
			throws InterruptedException {
		connectionWillRetryConnectionAttemptWhenItFails(
				TcpClient.create(ConnectionProvider.create("connectionWillRetryConnectionAttemptWhenItFailsFixedChannelPool", 1))
				         .host("localhost")
				         .port(abortServerPort + 3)
				         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100));
	}

	@Test
	public void connectionWillAttemptToReconnectWhenItIsDropped()
			throws InterruptedException {
		final CountDownLatch connectionLatch = new CountDownLatch(1);
		final CountDownLatch reconnectionLatch = new CountDownLatch(1);

		try {
			TcpClient tcpClient =
					TcpClient.newConnection()
					         .host("localhost")
					         .port(abortServerPort);

			Mono<? extends Connection> handler = tcpClient.handle((in, out) -> {
				log.debug("Start");
				connectionLatch.countDown();
				in.receive()
				  .subscribe();
				return Flux.never();
			})
			.wiretap(true)
			.connect();

			Connection c =
					handler.log()
					       .then(handler.doOnSuccess(s -> reconnectionLatch.countDown()))
					       .block(Duration.ofSeconds(30));
			assertNotNull(c);
			c.onDispose();

			assertTrue("Initial connection is made", connectionLatch.await(5, TimeUnit.SECONDS));
			assertTrue("A reconnect attempt was made", reconnectionLatch.await(5, TimeUnit.SECONDS));
		}
		catch (AbortedException e){
			// ignored
		}
	}

	@Test
	public void testCancelSend() throws InterruptedException {
		final CountDownLatch connectionLatch = new CountDownLatch(3);

		TcpClient tcpClient =
				TcpClient.newConnection()
				         .host("localhost")
		                 .port(echoServerPort);
		Connection c;

		c = tcpClient.handle((i, o) -> {
			o.sendObject(Mono.never()
			                 .doOnCancel(connectionLatch::countDown)
			                 .log("uno"))
			 .then()
			 .subscribe()
			 .dispose();

			Schedulers.parallel()
			          .schedule(() -> o.sendObject(Mono.never()
			                                           .doOnCancel(connectionLatch::countDown)
			                                           .log("dos"))
			                           .then()
			                           .subscribe()
			                           .dispose());

			o.sendObject(Mono.never()
			                 .doOnCancel(connectionLatch::countDown)
			                 .log("tres"))
			 .then()
			 .subscribe()
			 .dispose();

			return Mono.never();
		})
		             .connectNow();

		assertTrue("Cancel not propagated", connectionLatch.await(30, TimeUnit.SECONDS));
		c.disposeNow();
	}

	@Ignore
	public void consumerSpecAssignsEventHandlers()
			throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(2);
		final CountDownLatch close = new CountDownLatch(1);
		final AtomicLong totalDelay = new AtomicLong();
		final long start = System.currentTimeMillis();

		TcpClient client =
				TcpClient.create()
				         .host("localhost")
				         .port(timeoutServerPort);

		Connection s = client.handle((in, out) -> {
			in.withConnection(c -> c.onDispose(close::countDown));

			out.withConnection(c -> c.onWriteIdle(500, () -> {
				totalDelay.addAndGet(System.currentTimeMillis() - start);
				latch.countDown();
			}));

			return Mono.delay(Duration.ofSeconds(3))
			           .then()
			           .log();
		})
		                     .wiretap(true)
		                     .connectNow();

		assertTrue("latch was counted down", latch.await(5, TimeUnit.SECONDS));
		assertTrue("close was counted down", close.await(30, TimeUnit.SECONDS));
		assertThat("totalDelay was >500ms", totalDelay.get(), greaterThanOrEqualTo(500L));
		s.disposeNow();
	}

	@Test
	@Ignore
	public void readIdleDoesNotFireWhileDataIsBeingRead()
			throws InterruptedException, IOException {
		final CountDownLatch latch = new CountDownLatch(1);
		long start = System.currentTimeMillis();

		TcpClient client = TcpClient.create()
		                            .port(heartbeatServerPort);

		Connection s = client.handle((in, out) -> {
			in.withConnection(c -> c.onReadIdle(500, latch::countDown));
			return Flux.never();
		})
		                     .wiretap(true)
		                     .connectNow();

		assertTrue(latch.await(15, TimeUnit.SECONDS));
		heartbeatServer.close();

		long duration = System.currentTimeMillis() - start;

		assertThat(duration, is(greaterThanOrEqualTo(500L)));
		s.disposeNow();
	}

	@Test
	public void writeIdleDoesNotFireWhileDataIsBeingSent()
			throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		long start = System.currentTimeMillis();

		Connection client = TcpClient.create()
		                             .host("localhost")
		                             .port(echoServerPort)
		                             .handle((in, out) -> {
			                               log.debug("hello");
			                               out.withConnection(c -> c.onWriteIdle(500, latch::countDown));

			                               List<Publisher<Void>> allWrites =
					                               new ArrayList<>();
			                               for (int i = 0; i < 5; i++) {
				                               allWrites.add(out.sendString(Flux.just("a")
				                                                                .delayElements(Duration.ofMillis(750))));
			                               }
			                               return Flux.merge(allWrites);
		                               })
		                             .wiretap(true)
		                             .connectNow();

		log.debug("Started");

		assertTrue(latch.await(5, TimeUnit.SECONDS));

		long duration = System.currentTimeMillis() - start;

		assertThat(duration, is(greaterThanOrEqualTo(500L)));
		client.disposeNow();
	}

	@Test
	public void gettingOptionsDuplicates() {
		TcpClient client = TcpClient.create().host("example.com").port(123);
		Assertions.assertThat(client.configure())
		          .isNotSameAs(TcpClient.DEFAULT_BOOTSTRAP)
		          .isNotSameAs(client.configure());
	}

	public static final class EchoServer
			extends CountDownLatch
			implements Runnable {

		private final    int                 port;
		private final    ServerSocketChannel server;
		private volatile Thread              thread;

		public EchoServer(int port) {
			super(1);
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
				server.socket()
				      .bind(new InetSocketAddress(port));
				countDown();
				thread = Thread.currentThread();
				while (true) {
					SocketChannel ch = server.accept();

					ByteBuffer buffer = ByteBuffer.allocate(8192);
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
			}
			catch (IOException e) {
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

	private static final class ConnectionAbortServer
			extends CountDownLatch
			implements Runnable {

		final         int                 port;
		private final ServerSocketChannel server;

		private ConnectionAbortServer(int port) {
			super(1);
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
				server.socket()
				      .bind(new InetSocketAddress(port));
				countDown();
				while (true) {
					SocketChannel ch = server.accept();
					log.debug("ABORTING");
					ch.close();
				}
			}
			catch (Exception e) {
				Loggers.getLogger(this.getClass()).debug("", e);
			}
		}

		public void close() throws IOException {
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

	private static final class ConnectionTimeoutServer
			extends CountDownLatch
			implements Runnable {

		final         int                 port;
		private final ServerSocketChannel server;

		private ConnectionTimeoutServer(int port) {
			super(1);
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
				server.socket()
				      .bind(new InetSocketAddress(port));
				countDown();
				while (true) {
					SocketChannel ch = server.accept();
					ByteBuffer buff = ByteBuffer.allocate(1);
					ch.read(buff);
				}
			}
			catch (IOException e) {
				// ignore
			}
		}

		public void close() throws IOException {
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

	private static final class HeartbeatServer extends CountDownLatch
			implements Runnable {

		final         int                 port;
		private final ServerSocketChannel server;

		private HeartbeatServer(int port) {
			super(1);
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
				server.socket()
				      .bind(new InetSocketAddress(port));
				countDown();
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
			}
			catch (IOException e) {
				// Server closed
			}
			catch (InterruptedException ie) {
				// ignore
			}
		}

		public void close() throws IOException {
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}


	@Test
	public void testIssue600_1() {
		doTestIssue600(true);
	}

	@Test
	public void testIssue600_2() {
		doTestIssue600(false);
	}

	private void doTestIssue600(boolean withLoop) {
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .handle((req, res) -> res.send(req.receive()
				                                           .retain()
				                                           .delaySubscription(Duration.ofSeconds(1))))
				         .wiretap(true)
				         .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("doTestIssue600", 10);
		LoopResources loop = LoopResources.create("test", 4, true);
		TcpClient client;
		if (withLoop) {
			client =
					TcpClient.create(pool)
					         .addressSupplier(server::address)
					         .runOn(loop);
		}
		else {
			client =
					TcpClient.create(pool)
					         .addressSupplier(server::address);
		}

		Set<String> threadNames = new ConcurrentSkipListSet<>();
		StepVerifier.create(
				Flux.range(1,4)
				    .flatMap(i ->
				            client.handle((in, out) -> {
				                threadNames.add(Thread.currentThread().getName());
				                return out.send(Flux.empty());
				            })
				            .connect()))
		            .expectNextCount(4)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		pool.dispose();
		loop.dispose();
		server.disposeNow();

		Assertions.assertThat(threadNames.size()).isGreaterThan(1);
	}

	@Test
	public void testRetryOnDifferentAddress() throws Exception {
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .wiretap(true)
				         .handle((req, res) -> res.sendString(Mono.just("test")))
				         .bindNow();

		final CountDownLatch latch = new CountDownLatch(1);

		Supplier<SocketAddress> addressSupplier = new Supplier<SocketAddress>() {
			int i = 2;

			@Override
			public SocketAddress get() {
				return new InetSocketAddress("localhost", server.port() + i--);
			}
		};

		Connection  conn =
				TcpClient.create()
				         .addressSupplier(addressSupplier)
				         .doOnConnected(connection -> latch.countDown())
				         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100)
				         .handle((in, out) -> Mono.never())
				         .wiretap(true)
				         .connect()
				         .retry()
				         .block(Duration.ofSeconds(30));
		assertNotNull(conn);

		assertTrue(latch.await(30, TimeUnit.SECONDS));

		conn.disposeNow();
		server.disposeNow();
	}

	@Test
	public void testReconnectWhenDisconnected() throws Exception {
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .wiretap(true)
				         .handle((req, res) -> res.sendString(Mono.just("test")))
				         .bindNow();

		final CountDownLatch latch = new CountDownLatch(1);

		TcpClient  client =
				TcpClient.create()
				         .port(echoServerPort)
				         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100)
				         .handle((in, out) -> out.withConnection(Connection::dispose))
				         .wiretap(true);

		connect(client, true, latch);

		assertTrue(latch.await(30, TimeUnit.SECONDS));

		server.disposeNow();
	}

	private void connect(TcpClient  client, boolean reconnect, CountDownLatch latch) {
		client.connect()
		      .subscribe(
		          conn -> {
		              if (reconnect) {
		                  conn.onTerminate()
		                      .subscribe(null, null, () -> connect(client, false, latch));
		              }
		          },
		          null,
		          latch::countDown);
	}

	@Test
	public void testIssue585_1() throws Exception {
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .handle((req, res) -> res.send(req.receive()
				                                           .retain()))
				         .wiretap(true)
				         .bindNow();

		CountDownLatch latch = new CountDownLatch(1);

		byte[] bytes = "test".getBytes(Charset.defaultCharset());
		ByteBuf b1 = Unpooled.wrappedBuffer(bytes);
		ByteBuf b2 = Unpooled.wrappedBuffer(bytes);
		ByteBuf b3 = Unpooled.wrappedBuffer(bytes);

		WeakReference<ByteBuf> refCheck1 = new WeakReference<>(b1);
		WeakReference<ByteBuf> refCheck2 = new WeakReference<>(b2);
		WeakReference<ByteBuf> refCheck3 = new WeakReference<>(b3);

		Connection conn =
				TcpClient.create()
				         .addressSupplier(server::address)
				         .wiretap(true)
				         .connectNow();

		NettyOutbound out = conn.outbound();

		Flux.concatDelayError(
		        out.sendObject(Mono.error(new RuntimeException("test")))
		           .sendObject(b1)
		           .then(),
		        out.sendObject(Mono.error(new RuntimeException("test")))
		           .sendObject(b2)
		           .then(),
		        out.sendObject(Mono.error(new RuntimeException("test")))
		           .sendObject(b3)
		           .then())
		    .doOnError(t -> latch.countDown())
		    .subscribe(conn.disposeSubscriber());

		Assertions.assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		Assertions.assertThat(b1.refCnt()).isEqualTo(0);
		b1 = null;
		checkReference(refCheck1);

		Assertions.assertThat(b2.refCnt()).isEqualTo(0);
		b2 = null;
		checkReference(refCheck2);

		Assertions.assertThat(b3.refCnt()).isEqualTo(0);
		b3 = null;
		checkReference(refCheck3);

		server.disposeNow();
		conn.disposeNow();
	}

	@Test
	public void testIssue585_2() throws Exception {
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .handle((req, res) -> res.send(req.receive()
				                                           .retain()))
				         .wiretap(true)
				         .bindNow();

		byte[] bytes = "test".getBytes(Charset.defaultCharset());
		ByteBuf b1 = Unpooled.wrappedBuffer(bytes);
		ByteBuf b2 = Unpooled.wrappedBuffer(bytes);
		ByteBuf b3 = Unpooled.wrappedBuffer(bytes);

		WeakReference<ByteBuf> refCheck1 = new WeakReference<>(b1);
		WeakReference<ByteBuf> refCheck2 = new WeakReference<>(b2);
		WeakReference<ByteBuf> refCheck3 = new WeakReference<>(b3);

		Connection conn =
				TcpClient.create()
				         .addressSupplier(server::address)
				         .wiretap(true)
				         .connectNow();

		NettyOutbound out = conn.outbound();

		out.sendObject(b1)
		   .then()
		   .block(Duration.ofSeconds(30));

		Assertions.assertThat(b1.refCnt()).isEqualTo(0);
		b1 = null;
		checkReference(refCheck1);

		out.sendObject(b2)
		   .then()
		   .block(Duration.ofSeconds(30));

		Assertions.assertThat(b2.refCnt()).isEqualTo(0);
		b2 = null;
		checkReference(refCheck2);

		out.sendObject(b3)
		   .then()
		   .block(Duration.ofSeconds(30));

		Assertions.assertThat(b3.refCnt()).isEqualTo(0);
		b3 = null;
		checkReference(refCheck3);

		server.disposeNow();
		conn.disposeNow();
	}

	private void checkReference(WeakReference<ByteBuf> ref) throws Exception {
		for (int i = 0; i < 10; i++) {
			if (ref.get() == null) {
				return;
			}
			System.gc();
			Thread.sleep(100);
		}

		Assertions.assertThat(ref.get()).isNull();
	}
}
