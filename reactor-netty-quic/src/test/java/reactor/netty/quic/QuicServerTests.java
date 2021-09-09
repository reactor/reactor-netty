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

import io.netty.incubator.codec.quic.QuicStreamType;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Violeta Georgieva
 */
class QuicServerTests extends BaseQuicTests {

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
}

