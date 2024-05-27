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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SniCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicException;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicSslEngine;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.DomainWildcardMappingBuilder;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.CancelReceiverHandlerTest;
import reactor.netty.LogTracker;
import reactor.netty.NettyPipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * This test class verifies {@link QuicServer}.
 *
 * @author Violeta Georgieva
 */
class QuicServerTests extends BaseQuicTests {

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
			Consumer<QuicInitialSettingsSpec.Builder> clientInitialSettings) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		server =
				createServer(0)
				        .doOnConnection(quicConn ->
				            Flux.range(0, 2)
				                .flatMap(i -> quicConn.createStream(streamType, (in, out) -> out.sendString(Mono.just("Hello World!"))))
				                .subscribe())
				        .bindNow();

		client =
				createClient(server::address, clientInitialSettings)
				        .idleTimeout(Duration.ofMillis(100))
				        .doOnConnected(quicConn -> quicConn.onDispose(latch::countDown))
				        .connectNow();

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
		Consumer<QuicInitialSettingsSpec.Builder> clientInitialSettings =
				spec -> spec.maxData(1000000)
				            .maxStreamDataBidirectionalRemote(1000000)
				            .maxStreamsBidirectional(100);
		server =
				createServer(0)
				        .handleStream((in, out) -> out.send(in.receive().retain()))
				        .bindNow();

		client =
				createClient(server::address, clientInitialSettings)
				        .idleTimeout(Duration.ofMillis(100))
				        .doOnConnected(quicConn -> quicConn.onDispose(latch::countDown))
				        .connectNow();

		Flux.range(0, 2)
				.flatMap(i -> client.createStream(QuicStreamType.BIDIRECTIONAL, (in, out) -> out.sendString(Mono.just("Hello World!"))))
				.blockLast(Duration.ofSeconds(5));

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
			Consumer<QuicInitialSettingsSpec.Builder> clientInitialSettings) throws Exception {
		AtomicReference<String> incomingData = new AtomicReference<>("");
		AtomicReference<Throwable> error = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(4);
		server =
				createServer()
				        .doOnConnection(quicConn ->
				            Flux.range(0, 2)
				                .flatMap(i ->
				                    quicConn.createStream(streamType, (in, out) -> {
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
				                .subscribe())
				        .bindNow();

		AtomicBoolean streamTypeReceived = new AtomicBoolean();
		AtomicBoolean remoteCreated = new AtomicBoolean();
		client =
				createClient(server::address, clientInitialSettings)
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
				        .connectNow();

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
						QuicServer.create()
						          .port(0)
						          .bindNow());
	}

	@Test
	void testMissingTokenHandler() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() ->
						QuicServer.create()
						          .secure(serverCtx)
						          .port(0)
						          .bindNow());
	}

	@Test
	void testSniSupportDefault() throws Exception {
		testSniSupport(quicChannel -> clientCtx.newEngine(quicChannel.alloc(), "test.com", 8080), "http/0.9", "test.com");
	}

	@Test
	void testSniSupportMatches() throws Exception {
		QuicSslContext clientSslContext =
				QuicSslContextBuilder.forClient()
				                     .trustManager(InsecureTrustManagerFactory.INSTANCE)
				                     .applicationProtocols("http/1.1")
				                     .build();
		testSniSupport(quicChannel -> clientSslContext.newEngine(quicChannel.alloc(), "quic.test.com", 8080), "http/1.1", "quic.test.com");
	}

	private void testSniSupport(Function<QuicChannel, ? extends QuicSslEngine> sslEngineProvider,
			String expectedAppProtocol, String expectedHostname) throws Exception {
		QuicSslContext sniServerCtx =
					QuicSslContextBuilder.forServer(ssc.privateKey(), null, ssc.certificate())
					                     .applicationProtocols("http/1.1")
					                     .build();

		QuicSslContext serverSslContext = QuicSslContextBuilder.buildForServerWithSni(
				new DomainWildcardMappingBuilder<>(serverCtx).add("*.test.com", sniServerCtx).build());

		AtomicReference<String> appProtocol = new AtomicReference<>("");
		AtomicReference<String> hostname = new AtomicReference<>();
		server =
				createServer()
				        .secure(serverSslContext)
				        .doOnChannelInit((obs, channel, remoteAddress) ->
				                channel.pipeline()
				                       .addBefore(NettyPipeline.ReactiveBridge, "test", new ChannelInboundHandlerAdapter() {
				                           @Override
				                           public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
				                               if (evt instanceof SniCompletionEvent) {
				                                   hostname.set(((SniCompletionEvent) evt).hostname());
				                               }
				                               ctx.fireUserEventTriggered(evt);
				                           }
				                       }))
				        .handleStream((in, out) -> {
				            in.withConnection(conn ->
				                appProtocol.set(((QuicChannel) conn.channel().parent()).sslEngine().getApplicationProtocol()));
				            return out.send(in.receive().retain());
				        })
				        .bindNow();

		client =
				createClient(server::address)
				        .secure(sslEngineProvider)
				        .connectNow();

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> incomingData = new AtomicReference<>("");
		client.createStream((in, out) -> out.sendString(Mono.just("testSniSupport"))
		                                    .then(in.receive()
		                                            .asString()
		                                            .doOnNext(s -> {
		                                                incomingData.getAndUpdate(s1 -> s + s1);
		                                                latch.countDown();
		                                            })
		                                            .then()))
		      .block(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch wait").isTrue();

		assertThat(appProtocol.get()).isEqualTo(expectedAppProtocol);

		assertThat(incomingData.get()).isEqualTo("testSniSupport");

		assertThat(hostname.get()).isNotNull().isEqualTo(expectedHostname);
	}

	@Test
	void testStreamCreatedByServerBidirectional() throws Exception {
		testStreamCreatedByServer(QuicStreamType.BIDIRECTIONAL);
	}

	@Test
	void testStreamCreatedByServerUnidirectional() throws Exception {
		testStreamCreatedByServer(QuicStreamType.UNIDIRECTIONAL);
	}

	private void testStreamCreatedByServer(QuicStreamType streamType) throws Exception {
		AtomicReference<String> incomingData = new AtomicReference<>("");

		CountDownLatch latch = new CountDownLatch(3);
		server =
				createServer()
				        .doOnConnection(quicConn ->
				            quicConn.createStream(streamType, (in, out) -> {
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
				                    .subscribe())
				        .bindNow();

		AtomicBoolean streamTypeReceived = new AtomicBoolean();
		AtomicBoolean remoteCreated = new AtomicBoolean();

		client =
				createClient(server::address)
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
				        .connectNow();

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch wait").isTrue();

		assertThat(streamTypeReceived).isTrue();
		assertThat(remoteCreated).isTrue();
		assertThat(incomingData.get()).isEqualTo("Hello World!");
	}

	@Test
	void testUnidirectionalStreamCreatedByServerClientDoesNotListen() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		server =
				createServer()
				        .doOnConnection(quicConn ->
				            quicConn.createStream(QuicStreamType.UNIDIRECTIONAL, (in, out) -> {
				                        in.withConnection(conn -> conn.onDispose(latch::countDown));
				                        return out.sendString(Mono.just("Hello World!"));
				                    })
				                    .subscribe())
				        .bindNow();

		client = createClient(server::address).connectNow();

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch wait").isTrue();
	}

	@Test
	void testUnidirectionalStreamCreatedByServerClientTriesToSend() throws Exception {
		CountDownLatch latch = new CountDownLatch(2);
		server =
				createServer()
				        .doOnConnection(quicConn ->
				            quicConn.createStream(QuicStreamType.UNIDIRECTIONAL, (in, out) ->
				                        out.sendString(Mono.just("Hello World!")))
				                    .subscribe())
				        .bindNow();

		AtomicBoolean streamTypeReceived = new AtomicBoolean();
		AtomicBoolean remoteCreated = new AtomicBoolean();
		AtomicReference<Throwable> error = new AtomicReference<>();

		client =
				createClient(server::address)
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
				        .connectNow();

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch wait").isTrue();

		assertThat(streamTypeReceived).isTrue();
		assertThat(remoteCreated).isTrue();
		assertThat(error.get()).isInstanceOf(UnsupportedOperationException.class)
				.hasMessage("Writes on non-local created streams that are unidirectional are not supported");
	}

	@Test
	void testQuicServerCancelled() throws InterruptedException {
		try (LogTracker lg = new LogTracker(QuicStreamOperations.class, QuicStreamOperations.INBOUND_CANCEL_LOG)) {
			AtomicReference<Subscription> subscription = new AtomicReference<>();
			AtomicReference<List<String>> serverMsg = new AtomicReference<>(new ArrayList<>());
			CancelReceiverHandlerTest cancelReceiver = new CancelReceiverHandlerTest(() -> subscription.get().cancel());

			server = createServer()
					.handleStream((in, out) -> {
						in.withConnection(conn -> conn.addHandlerFirst(cancelReceiver));
						return in.receive()
								.asString()
								.log("server.receive")
								.doOnSubscribe(subscription::set)
								.doOnNext(s -> serverMsg.get().add(s))
								.then(Mono.never());
					})
					.bindNow();

			client = createClient(server::address).connectNow();

			client.createStream(QuicStreamType.BIDIRECTIONAL, (in, out) -> out.sendString(Mono.just("PING"))
					.neverComplete())
					.subscribe();

			assertThat(cancelReceiver.awaitAllReleased(30)).as("cancelReceiver").isTrue();
			assertThat(lg.latch.await(30, TimeUnit.SECONDS)).isTrue();
			List<String> serverMessages = serverMsg.get();
			assertThat(serverMessages).isNotNull();
			assertThat(serverMessages.size()).isEqualTo(0);
		}
	}
}

