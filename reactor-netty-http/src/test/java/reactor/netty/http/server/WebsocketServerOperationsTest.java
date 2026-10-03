/*
 * Copyright (c) 2023-2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.netty.BaseHttpTest;
import reactor.netty.CancelReceiverHandlerTest;
import reactor.netty.Connection;
import reactor.netty.LogTracker;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
	void testFirstFrameAfterOffEventLoopUpgrade() throws Exception {
		AtomicInteger offEventLoopUpgrades = new AtomicInteger();
		disposableServer = HttpServer.create()
				.host("127.0.0.1")
				.port(0)
				.handle((req, res) -> {
					if ("/warm".equals(req.uri())) {
						return res.sendString(Mono.just("ok")).then();
					}
					EventLoop eventLoop = ((Connection) req).channel().eventLoop();
					WebsocketServerSpec config = recordingSpec(eventLoop, offEventLoopUpgrades);
					return Mono.fromCallable(() -> 1)
							.subscribeOn(Schedulers.boundedElastic())
							.then(Mono.defer(() -> res.sendWebsocket((in, out) ->
									out.sendString(in.receive().asString().map(message -> "echo:" + message)), config)));
				})
				.bindNow();
		for (int i = 0; i < 3; i++) {
			try (Socket socket = new Socket("127.0.0.1", disposableServer.port())) {
				socket.setSoTimeout(5000);
				socket.getOutputStream().write("GET /warm HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
						.getBytes(StandardCharsets.US_ASCII));
				assertThat(readHttpHeaders(socket.getInputStream())).startsWith("HTTP/1.1 200");
			}
		}

		for (int i = 0; i < 5; i++) {
			try (Socket socket = new Socket("127.0.0.1", disposableServer.port())) {
				socket.setSoTimeout(5000);
				InputStream input = socket.getInputStream();
				OutputStream output = socket.getOutputStream();
				byte[] key = new byte[16];
				ThreadLocalRandom.current().nextBytes(key);
				output.write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n" +
						"Connection: Upgrade\r\nSec-WebSocket-Key: " + Base64.getEncoder().encodeToString(key) +
						"\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				assertThat(readHttpHeaders(input)).startsWith("HTTP/1.1 101");

				byte[] mask = new byte[4];
				ThreadLocalRandom.current().nextBytes(mask);
				byte[] message = "first".getBytes(StandardCharsets.UTF_8);
				byte[] frame = new byte[6 + message.length];
				frame[0] = (byte) 0x81;
				frame[1] = (byte) (0x80 | message.length);
				System.arraycopy(mask, 0, frame, 2, mask.length);
				for (int j = 0; j < message.length; j++) {
					frame[6 + j] = (byte) (message[j] ^ mask[j % mask.length]);
				}
				output.write(frame);

				DataInputStream response = new DataInputStream(input);
				assertThat(response.readUnsignedByte()).isEqualTo(0x81);
				int length = response.readUnsignedByte() & 0x7f;
				byte[] payload = new byte[length];
				response.readFully(payload);
				assertThat(new String(payload, StandardCharsets.UTF_8)).isEqualTo("echo:first");
			}
		}
		assertThat(offEventLoopUpgrades).hasValue(0);
	}

	private static WebsocketServerSpec recordingSpec(EventLoop eventLoop, AtomicInteger offEventLoopUpgrades) {
		WebsocketServerSpec delegate = WebsocketServerSpec.builder().build();
		return new WebsocketServerSpec() {
			@Override
			public String protocols() {
				if (!eventLoop.inEventLoop()) {
					offEventLoopUpgrades.incrementAndGet();
				}
				return delegate.protocols();
			}

			@Override
			public int maxFramePayloadLength() {
				return delegate.maxFramePayloadLength();
			}

			@Override
			public boolean handlePing() {
				return delegate.handlePing();
			}

			@Override
			public boolean compress() {
				return delegate.compress();
			}

			@Override
			public boolean compressionAllowServerNoContext() {
				return delegate.compressionAllowServerNoContext();
			}

			@Override
			public boolean compressionPreferredClientNoContext() {
				return delegate.compressionPreferredClientNoContext();
			}
		};
	}

	private static String readHttpHeaders(InputStream input) throws Exception {
		ByteArrayOutputStream headers = new ByteArrayOutputStream();
		int matched = 0;
		int next;
		while (matched < 4 && (next = input.read()) != -1) {
			headers.write(next);
			matched = next == "\r\n\r\n".charAt(matched) ? matched + 1 : next == '\r' ? 1 : 0;
		}
		return headers.toString(StandardCharsets.US_ASCII.name());
	}

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
