/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.LineBasedFrameDecoder;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.SocketUtils;
import reactor.ipc.netty.channel.AbortedException;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.resources.PoolResources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Stephane Maldini
 * @since 2.5
 */
public class TcpClientTests {

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
	public void testTcpClient() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);

		NettyContext client = TcpClient.create("localhost", echoServerPort)
		                               .newHandler((in, out) -> {
			                               in.receive()
			                                 .log("conn")
			                                 .subscribe(s -> latch.countDown());

			                               return out.sendString(Flux.just("Hello World!"))
			                                  .neverComplete();
		                               })
		                               .block(Duration.ofSeconds(30));

		latch.await(30, TimeUnit.SECONDS);

		client.dispose();

		assertThat("latch was counted down", latch.getCount(), is(0L));
	}

	@Test
	public void testTcpClientWithInetSocketAddress() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);

		TcpClient client =
				TcpClient.create(echoServerPort);

		NettyContext s = client.newHandler((in, out) -> {
			in.receive()
			  .subscribe(d -> latch.countDown());

			return out.sendString(Flux.just("Hello"))
			   .neverComplete();
		})
		                       .block(Duration.ofSeconds(5));

		latch.await(5, TimeUnit.SECONDS);

		s.dispose();

		assertThat("latch was counted down", latch.getCount(), is(0L));
	}

	@Test
	public void tcpClientHandlesLineFeedData() throws InterruptedException {
		final int messages = 100;
		final CountDownLatch latch = new CountDownLatch(messages);
		final List<String> strings = new ArrayList<String>();

				TcpClient.create(opts -> opts.host("localhost")
				                             .port(echoServerPort)
				                             .afterChannelInit(c -> c.pipeline()
				                                                     .addBefore(
						                                                     NettyPipeline.ReactiveBridge,
						                                                     "codec",
						                                                     new LineBasedFrameDecoder(
								                                                     8 * 1024)))
				)
				         .newHandler((in, out) ->
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
				         .block(Duration.ofSeconds(15))
				         .onClose()
				         .block(Duration.ofSeconds(30));

		assertTrue("Expected messages not received. Received " + strings.size() + " messages: " + strings,
				latch.await(15, TimeUnit.SECONDS));

		assertEquals(messages, strings.size());
	}

	@Test
	public void tcpClientHandlesLineFeedDataFixedPool() throws InterruptedException {
		Consumer<? super Channel> channelInit = c -> c
				.pipeline()
				.addBefore(NettyPipeline.ReactiveBridge,
						"codec",
						new LineBasedFrameDecoder(8 * 1024));

		tcpClientHandlesLineFeedData(opts -> opts
				.host("localhost")
				    .port(echoServerPort)
				    .poolResources(PoolResources.fixed("tcpClientHandlesLineFeedDataFixedPool", 1))
				    .afterChannelInit(channelInit)
		);
	}

	@Test
	public void tcpClientHandlesLineFeedDataElasticPool() throws InterruptedException {
		Consumer<? super Channel> channelInit = c -> c
				.pipeline()
				.addBefore(NettyPipeline.ReactiveBridge,
						"codec",
						new LineBasedFrameDecoder(8 * 1024));

		tcpClientHandlesLineFeedData(opts -> opts
				.host("localhost")
				    .port(echoServerPort)
				    .poolResources(PoolResources.elastic("tcpClientHandlesLineFeedDataElasticPool"))
				    .afterChannelInit(channelInit)
		);
	}

	private void tcpClientHandlesLineFeedData(Consumer<? super ClientOptions.Builder<?>> opsBuilder) throws InterruptedException {
		final int messages = 100;
		final CountDownLatch latch = new CountDownLatch(messages);
		final List<String> strings = new ArrayList<>();

		TcpClient.create(opsBuilder)
				         .newHandler((in, out) ->
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
				         .block(Duration.ofSeconds(15))
				         .onClose()
				         .block(Duration.ofSeconds(30));

		assertTrue("Expected messages not received. Received " + strings.size() + " messages: " + strings,
				latch.await(15, TimeUnit.SECONDS));

		assertEquals(messages, strings.size());
	}

	@Test
	public void closingPromiseIsFulfilled() {
		TcpClient client =
				TcpClient.create(opts -> opts.host("localhost")
				                             .port(abortServerPort)
				                             .disablePool());

		client.newHandler((in, out) -> Mono.empty())
		      .block(Duration.ofSeconds(30))
		      .onClose()
		      .block(Duration.ofSeconds(30));
	}

	private void connectionWillRetryConnectionAttemptWhenItFails(Consumer<ClientOptions.Builder<?>> opsBuilder)
			throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicLong totalDelay = new AtomicLong();

		TcpClient.create(opsBuilder)
		         .newHandler((in, out) -> Mono.never())
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

		latch.await(5, TimeUnit.SECONDS);
		assertTrue("latch was counted down:" + latch.getCount(), latch.getCount() == 0);
		assertThat("totalDelay was >1.6s", totalDelay.get(), greaterThanOrEqualTo(1600L));
	}

	@Test
	public void connectionWillRetryConnectionAttemptWhenItFailsElastic()
			throws InterruptedException {
		connectionWillRetryConnectionAttemptWhenItFails(ops -> ops
				.host("localhost")
				.port(abortServerPort + 3)
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100));
	}

	//see https://github.com/reactor/reactor-netty/issues/289
	@Test
	public void connectionWillRetryConnectionAttemptWhenItFailsFixedChannelPool()
			throws InterruptedException {
		connectionWillRetryConnectionAttemptWhenItFails(ops -> ops
				.host("localhost")
				.port(abortServerPort + 3)
				.poolResources(PoolResources.fixed("test", 1))
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100));
	}

	@Test
	public void connectionWillAttemptToReconnectWhenItIsDropped()
			throws InterruptedException {
		final CountDownLatch connectionLatch = new CountDownLatch(1);
		final CountDownLatch reconnectionLatch = new CountDownLatch(1);

		try {
			TcpClient tcpClient =
					TcpClient.create(opts -> opts.host("localhost")
					                             .port(abortServerPort)
					                             .disablePool());

			Mono<? extends NettyContext> handler = tcpClient.newHandler((in, out) -> {
				System.out.println("Start");
				connectionLatch.countDown();
				in.receive()
				  .subscribe();
				return Flux.never();
			});

			handler.log()
			       .block(Duration.ofSeconds(30))
			       .onClose()
			       .then(handler.doOnSuccess(s -> reconnectionLatch.countDown()))
			       .block(Duration.ofSeconds(30));

			assertTrue("Initial connection is made", connectionLatch.await(5, TimeUnit.SECONDS));
			assertTrue("A reconnect attempt was made", reconnectionLatch.await(5, TimeUnit.SECONDS));
		}
		catch (AbortedException ise){
			return;
		}
	}

	@Test
	public void testCancelSend() throws InterruptedException {
		final CountDownLatch connectionLatch = new CountDownLatch(3);

		TcpClient tcpClient = TcpClient.create(opts -> opts.host("localhost")
		                                                   .port(echoServerPort)
		                                                   .disablePool());
		NettyContext c;

		c = tcpClient.newHandler((i, o) -> {
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
		             .block();

		assertTrue("Cancel not propagated", connectionLatch.await(30, TimeUnit.SECONDS));
		c.dispose();
	}

	@Test
	public void consumerSpecAssignsEventHandlers()
			throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(2);
		final CountDownLatch close = new CountDownLatch(1);
		final AtomicLong totalDelay = new AtomicLong();
		final long start = System.currentTimeMillis();

		TcpClient client =
				TcpClient.create(opts -> opts.host("localhost").port(timeoutServerPort));

		NettyContext s = client.newHandler((in, out) -> {
			in.onReadIdle(500, () -> {
				  totalDelay.addAndGet(System.currentTimeMillis() - start);
				  latch.countDown();
			})
			  .context()
			  .onClose(close::countDown);

			out.onWriteIdle(500, () -> {
				totalDelay.addAndGet(System.currentTimeMillis() - start);
				latch.countDown();
			});

			return Mono.delay(Duration.ofSeconds(3))
			           .then()
			           .log();
		})
		                       .block(Duration.ofSeconds(30));

		assertTrue("latch was counted down", latch.await(5, TimeUnit.SECONDS));
		assertTrue("close was counted down", close.await(30, TimeUnit.SECONDS));
		assertThat("totalDelay was >500ms", totalDelay.get(), greaterThanOrEqualTo(500L));
		s.dispose();
	}

	@Test
	public void readIdleDoesNotFireWhileDataIsBeingRead()
			throws InterruptedException, IOException {
		final CountDownLatch latch = new CountDownLatch(1);
		long start = System.currentTimeMillis();

		TcpClient client = TcpClient.create("localhost", heartbeatServerPort);

		NettyContext s = client.newHandler((in, out) -> {
			in.onReadIdle(500, latch::countDown);
			return Flux.never();
		})
		                       .block(Duration.ofSeconds(30));

		assertTrue(latch.await(15, TimeUnit.SECONDS));
		heartbeatServer.close();

		long duration = System.currentTimeMillis() - start;

		assertThat(duration, is(greaterThanOrEqualTo(500L)));
		s.dispose();
	}

	@Test
	public void writeIdleDoesNotFireWhileDataIsBeingSent()
			throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		long start = System.currentTimeMillis();

		NettyContext client = TcpClient.create("localhost", echoServerPort)
		                               .newHandler((in, out) -> {
			                               System.out.println("hello");
			                               out.onWriteIdle(500, latch::countDown);

			                               List<Publisher<Void>> allWrites =
					                               new ArrayList<>();
			                               for (int i = 0; i < 5; i++) {
				                               allWrites.add(out.sendString(Flux.just("a")
				                                                                .delayElements(Duration.ofMillis(750))));
			                               }
			                               return Flux.merge(allWrites);
		                               })
		                               .block(Duration.ofSeconds(30));

		System.out.println("Started");

		assertTrue(latch.await(5, TimeUnit.SECONDS));

		long duration = System.currentTimeMillis() - start;

		assertThat(duration, is(greaterThanOrEqualTo(500l)));
		client.dispose();
	}

	@Test
	public void nettyNetChannelAcceptsNettyChannelHandlers() throws InterruptedException {
		HttpClient client = HttpClient.create();

		final CountDownLatch latch = new CountDownLatch(1);
		System.out.println(client.get("https://www.google.com/?q=test%20d%20dq")
		                         .flatMap(r -> r.receive()
		                                     .asString()
		                                     .collectList())
		                         .doOnSuccess(v -> latch.countDown())
		                         .block(Duration.ofSeconds(30)));

		assertTrue("Latch didn't time out", latch.await(15, TimeUnit.SECONDS));
	}

	@Test
	public void toStringShowsOptions() {
		TcpClient client = TcpClient.create(opt -> opt.host("foo").port(123));

		Assertions.assertThat(client.toString()).isEqualTo("TcpClient: connecting to foo:123");
	}

	@Test
	public void gettingOptionsDuplicates() {
		TcpClient client = TcpClient.create(opt -> opt.host("foo").port(123));
		Assertions.assertThat(client.options())
		          .isNotSameAs(client.options)
		          .isNotSameAs(client.options());
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
					System.out.println("ABORTING");
					ch.close();
				}
			}
			catch (Exception e) {
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
