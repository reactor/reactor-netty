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
package reactor.netty.channel;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;

import static org.assertj.core.api.Assertions.assertThat;

class FluxReceiveTest extends BaseHttpTest {

	@Test
	void testByteBufsReleasedWhenTimeout() {
		byte[] content = new byte[1024 * 8];
		Random rndm = new Random();
		rndm.nextBytes(content);

		DisposableServer server1 =
				createServer()
				          .route(routes ->
				                     routes.get("/target", (req, res) ->
				                           req.receive()
				                              .thenMany(res.sendByteArray(Flux.just(content)
				                                                              .delayElements(Duration.ofMillis(100))))))
				          .bindNow();

		DisposableServer server2 =
				createServer()
				          .route(routes ->
				                     routes.get("/forward", (req, res) ->
				                           createClient(server1.port())
				                                     .get()
				                                     .uri("/target")
				                                     .responseContent()
				                                     .aggregate()
				                                     .asString()
				                                     .log()
				                                     .timeout(Duration.ofMillis(50))
				                                     .then()))
				          .bindNow();

		Flux.range(0, 50)
		    .flatMap(i -> createClient(server2.port())
		                            .get()
		                            .uri("/forward")
		                            .responseContent()
		                            .log()
		                            .onErrorResume(t -> Mono.empty()))
		    .blockLast(Duration.ofSeconds(15));

		server1.disposeNow();
		server2.disposeNow();
	}

	@Test
	void testByteBufsReleasedWhenTimeoutUsingHandlers() {
		byte[] content = new byte[1024 * 8];
		Random rndm = new Random();
		rndm.nextBytes(content);

		DisposableServer server1 =
				createServer()
				          .route(routes ->
				                     routes.get("/target", (req, res) ->
				                           req.receive()
				                              .thenMany(res.sendByteArray(Flux.just(content)
				                                                              .delayElements(Duration.ofMillis(100))))))
				          .bindNow();

		DisposableServer server2 =
				createServer()
				          .route(routes ->
				                     routes.get("/forward", (req, res) ->
				                           createClient(server1.port())
				                                     .doOnConnected(c ->
				                                             c.addHandlerFirst(new ReadTimeoutHandler(50, TimeUnit.MILLISECONDS)))
				                                     .get()
				                                     .uri("/target")
				                                     .responseContent()
				                                     .aggregate()
				                                     .asString()
				                                     .log()
				                                     .then()))
				          .bindNow();

		Flux.range(0, 50)
		    .flatMap(i -> createClient(server2.port())
		                            .get()
		                            .uri("/forward")
		                            .responseContent()
		                            .log()
		                            .onErrorResume(t -> Mono.empty()))
		    .blockLast(Duration.ofSeconds(15));

		server1.disposeNow();
		server2.disposeNow();
	}

	@Test
	void testIssue1016() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel();

		Connection connection = Connection.from(channel);
		ConnectionObserver observer = (conn, newState) -> {
			if (newState == ConnectionObserver.State.DISCONNECTING) {
				if (conn.channel().isActive() && !conn.isPersistent()) {
					conn.dispose();
				}
			}
		};
		ChannelOperations<?, ?> ops = new ChannelOperations<>(connection, observer);
		ops.bind();

		ByteBuf buffer = channel.alloc().buffer();
		buffer.writeCharSequence("testIssue1016", Charset.defaultCharset());
		ops.inbound.onInboundNext(buffer);

		CountDownLatch latch = new CountDownLatch(1);
		// There is a subscriber, but there is no request for an item
		ops.receive().subscribe(new TestSubscriber(latch));

		ops.onInboundError(new OutOfMemoryError());

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(buffer.refCnt()).isEqualTo(0);
	}

	static final class TestSubscriber implements CoreSubscriber<Object> {

		final CountDownLatch latch;

		TestSubscriber(CountDownLatch latch) {
			this.latch = latch;
		}

		@Override
		public void onSubscribe(Subscription s) {
		}

		@Override
		public void onNext(Object o) {
		}

		@Override
		public void onError(Throwable t) {
			assertThat(t).hasCauseInstanceOf(OutOfMemoryError.class);
			latch.countDown();
		}

		@Override
		public void onComplete() {
		}
	}
}
