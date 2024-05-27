/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.incubator.quic;

import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.incubator.codec.quic.QuicException;
import io.netty.incubator.codec.quic.QuicStreamType;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.CancelReceiverHandlerTest;
import reactor.netty.LogTracker;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * This test class verifies {@link QuicClient}.
 *
 * @author Violeta Georgieva
 */
class QuicClientTests extends BaseQuicTests {

	@Test
	void testCannotConnectToRemote() {
			createClient(() -> new InetSocketAddress(12121))
			        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100)
			        .connect()
			        .as(StepVerifier::create)
			        .expectErrorSatisfies(t -> assertThat(t).isInstanceOf(ConnectTimeoutException.class)
			                                                .hasMessageContaining("connection timed out"))
			        .verify(Duration.ofSeconds(5));
	}

	/**
	 * DATA_BLOCKED.
	 */
	@Test
	void testMaxDataNotSpecifiedBidirectional() throws Exception {
		testMaxDataReached(QuicStreamType.BIDIRECTIONAL,
				spec -> spec.maxStreamDataBidirectionalLocal(1000000)
				            .maxStreamDataBidirectionalRemote(1000000)
				            .maxStreamsBidirectional(100));
	}

	/**
	 * DATA_BLOCKED.
	 */
	@Test
	void testMaxDataNotSpecifiedUnidirectional() throws Exception {
		testMaxDataReached(QuicStreamType.UNIDIRECTIONAL,
				spec -> spec.maxStreamDataUnidirectional(1000000)
				            .maxStreamsUnidirectional(100));
	}

	/**
	 * STREAM_DATA_BLOCKED.
	 */
	@Test
	void testMaxStreamDataNotSpecifiedUnidirectional() throws Exception {
		testMaxDataReached(QuicStreamType.UNIDIRECTIONAL,
				spec -> spec.maxData(1000000)
				            .maxStreamsUnidirectional(100));
	}

	/**
	 * STREAM_DATA_BLOCKED.
	 */
	@Test
	void testMaxStreamDataLocalNotSpecifiedBidirectional() throws Exception {
		testMaxDataReachedLocal();
	}

	/**
	 * STREAM_DATA_BLOCKED.
	 */
	@Test
	void testMaxStreamDataRemoteNotSpecifiedBidirectional() throws Exception {
		testMaxDataReached(QuicStreamType.BIDIRECTIONAL,
				spec -> spec.maxData(1000000)
				            .maxStreamDataBidirectionalLocal(1000000)
				            .maxStreamsBidirectional(100));
	}

	/**
	 * https://datatracker.ietf.org/doc/html/rfc9000#section-4.1
	 *
	 * Data Flow Control
	 *
	 * If a sender has sent data up to the limit, it will be unable to send
	 * new data and is considered blocked. A sender SHOULD send a
	 * STREAM_DATA_BLOCKED or DATA_BLOCKED frame to indicate to the receiver
	 * that it has data to write but is blocked by flow control limits. If
	 * a sender is blocked for a period longer than the idle timeout
	 * (Section 10.1), the receiver might close the connection even when the
	 * sender has data that is available for transmission.
	 */
	private void testMaxDataReached(QuicStreamType streamType,
			Consumer<QuicInitialSettingsSpec.Builder> serverInitialSettings) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		server =
				createServer(0, serverInitialSettings)
				        .idleTimeout(Duration.ofMillis(100))
				        .doOnConnection(quicConn -> quicConn.onDispose(latch::countDown))
				        .bindNow();

		client = createClient(server::address).connectNow();

		Flux.range(0, 2)
		    .flatMap(i -> client.createStream(streamType, (in, out) -> out.sendString(Mono.just("Hello World!"))))
		    .blockLast(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch wait").isTrue();
	}

	/**
	 * https://datatracker.ietf.org/doc/html/rfc9000#section-4.1
	 *
	 * Data Flow Control
	 *
	 * If a sender has sent data up to the limit, it will be unable to send
	 * new data and is considered blocked. A sender SHOULD send a
	 * STREAM_DATA_BLOCKED or DATA_BLOCKED frame to indicate to the receiver
	 * that it has data to write but is blocked by flow control limits. If
	 * a sender is blocked for a period longer than the idle timeout
	 * (Section 10.1), the receiver might close the connection even when the
	 * sender has data that is available for transmission.
	 */
	private void testMaxDataReachedLocal() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		Consumer<QuicInitialSettingsSpec.Builder> serverInitialSettings =
				spec -> spec.maxData(1000000)
				            .maxStreamDataBidirectionalRemote(1000000)
				            .maxStreamsBidirectional(100);
		server =
				createServer(0, serverInitialSettings)
				        .idleTimeout(Duration.ofMillis(100))
				        .doOnConnection(quicConn -> {
				            quicConn.onDispose(latch::countDown);
				            Flux.range(0, 2)
				                .flatMap(i -> quicConn.createStream(QuicStreamType.BIDIRECTIONAL, (in, out) -> out.sendString(Mono.just("Hello World!"))))
				                .subscribe();
				        })
				        .bindNow();

		client =
				createClient(server::address)
				        .handleStream((in, out) -> out.send(in.receive().retain()))
				        .connectNow();

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch wait").isTrue();
	}

	@Test
	void testMaxStreamsReachedBidirectional() throws Exception {
		testMaxStreamsReached(QuicStreamType.BIDIRECTIONAL,
				spec -> spec.maxData(10000000)
				            .maxStreamDataBidirectionalRemote(1000000)
				            .maxStreamsBidirectional(1));
	}

	@Test
	void testMaxStreamsReachedUnidirectional() throws Exception {
		testMaxStreamsReached(QuicStreamType.UNIDIRECTIONAL,
				spec -> spec.maxData(10000000)
				            .maxStreamDataUnidirectional(1000000)
				            .maxStreamsUnidirectional(1));
	}

	private void testMaxStreamsReached(QuicStreamType streamType,
			Consumer<QuicInitialSettingsSpec.Builder> serverInitialSettings) throws Exception {
		AtomicBoolean streamTypeReceived = new AtomicBoolean();
		AtomicBoolean remoteCreated = new AtomicBoolean();
		AtomicReference<String> incomingData = new AtomicReference<>("");

		CountDownLatch latch = new CountDownLatch(4);
		server =
				createServer(0, serverInitialSettings)
				        .handleStream((in, out) -> {
				            streamTypeReceived.set(in.streamType() == streamType);
				            remoteCreated.set(!in.isLocalStream());
				            latch.countDown();
				            if (QuicStreamType.BIDIRECTIONAL == streamType) {
				                return out.send(in.receive().retain());
				            }
				            else {
				                return in.receive()
				                         .asString()
				                         .doOnNext(s -> {
				                             incomingData.getAndUpdate(s1 -> s + s1);
				                             latch.countDown();
				                         })
				                         .then();
				            }
				        })
				        .bindNow();

		AtomicReference<Throwable> error = new AtomicReference<>();

		client = createClient(server::address).connectNow();

		Flux.range(0, 2)
		    .flatMap(i ->
		        client.createStream(streamType, (in, out) -> {
		                  in.withConnection(conn -> conn.onDispose(latch::countDown));
		                      if (QuicStreamType.BIDIRECTIONAL == streamType) {
		                          in.receive()
		                            .asString()
		                            .doOnNext(s -> {
		                                incomingData.getAndUpdate(s1 -> s + s1);
		                                latch.countDown();
		                            })
		                            .subscribe();
		                      }
		                      return out.sendString(Mono.just("Hello World!"));
		                  })
		              .onErrorResume(t -> {
		                  error.set(t);
		                  latch.countDown();
		                  return Mono.empty();
		              }))
		      .blockLast(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch wait").isTrue();

		assertThat(streamTypeReceived).isTrue();
		assertThat(remoteCreated).isTrue();
		assertThat(incomingData.get()).isEqualTo("Hello World!");
		assertThat(error.get()).isNotNull()
				.isInstanceOf(QuicException.class)
				.hasMessageContaining("QUICHE_ERR_STREAM_LIMIT");
	}

	@Test
	void testMissingSslContext() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() ->
						QuicClient.create()
						          .port(0)
						          .connectNow());
	}

	@Test
	void testStreamCreatedByClientBidirectional() throws Exception {
		testStreamCreatedByClient(QuicStreamType.BIDIRECTIONAL);
	}

	@Test
	void testStreamCreatedByClientUnidirectional() throws Exception {
		testStreamCreatedByClient(QuicStreamType.UNIDIRECTIONAL);
	}

	private void testStreamCreatedByClient(QuicStreamType streamType) throws Exception {
		AtomicBoolean streamTypeReceived = new AtomicBoolean();
		AtomicBoolean remoteCreated = new AtomicBoolean();
		AtomicReference<String> incomingData = new AtomicReference<>("");

		CountDownLatch latch = new CountDownLatch(3);
		server =
				createServer()
				        .handleStream((in, out) -> {
				            streamTypeReceived.set(in.streamType() == streamType);
				            remoteCreated.set(!in.isLocalStream());
				            latch.countDown();
				            if (QuicStreamType.BIDIRECTIONAL == streamType) {
				                return out.send(in.receive().retain());
				            }
				            else {
				                return in.receive()
				                         .asString()
				                         .doOnNext(s -> {
				                             incomingData.getAndUpdate(s1 -> s + s1);
				                             latch.countDown();
				                         })
				                         .then();
				            }
				        })
				        .bindNow();

		client = createClient(server::address).connectNow();

		client.createStream(streamType, (in, out) -> {
		          in.withConnection(conn -> conn.onDispose(latch::countDown));
		          if (QuicStreamType.BIDIRECTIONAL == streamType) {
		              in.receive()
		                .asString()
		                .doOnNext(s -> {
		                    incomingData.getAndUpdate(s1 -> s + s1);
		                    latch.countDown();
		                })
		                .subscribe();
		          }
		          return out.sendString(Mono.just("Hello World!"));
		      })
		      .block(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch wait").isTrue();

		assertThat(streamTypeReceived).isTrue();
		assertThat(remoteCreated).isTrue();
		assertThat(incomingData.get()).isEqualTo("Hello World!");
	}

	@Test
	void testUnidirectionalStreamCreatedByClientServerDoesNotListen() throws Exception {
		server = createServer().bindNow();

		client = createClient(server::address).connectNow();

		CountDownLatch latch = new CountDownLatch(1);
		client.createStream(QuicStreamType.UNIDIRECTIONAL, (in, out) -> {
					in.withConnection(conn -> conn.onDispose(latch::countDown));
					return out.sendString(Mono.just("Hello World!"));
				})
		      .block(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch wait").isTrue();
	}

	@Test
	void testUnidirectionalStreamCreatedByClientServerTriesToSend() throws Exception {
		AtomicBoolean streamTypeReceived = new AtomicBoolean();
		AtomicBoolean remoteCreated = new AtomicBoolean();
		AtomicReference<Throwable> error = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(2);
		server =
				createServer()
				        .handleStream((in, out) -> {
				            streamTypeReceived.set(in.streamType() == QuicStreamType.UNIDIRECTIONAL);
				            remoteCreated.set(!in.isLocalStream());
				            latch.countDown();
				            return out.send(in.receive().retain())
				                      .then()
				                      .doOnError(t -> {
				                          error.set(t);
				                          latch.countDown();
				                      });
				        })
				        .bindNow();

		client = createClient(server::address).connectNow();

		client.createStream(QuicStreamType.UNIDIRECTIONAL, (in, out) -> out.sendString(Mono.just("Hello World!")))
		      .block(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch wait").isTrue();

		assertThat(streamTypeReceived).isTrue();
		assertThat(remoteCreated).isTrue();
		assertThat(error.get()).isInstanceOf(UnsupportedOperationException.class)
				.hasMessage("Writes on non-local created streams that are unidirectional are not supported");
	}

	@Test
	void testQuicClientCancelled() throws InterruptedException {
		Sinks.Empty<Void> empty = Sinks.empty();
		CountDownLatch cancelled = new CountDownLatch(1);
		CancelReceiverHandlerTest cancelReceiver = new CancelReceiverHandlerTest(empty::tryEmitEmpty);
		CountDownLatch closed = new CountDownLatch(2);

		try (LogTracker lg = new LogTracker(QuicStreamOperations.class, QuicStreamOperations.INBOUND_CANCEL_LOG)) {
			server =
					createServer()
							.handleStream((in, out) -> {
								in.withConnection(conn -> conn.onDispose(closed::countDown));
								return out.sendString(in.receive()
												.asString()
												.log("server.receive"))
										.then();
							})
							.bindNow();

			client = createClient(server::address).connectNow();
			client.createStream(QuicStreamType.BIDIRECTIONAL, (in, out) -> {
						in.withConnection(conn -> {
							conn.addHandlerFirst(cancelReceiver);
							conn.onDispose(closed::countDown);
						});
						out.sendString(Mono.just("PING")).then().subscribe();
						Mono<Void> receive = in.receive()
								.asString()
								.log("client.receive")
								.doOnCancel(cancelled::countDown)
								.then();
						return Flux.zip(receive, empty.asMono())
								.log("zip")
								.then();
					})
					.log("client")
					.subscribe();

			assertThat(cancelled.await(30, TimeUnit.SECONDS)).as("cancelled await").isTrue();
			assertThat(closed.await(30, TimeUnit.SECONDS)).as("closed await").isTrue();
			assertThat(lg.latch.await(30, TimeUnit.SECONDS)).isTrue();
			assertThat(cancelReceiver.awaitAllReleased(30)).as("clientInboundReleased").isTrue();
		}
	}
}
