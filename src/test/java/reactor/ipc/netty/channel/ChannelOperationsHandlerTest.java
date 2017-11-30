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

package reactor.ipc.netty.channel;

import java.time.Duration;

import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

public class ChannelOperationsHandlerTest {

	@Test
	public void publisherSenderOnCompleteFlushInProgress_1() {
		doTestPublisherSenderOnCompleteFlushInProgress(false);
	}

	@Test
	public void publisherSenderOnCompleteFlushInProgress_2() {
		doTestPublisherSenderOnCompleteFlushInProgress(true);
	}

	private void doTestPublisherSenderOnCompleteFlushInProgress(boolean useScheduler) {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handler((req, res) ->
				                  req.receive()
				                     .asString()
				                     .doOnNext(System.err::println)
				                     .then(res.status(200).sendHeaders().then()))
				          .wiretap()
				          .bindNow(Duration.ofSeconds(300));

		Flux<String> flux = Flux.range(1, 257).map(count -> count + "");
		if (useScheduler) {
			flux.publishOn(Schedulers.single());
		}
		Mono<HttpClientResponse> response =
				HttpClient.prepare()
				          .tcpConfiguration(tcpClient -> tcpClient.noSSL())
				          .port(server.address().getPort())
				          .wiretap()
				          .post()
				          .uri("/")
				          .send(ByteBufFlux.fromString(flux))
				          .response().log();

		StepVerifier.create(response)
		            .expectNextMatches(res -> {
		                res.dispose();
		                return res.status().code() == 200;
		            })
		            .expectComplete()
		            .verify(Duration.ofSeconds(300));

		server.dispose();
	}
/*
	@Test
	public void keepPrefetchSizeConstantEqualsWriteBufferLowHighWaterMark() {
		doTestPrefetchSize(1024, 1024);
	}

	@Test
	public void keepPrefetchSizeConstantDifferentWriteBufferLowHighWaterMark() {
		doTestPrefetchSize(0, 1024);
	}

	private void doTestPrefetchSize(int writeBufferLowWaterMark, int writeBufferHighWaterMark) {
		ChannelOperationsHandler handler = new ChannelOperationsHandler(null);

		EmbeddedChannel channel = new EmbeddedChannel(handler);
		channel.config().setWriteBufferLowWaterMark(writeBufferLowWaterMark)
		                .setWriteBufferHighWaterMark(writeBufferHighWaterMark);

		assertThat(handler.prefetch == (handler.inner.requested - handler.inner.produced)).isTrue();

		StepVerifier.create(FutureMono.deferFuture(() -> channel.writeAndFlush(Flux.range(0, 70))))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(handler.prefetch == (handler.inner.requested - handler.inner.produced)).isTrue();
	}

	@Test
	public void testChannelInactiveThrowsIOException() throws Exception {
		ExecutorService threadPool = Executors.newCachedThreadPool();

		int abortServerPort = SocketUtils.findAvailableTcpPort();
		ConnectionAbortServer abortServer = new ConnectionAbortServer(abortServerPort);

		threadPool.submit(abortServer);

		if(!abortServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("Fail to start test server");
		}

		Mono<HttpClientResponse> response =
				HttpClient.prepare()
				          .port(abortServerPort)
				          .tcpConfiguration(tcpClient -> tcpClient.host("localhost"))
				          .get()
				          .uri("/")
						          req -> req.sendHeaders()
						                    .sendString(Flux.just("a", "b", "c")));

		StepVerifier.create(response)
		            .expectError()
		            .verify();

		abortServer.close();
	}

	private static final class ConnectionAbortServer extends CountDownLatch implements Runnable {

		private final int port;
		private final ServerSocketChannel server;
		private volatile boolean read = false;
		private volatile Thread thread;

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
				thread = Thread.currentThread();
				while (true) {
					SocketChannel ch = server.accept();

					while (true) {
						int bytes = ch.read(ByteBuffer.allocate(256));
						if (bytes > 0) {
							if (!read) {
								read = true;
							}
							else {
								ch.close();
								return;
							}
						}
					}
				}
			}
			catch (IOException e) {
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

	@Test
	@Ignore
	public void testIssue196() throws Exception {
		ExecutorService threadPool = Executors.newCachedThreadPool();

		int testServerPort = SocketUtils.findAvailableTcpPort();
		TestServer testServer = new TestServer(testServerPort);

		threadPool.submit(testServer);

		if(!testServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("Fail to start test server");
		}

		HttpClient client =
		        HttpClient.create(opt -> opt.port(testServerPort)
		                                    .poolResources(PoolResources.fixed("test", 1)));

		Flux.range(0, 2)
		    .flatMap(i -> client.get("/205")
		                        .flatMap(res -> res.receive()
		                                           .aggregate()
		                                           .asString()))
		    .blockLast(Duration.ofSeconds(100));

		testServer.close();
	}

	private static final class TestServer extends CountDownLatch implements Runnable {

		private final    int                 port;
		private final    ServerSocketChannel server;
		private volatile Thread              thread;

		private TestServer(int port) {
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

					byte[] buffer =
							("HTTP/1.1 205 Reset Content\r\n" +
							"Transfer-Encoding: chunked\r\n" +
							"\r\n" +
							"0\r\n" +
							"\r\n").getBytes();

					int written = ch.write(ByteBuffer.wrap(buffer));
					if (written < 0) {
						throw new IOException("Cannot write to client");
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
	}*/
}
