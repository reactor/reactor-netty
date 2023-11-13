/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.BaseHttpTest;
import reactor.netty.CancelReceiverHandlerTest;
import reactor.netty.LogTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This test class verifies {@link HttpServer} websocket functionality.
 *
 * @author Pierre De Rop
 * @since 1.0.27
 */
class WebsocketServerOperationsTest extends BaseHttpTest {

	@Test
	void testWebSocketServerCancelled() throws InterruptedException {
		try (LogTracker lt = new LogTracker(HttpServerOperations.class, WebsocketServerOperations.INBOUND_CANCEL_LOG)) {
			AtomicReference<WebSocketCloseStatus> clientCloseStatus = new AtomicReference<>();
			AtomicReference<WebSocketCloseStatus> serverCloseStatus = new AtomicReference<>();
			CountDownLatch closeLatch = new CountDownLatch(2);
			CountDownLatch cancelled = new CountDownLatch(1);
			AtomicReference<List<String>> serverMsg = new AtomicReference<>(new ArrayList<>());
			Sinks.Empty<Void> empty = Sinks.empty();
			CancelReceiverHandlerTest cancelReceiver = new CancelReceiverHandlerTest(() -> empty.tryEmitEmpty());

			disposableServer = createServer()
					.handle((in, out) -> out.sendWebsocket((i, o) -> {
						i.withConnection(conn -> conn.addHandlerLast(cancelReceiver));

						i.receiveCloseStatus()
								.log("server.closestatus")
								.doOnNext(status -> {
									serverCloseStatus.set(status);
									closeLatch.countDown();
								})
								.subscribe();

						Mono<Void> receive = i.receive()
								.asString()
								.log("server.receive")
								.doOnCancel(cancelled::countDown)
								.doOnNext(s -> serverMsg.get().add(s))
								.then();

						return Flux.zip(receive, empty.asMono())
								.then(Mono.never());
					}))
					.bindNow();

			createClient(disposableServer.port())
					.websocket()
					.uri("/test")
					.handle((in, out) -> {
						in.receiveCloseStatus()
								.log("client.closestatus")
								.doOnNext(status -> {
									clientCloseStatus.set(status);
									closeLatch.countDown();
								})
								.subscribe();

						return out.sendString(Mono.just("PING"))
								.neverComplete();
					})
					.log("client")
					.subscribe();

			assertThat(closeLatch.await(30, TimeUnit.SECONDS)).isTrue();
			// client received closed without any status code
			assertThat(clientCloseStatus.get()).isNotNull().isEqualTo(WebSocketCloseStatus.EMPTY);
			// server locally closed abnormally
			assertThat(serverCloseStatus.get()).isNotNull().isEqualTo(WebSocketCloseStatus.ABNORMAL_CLOSURE);
			assertThat(lt.latch.await(30, TimeUnit.SECONDS)).isTrue();
			assertThat(cancelled.await(30, TimeUnit.SECONDS)).isTrue();

			List<String> serverMessages = serverMsg.get();
			assertThat(serverMessages).isNotNull();
			assertThat(serverMessages.size()).isEqualTo(0);
			assertThat(cancelReceiver.awaitAllReleased(30)).as("cancelReceiver").isTrue();
		}
	}

}
