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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import reactor.util.retry.Retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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

	@BeforeEach
	void setup() throws Exception {
		echoServerPort = SocketUtils.findAvailableTcpPort();
		echoServer = new EchoServer(echoServerPort);
		echoServerFuture = threadPool.submit(echoServer);
		if (!echoServer.await(10, TimeUnit.SECONDS)) {
			throw new IOException("fail to start test server");
		}

		abortServerPort = SocketUtils.findAvailableTcpPort();
		abortServer = new ConnectionAbortServer(abortServerPort);
		abortServerFuture = threadPool.submit(abortServer);
		if (!abortServer.await(10, TimeUnit.SECONDS)) {
			throw new IOException("fail to start test server");
		}

		timeoutServerPort = SocketUtils.findAvailableTcpPort();
		timeoutServer = new ConnectionTimeoutServer(timeoutServerPort);
		timeoutServerFuture = threadPool.submit(timeoutServer);
		if (!timeoutServer.await(10, TimeUnit.SECONDS)) {
			throw new IOException("fail to start test server");
		}

		heartbeatServerPort = SocketUtils.findAvailableTcpPort();
		heartbeatServer = new HeartbeatServer(heartbeatServerPort);
		heartbeatServerFuture = threadPool.submit(heartbeatServer);
		if (!heartbeatServer.await(10, TimeUnit.SECONDS)) {
			throw new IOException("fail to start test server");
		}
	}

	@AfterEach
	void cleanup() throws Exception {
		echoServer.close();
		abortServer.close();
		timeoutServer.close();
		heartbeatServer.close();
		assertThat(echoServerFuture.get()).isNull();
		assertThat(abortServerFuture.get()).isNull();
		assertThat(timeoutServerFuture.get()).isNull();
		assertThat(heartbeatServerFuture.get()).isNull();
		threadPool.shutdown();
		threadPool.awaitTermination(5, TimeUnit.SECONDS);
		Thread.sleep(500);
	}

	@Test
	void disableSsl() {
		TcpClient secureClient = TcpClient.create()
		                                  .secure();

		assertThat(secureClient.configuration().isSecure()).isTrue();
		assertThat(secureClient.noSSL().configuration().isSecure()).isFalse();
	}

	@Test
	void testTcpClient() throws InterruptedException {
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

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		client.disposeNow();
	}


	@Test
	void testTcpClient1ThreadAcquire() {

		LoopResources resources = LoopResources.create("test", 1, true);


		Connection client = TcpClient.create()
		                             .host("localhost")
		                             .port(echoServerPort)
		                             .runOn(resources)
		                             .wiretap(true)
		                             .connectNow();

		client.disposeNow();
		resources.dispose();

		assertThat(client).as("client was configured").isInstanceOf(ChannelOperations.class);
	}

	@Test
	void testTcpClientWithInetSocketAddress() throws InterruptedException {
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

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch await").isTrue();

		s.disposeNow();
	}

	@Test
	void tcpClientHandlesLineFeedData() throws InterruptedException {
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
					            .then(in.receive()
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

		assertThat(latch.await(15, TimeUnit.SECONDS))
				.as("Expected messages not received. Received " + strings.size() + " messages: " + strings)
				.isTrue();

		assertThat(strings).hasSize(messages);
		client.disposeNow();
	}

	@Test
	void tcpClientHandlesLineFeedDataFixedPool() throws InterruptedException {
		Consumer<? super Connection> channelInit = c -> c
				.addHandler("codec",
				            new LineBasedFrameDecoder(8 * 1024));

		//ConnectionProvider p = ConnectionProvider.fixed("tcpClientHandlesLineFeedDataFixedPool", 1);

		ConnectionProvider p = ConnectionProvider.newConnection();

		tcpClientHandlesLineFeedData(
				TcpClient.create(p)
				         .host("localhost")
				         .port(echoServerPort)
				         .doOnConnected(channelInit)
		);

	}

	@Test
	void tcpClientHandlesLineFeedDataElasticPool() throws InterruptedException {
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
					            .then(in.receive()
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

		assertThat(latch.await(15, TimeUnit.SECONDS))
				.as("Expected messages not received. Received " + strings.size() + " messages: " + strings)
				.isTrue();

		assertThat(strings).hasSize(messages);
	}

	@Test
	void closingPromiseIsFulfilled() {
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
		         .retryWhen(Retry.from(errors -> errors
		                                    .flatMap(attempt -> {
			                                    switch ((int) attempt.totalRetries()) {
				                                    case 0:
					                                    totalDelay.addAndGet(100);
					                                    return Mono.delay(Duration
							                                    .ofMillis(100));
				                                    case 1:
					                                    totalDelay.addAndGet(500);
					                                    return Mono.delay(Duration
							                                    .ofMillis(500));
				                                    case 2:
					                                    totalDelay.addAndGet(1000);
					                                    return Mono.delay(Duration
							                                    .ofSeconds(1));
				                                    default:
					                                    latch.countDown();
					                                    return Mono.<Long>empty();
			                                    }
		                                    })))
		         .subscribe(System.out::println);

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(totalDelay.get()).as("totalDelay was >1.6s").isGreaterThanOrEqualTo(1600L);
	}

	/*Check in details*/
	@Test
	void connectionWillRetryConnectionAttemptWhenItFailsElastic()
			throws InterruptedException {
		connectionWillRetryConnectionAttemptWhenItFails(
				TcpClient.create()
				         .host("localhost")
				         .port(abortServerPort + 3)
				         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100));
	}

	//see https://github.com/reactor/reactor-netty/issues/289
	@Test
	void connectionWillRetryConnectionAttemptWhenItFailsFixedChannelPool()
			throws InterruptedException {
		connectionWillRetryConnectionAttemptWhenItFails(
				TcpClient.create(ConnectionProvider.create("connectionWillRetryConnectionAttemptWhenItFailsFixedChannelPool", 1))
				         .host("localhost")
				         .port(abortServerPort + 3)
				         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100));
	}

	@Test
	void connectionWillAttemptToReconnectWhenItIsDropped()
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
			assertThat(c).isNotNull();
			c.onDispose();

			assertThat(connectionLatch.await(5, TimeUnit.SECONDS)).as("Initial connection is made").isTrue();
			assertThat(reconnectionLatch.await(5, TimeUnit.SECONDS)).as("A reconnect attempt was made").isTrue();
		}
		catch (AbortedException e) {
			// ignored
		}
	}

	@Test
	void testCancelSend() throws InterruptedException {
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

		assertThat(connectionLatch.await(30, TimeUnit.SECONDS)).as("Cancel not propagated").isTrue();
		c.disposeNow();
	}

	@Test
	void consumerSpecAssignsEventHandlers() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(2);
		final CountDownLatch close = new CountDownLatch(1);
		final AtomicLong totalDelay = new AtomicLong();
		final long start = System.currentTimeMillis();

		TcpClient client =
				TcpClient.create()
				         .host("localhost")
				         .port(timeoutServerPort);

		Connection s =
				client.handle((in, out) -> {
				            in.withConnection(c -> c.onDispose(close::countDown));

				            out.withConnection(c -> c.onWriteIdle(200, () -> {
				                totalDelay.addAndGet(System.currentTimeMillis() - start);
				                latch.countDown();
				            }));

				            return Mono.delay(Duration.ofSeconds(1))
				                       .then()
				                       .log();
				      })
				      .wiretap(true)
				      .connectNow();

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch was counted down").isTrue();
		assertThat(close.await(30, TimeUnit.SECONDS)).as("close was counted down").isTrue();
		assertThat(totalDelay.get()).as("totalDelay was > 200ms").isGreaterThanOrEqualTo(200L);

		s.disposeNow();
	}

	@Test
	void readIdleDoesNotFireWhileDataIsBeingRead() throws InterruptedException, IOException {
		final CountDownLatch latch = new CountDownLatch(1);
		long start = System.currentTimeMillis();

		TcpClient client = TcpClient.create()
		                            .port(heartbeatServerPort);

		Connection s =
				client.handle((in, out) -> {
				            in.withConnection(c -> c.onReadIdle(200, latch::countDown));
				            return Flux.never();
				      })
				      .wiretap(true)
				      .connectNow();

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch await").isTrue();
		heartbeatServer.close();

		long duration = System.currentTimeMillis() - start;

		assertThat(duration).isGreaterThanOrEqualTo(200L);
		s.disposeNow();
	}

	@Test
	void writeIdleDoesNotFireWhileDataIsBeingSent() throws InterruptedException {
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

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch await").isTrue();

		long duration = System.currentTimeMillis() - start;

		assertThat(duration).isGreaterThanOrEqualTo(500L);
		client.disposeNow();
	}

	@Test
	void gettingOptionsDuplicates() {
		TcpClient client1 = TcpClient.create();
		TcpClient client2 = client1.host("example.com").port(123);
		assertThat(client2)
				.isNotSameAs(client1)
				.isNotSameAs(((TcpClientConnect) client2).duplicate());
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
	void testIssue600_1() {
		doTestIssue600(true);
	}

	@Test
	void testIssue600_2() {
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
					         .remoteAddress(server::address)
					         .runOn(loop);
		}
		else {
			client =
					TcpClient.create(pool)
					         .remoteAddress(server::address);
		}

		Set<String> threadNames = new ConcurrentSkipListSet<>();
		StepVerifier.create(
				Flux.range(1, 4)
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

		assertThat(threadNames.size()).isGreaterThan(1);
	}

	@Test
	void testRetryOnDifferentAddress() throws Exception {
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
				         .remoteAddress(addressSupplier)
				         .doOnConnected(connection -> latch.countDown())
				         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100)
				         .handle((in, out) -> Mono.never())
				         .wiretap(true)
				         .connect()
				         .retry()
				         .block(Duration.ofSeconds(30));
		assertThat(conn).isNotNull();

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		conn.disposeNow();
		server.disposeNow();
	}

	@Test
	void testReconnectWhenDisconnected() throws Exception {
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

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

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
	void testIssue585_1() throws Exception {
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
				         .remoteAddress(server::address)
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

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(b1.refCnt()).isEqualTo(0);
		b1 = null;
		checkReference(refCheck1);

		assertThat(b2.refCnt()).isEqualTo(0);
		b2 = null;
		checkReference(refCheck2);

		assertThat(b3.refCnt()).isEqualTo(0);
		b3 = null;
		checkReference(refCheck3);

		server.disposeNow();
		conn.disposeNow();
	}

	@Test
	void testIssue585_2() throws Exception {
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
				         .remoteAddress(server::address)
				         .wiretap(true)
				         .connectNow();

		NettyOutbound out = conn.outbound();

		out.sendObject(b1)
		   .then()
		   .block(Duration.ofSeconds(30));

		assertThat(b1.refCnt()).isEqualTo(0);
		b1 = null;
		checkReference(refCheck1);

		out.sendObject(b2)
		   .then()
		   .block(Duration.ofSeconds(30));

		assertThat(b2.refCnt()).isEqualTo(0);
		b2 = null;
		checkReference(refCheck2);

		out.sendObject(b3)
		   .then()
		   .block(Duration.ofSeconds(30));

		assertThat(b3.refCnt()).isEqualTo(0);
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

		assertThat(ref.get()).isNull();
	}

	@Test
	void testTcpClientWithDomainSocketsNIOTransport() {
		LoopResources loop = LoopResources.create("testTcpClientWithDomainSocketsNIOTransport");
		try {
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() ->
						TcpClient.create()
						         .runOn(loop, false)
						         .remoteAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
						         .connectNow());
		}
		finally {
			loop.disposeLater()
			    .block(Duration.ofSeconds(30));
		}
	}

	@Test
	void testTcpClientWithDomainSocketsWithHost() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> TcpClient.create()
		                                   .remoteAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
		                                   .host("localhost")
		                                   .connectNow());
	}

	@Test
	void testTcpClientWithDomainSocketsWithPort() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> TcpClient.create()
		                                   .remoteAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
		                                   .port(1234)
		                                   .connectNow());
	}

	@Test
	@SuppressWarnings({"deprecation", "FutureReturnValueIgnored"})
	void testBootstrapUnsupported() {
		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					// FutureReturnValueIgnored is deliberate
					b.bind();
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					// FutureReturnValueIgnored is deliberate
					b.bind(NetUtil.LOCALHOST, 8000);
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					// FutureReturnValueIgnored is deliberate
					b.bind(8000);
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					// FutureReturnValueIgnored is deliberate
					b.bind(new InetSocketAddress("localhost", 8000));
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					// FutureReturnValueIgnored is deliberate
					b.bind("localhost", 8000);
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> b.channel(io.netty.channel.socket.SocketChannel.class)));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(Bootstrap::clone));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					// FutureReturnValueIgnored is deliberate
					b.connect();
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					// FutureReturnValueIgnored is deliberate
					b.connect(NetUtil.LOCALHOST, 8000);
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					// FutureReturnValueIgnored is deliberate
					b.connect(new InetSocketAddress("localhost", 8000));
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					// FutureReturnValueIgnored is deliberate
					b.connect(new InetSocketAddress("localhost", 8001), new InetSocketAddress("localhost", 8002));
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					// FutureReturnValueIgnored is deliberate
					b.connect("localhost", 8000);
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					b.equals(new Bootstrap());
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					b.hashCode();
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					// FutureReturnValueIgnored is deliberate
					b.register();
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(b -> {
					b.toString();
					return b;
				}));

		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> TcpClient.create().bootstrap(Bootstrap::validate));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testBootstrap() {
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .handle((req, res) -> res.send(req.receive()
				                                           .retain()))
				         .wiretap(true)
				         .bindNow();

		AtomicInteger invoked = new AtomicInteger();
		Connection conn =
				TcpClient.create()
				         .bootstrap(b ->
				             b.attr(AttributeKey.valueOf("testBootstrap"), "testBootstrap")
				              .group(new NioEventLoopGroup())
				              .option(ChannelOption.valueOf("testBootstrap"), "testBootstrap")
				              .remoteAddress(server.address())
				              .resolver(DefaultAddressResolverGroup.INSTANCE)
				              .handler(new ChannelInboundHandlerAdapter() {
				                  @Override
				                  public void channelActive(ChannelHandlerContext ctx) throws Exception {
				                      invoked.set(1);
				                      super.channelActive(ctx);
				                  }
				              }))
				         .connectNow();

		conn.outbound()
		    .sendString(Mono.just("testBootstrap"))
		    .then()
		    .subscribe();

		String result =
				conn.inbound()
				    .receive()
				    .asString()
				    .blockFirst();

		assertThat(result).isEqualTo("testBootstrap");
		assertThat(invoked.get()).isEqualTo(1);

		conn.disposeNow();
		server.disposeNow();
	}

	@Test
	@SuppressWarnings("deprecation")
	void testAddressSupplier() {
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .handle((req, res) -> res.send(req.receive()
				                                           .retain()))
				         .wiretap(true)
				         .bindNow();

		Connection conn =
				TcpClient.create()
				         .addressSupplier(server::address)
				         .connectNow();

		conn.outbound()
				.sendString(Mono.just("testAddressSupplier"))
				.then()
				.subscribe();

		String result =
				conn.inbound()
				    .receive()
				    .asString()
				    .blockFirst();

		assertThat(result).isEqualTo("testAddressSupplier");

		conn.disposeNow();
		server.disposeNow();
	}
}
