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

import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.incubator.codec.quic.QuicStreamType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.ConnectionObserver.State.CONNECTED;

/**
 * @author Violeta Georgieva
 */
class QuicClientSmokeTests extends BaseQuicTests {

	static final String EMPTY_RESPONSE = "Hello Empty!";
	static final String FLUX_RESPONSE = "Hello Flux!";
	static final String MONO_RESPONSE = "Hello Mono!";
	static final String STREAM_ID_RESPONSE = "stream_id=0";

	static final String METHOD = "GET";

	static final String EMPTY_PATH = " /empty";
	static final String FLUX_PATH = " /flux";
	static final String MONO_PATH = " /mono";
	static final String ERROR_PATH_1 = " /error1";
	static final String ERROR_PATH_2 = " /error2";
	static final String STREAM_ID = " /stream_id";

	static Connection quicServer;
	static QuicConnection quicClient;

	@BeforeAll
	static void setUp() throws Exception {
		quicServer =
				createServer()
				        .streamObserve((conn, state) -> {
				            if (state == CONNECTED) {
				                conn.addHandlerLast(new LineBasedFrameDecoder(1024));
				            }
				        })
				        .handleStream((in, out) ->
				            out.sendString(in.receive()
				                             .asString()
				                             .flatMap(s -> {
				                                 if ((METHOD + MONO_PATH).equals(s)) {
				                                     return Mono.just(MONO_RESPONSE + "\r\n");
				                                 }
				                                 else if ((METHOD + FLUX_PATH).equals(s)) {
				                                     return Flux.just("Hello", " ", "Flux", "!", "\r\n");
				                                 }
				                                 else if ((METHOD + ERROR_PATH_1).equals(s)) {
				                                     throw new RuntimeException("error1");
				                                 }
				                                 else if ((METHOD + ERROR_PATH_2).equals(s)) {
				                                     return Mono.error(new RuntimeException("error2"));
				                                 }
				                                 else if ((METHOD + STREAM_ID).equals(s)) {
				                                     return Mono.just("stream_id=" + in.streamId() + "\r\n");
				                                 }
				                                 return Mono.empty();
				                             })))
				          .bindNow();

		quicClient =
				createClient(quicServer::address)
				        .streamObserve((conn, state) -> {
				            if (state == CONNECTED) {
				                conn.addHandlerLast(new LineBasedFrameDecoder(1024));
				            }
				        })
				        .connectNow();
	}

	@AfterAll
	static void tearDown() {
		if (quicServer != null) {
			quicServer.disposeNow();
		}
		if (quicClient != null) {
			quicClient.disposeNow();
		}
	}

	@Test
	void testEmpty() throws Exception {
		doTestClientOpensStream(quicClient, Mono.just(METHOD + EMPTY_PATH + "\r\n"), EMPTY_RESPONSE);
	}

	@Test
	void testFlux() throws Exception {
		doTestClientOpensStream(quicClient, Flux.just(METHOD, FLUX_PATH, "\r\n"), FLUX_RESPONSE);
	}

	@Test
	void testMono() throws Exception {
		doTestClientOpensStream(quicClient, Mono.just(METHOD + MONO_PATH + "\r\n"), MONO_RESPONSE);
	}

	@Test
	void testMonoError() throws Exception {
		doTestClientOpensStream(quicClient, Mono.just(METHOD + ERROR_PATH_2 + "\r\n"), EMPTY_RESPONSE);
	}

	@Test
	void testStreamId() throws Exception {
		doTestClientOpensStream(quicClient, Mono.just(METHOD + STREAM_ID + "\r\n"), STREAM_ID_RESPONSE);
	}

	@Test
	void testThrowsException() throws Exception {
		doTestClientOpensStream(quicClient, Mono.just(METHOD + ERROR_PATH_1 + "\r\n"), EMPTY_RESPONSE);
	}

	private void doTestClientOpensStream(QuicConnection client, Publisher<String> body, String expectation)
			throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> response = new AtomicReference<>("");
		client.createStream(
		          QuicStreamType.BIDIRECTIONAL,
		          (in, out) -> {
		              in.receive()
		                .asString()
		                .defaultIfEmpty(EMPTY_RESPONSE)
		                .doOnNext(s -> {
		                    response.set(s);
		                    latch.countDown();
		                })
		                .subscribe();

		          return out.sendString(body);
		       })
		      .block(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch wait").isTrue();

		assertThat(response.get()).isEqualTo(expectation);
	}
}
