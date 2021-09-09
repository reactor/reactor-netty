/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.quic;

import io.netty.incubator.codec.quic.QuicException;
import io.netty.incubator.codec.quic.QuicStreamType;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Violeta Georgieva
 */
class QuicClientTests extends BaseQuicTests {

	@Test
	void testMaxStreamsReachedBidirectional() throws Exception {
		testMaxStreamsReached(QuicStreamType.BIDIRECTIONAL);
	}

	@Test
	void testMaxStreamsReachedUnidirectional() throws Exception {
		testMaxStreamsReached(QuicStreamType.UNIDIRECTIONAL);
	}

	private void testMaxStreamsReached(QuicStreamType streamType) throws Exception {
		AtomicBoolean streamTypeReceived = new AtomicBoolean();
		AtomicBoolean remoteCreated = new AtomicBoolean();
		AtomicReference<String> incomingData = new AtomicReference<>("");

		CountDownLatch latch = new CountDownLatch(4);

		Consumer<QuicInitialSettingsSpec.Builder> initialSettings = spec -> {
			if (QuicStreamType.BIDIRECTIONAL == streamType) {
				spec.maxData(10000000)
				    .maxStreamDataBidirectionalRemote(1000000)
				    .maxStreamsBidirectional(1);
			}
			else {
				spec.maxData(10000000)
				    .maxStreamDataUnidirectional(1000000)
				    .maxStreamsUnidirectional(1);
			}
		};
		server =
				createServer(0, initialSettings)
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
				.hasMessage("QUICHE_ERR_STREAM_LIMIT");
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
	void testBidirectionalStreamCreatedByClientBidirectional() throws Exception {
		testStreamCreatedByClient(QuicStreamType.BIDIRECTIONAL);
	}

	@Test
	void testUnidirectionalStreamCreatedByClientUnidirectional() throws Exception {
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
}
