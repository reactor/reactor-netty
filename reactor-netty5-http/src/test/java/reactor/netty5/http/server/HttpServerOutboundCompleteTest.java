/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.server;

import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.FullHttpResponse;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.util.concurrent.Future;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.netty5.BaseHttpTest;
import reactor.netty5.DisposableServer;
import reactor.netty5.http.HttpProtocol;
import reactor.netty5.http.client.HttpClient;
import reactor.test.StepVerifier;
import reactor.util.annotation.Nullable;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static reactor.netty5.NettyPipeline.HttpTrafficHandler;
import static org.assertj.core.api.Assertions.assertThat;

class HttpServerOutboundCompleteTest extends BaseHttpTest {
	static final String REPEAT = createString(1024);
	static final String EXPECTED_REPEAT = createString(4096);

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpGetRespondsSend(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(4);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.get("/1", (req, res) -> res.send().doOnEach(recorder).doOnCancel(recorder)));

		sendGetRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Collections.emptyList())
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(1);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(1);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(1);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpGetRespondsSendFlux(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(3);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.get("/1", (req, res) -> res.sendString(Flux.just(REPEAT, REPEAT, REPEAT, REPEAT).doOnEach(recorder).doOnCancel(recorder))));

		sendGetRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Collections.singletonList(EXPECTED_REPEAT))
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(1);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(0);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(1);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpGetRespondsSendFluxContentAlwaysEmpty(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(4);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.get("/1", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
						.sendString(Flux.just(REPEAT, REPEAT, REPEAT, REPEAT).doOnEach(recorder).doOnCancel(recorder))));

		sendGetRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Collections.emptyList())
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(1);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(1);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(1);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpGetRespondsSendFluxContentLengthZero(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(4);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.get("/1", (req, res) -> res.header(HttpHeaderNames.CONTENT_LENGTH, "0")
						.sendString(Flux.just(REPEAT, REPEAT, REPEAT, REPEAT).doOnEach(recorder).doOnCancel(recorder))));

		sendGetRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Collections.emptyList())
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(1);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(1);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(1);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpGetRespondsSendHeaders(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(3);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.get("/1", (req, res) -> res.sendHeaders().then().doOnEach(recorder).doOnCancel(recorder)));

		sendGetRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Collections.emptyList())
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(1);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(0);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(1);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpGetRespondsSendMono(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(4);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.get("/1", (req, res) -> res.sendString(Mono.just(REPEAT).doOnEach(recorder).doOnCancel(recorder))));

		sendGetRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Collections.singletonList(REPEAT))
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(1);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(1);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(1);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpGetRespondsSendMonoEmpty(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(4);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.get("/1", (req, res) -> Mono.<Void>empty().doOnEach(recorder).doOnCancel(recorder)));

		sendGetRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Collections.emptyList())
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(1);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(1);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(1);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpGetRespondsSendObject(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(4);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.get("/1", (req, res) -> res.sendObject(res.alloc().copyOf(REPEAT, Charset.defaultCharset()))
						.then().doOnEach(recorder).doOnCancel(recorder)));

		sendGetRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Collections.singletonList(REPEAT))
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(1);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(1);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(1);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpPostRespondsSend(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(8);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.post("/1", (req, res) -> res.send().doOnEach(recorder).doOnCancel(recorder))
						.post("/2", (req, res) -> req.receive().then(res.send().doOnEach(recorder).doOnCancel(recorder))));

		sendPostRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Collections.emptyList())
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(2);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(2);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(2);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(2);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpPostRespondsSendFlux(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(6);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.post("/1", (req, res) -> res.sendString(Flux.just(REPEAT, REPEAT, REPEAT, REPEAT).doOnEach(recorder).doOnCancel(recorder)))
						.post("/2", (req, res) -> res.send(req.receive().transferOwnership()).then().doOnEach(recorder).doOnCancel(recorder)));

		sendPostRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Arrays.asList(EXPECTED_REPEAT, EXPECTED_REPEAT))
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(2);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(0);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(2);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(2);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpPostRespondsSendHeaders(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(6);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.post("/1", (req, res) -> res.sendHeaders().then().doOnEach(recorder).doOnCancel(recorder))
						.post("/2", (req, res) -> req.receive().then(res.sendHeaders().then().doOnEach(recorder).doOnCancel(recorder))));

		sendPostRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Collections.emptyList())
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(2);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(0);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(2);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(2);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpPostRespondsSendMono(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(8);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.post("/1", (req, res) -> res.sendString(Mono.just(REPEAT).doOnEach(recorder).doOnCancel(recorder)))
						.post("/2", (req, res) -> res.send(req.receive().aggregate().transferOwnership()).then().doOnEach(recorder).doOnCancel(recorder)));

		sendPostRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Arrays.asList(REPEAT, EXPECTED_REPEAT))
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(2);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(2);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(2);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(2);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpPostRespondsSendMonoEmpty(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(8);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.post("/1", (req, res) -> Mono.<Void>empty().doOnEach(recorder).doOnCancel(recorder))
						.post("/2", (req, res) -> req.receive().then(Mono.<Void>empty().doOnEach(recorder).doOnCancel(recorder))));

		sendPostRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Collections.emptyList())
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(2);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(2);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(2);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(2);
	}

	@ParameterizedTest
	@EnumSource(value = HttpProtocol.class, names = {"HTTP11", "H2C"})
	void httpPostRespondsSendObject(HttpProtocol protocol) throws Exception {
		CountDownLatch latch = new CountDownLatch(8);
		EventsRecorder recorder = new EventsRecorder(latch);
		disposableServer = createServer(recorder, protocol,
				r -> r.post("/1", (req, res) -> res.sendObject(res.alloc().copyOf(REPEAT, Charset.defaultCharset()))
								.then().doOnEach(recorder).doOnCancel(recorder))
						.post("/2", (req, res) -> req.receive().then(res.sendObject(res.alloc().copyOf(REPEAT, Charset.defaultCharset()))
								.then().doOnEach(recorder).doOnCancel(recorder))));

		sendPostRequest(disposableServer.port(), protocol)
				.as(StepVerifier::create)
				.expectNext(Arrays.asList(REPEAT, REPEAT))
				.expectComplete()
				.verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(recorder.bufferIsReleased.get()).isEqualTo(2);
		assertThat(recorder.fullResponseIsSent.get()).isEqualTo(2);
		assertThat(recorder.onCompleteIsReceived.get()).isEqualTo(2);
		assertThat(recorder.onTerminateIsReceived.get()).isEqualTo(2);
	}

	static DisposableServer createServer(EventsRecorder recorder, HttpProtocol protocol, Consumer<? super HttpServerRoutes> routes) {
		return createServer()
				.protocol(protocol)
				.doOnChannelInit((obs, ch, addr) -> {
					if (protocol == HttpProtocol.HTTP11) {
						ch.pipeline().addBefore(HttpTrafficHandler, "eventsRecorderHandler", new EventsRecorderHandler(recorder));
					}
				})
				.doOnConnection(conn -> {
					conn.onTerminate().subscribe(null, null, recorder::recordOnTerminateIsReceived);
					if (protocol == HttpProtocol.H2C) {
						conn.channel().pipeline().addBefore(HttpTrafficHandler, "eventsRecorderHandler", new EventsRecorderHandler(recorder));
					}
				})
				.route(routes)
				.bindNow();
	}

	static String createString(int length) {
		char[] chars = new char[length];
		Arrays.fill(chars, 'm');
		return new String(chars);
	}

	static Mono<List<String>> sendGetRequest(int port, HttpProtocol protocol) {
		return sendRequest(port, protocol, HttpMethod.GET, 1, null);
	}

	static Mono<List<String>> sendPostRequest(int port, HttpProtocol protocol) {
		return sendRequest(port, protocol, HttpMethod.POST, 2, Flux.just(REPEAT, REPEAT, REPEAT, REPEAT));
	}

	static Mono<List<String>> sendRequest(int port, HttpProtocol protocol, HttpMethod method, int numRequests,
			@Nullable Publisher<? extends String> body) {
		HttpClient client = createClient(port).protocol(protocol);
		return Flux.range(1, numRequests)
				.flatMap(i ->
						client.request(method)
								.uri("/" + i)
								.send((req, out) -> body != null ? out.sendString(body) : out)
								.responseContent()
								.aggregate()
								.asString())
				.collectList();
	}

	static class EventsRecorder implements Consumer<Signal<?>>, Runnable {
		final AtomicInteger bufferIsReleased = new AtomicInteger();
		final AtomicInteger fullResponseIsSent = new AtomicInteger();
		final AtomicInteger onCompleteIsReceived = new AtomicInteger();
		final AtomicInteger onTerminateIsReceived = new AtomicInteger();

		final CountDownLatch latch;

		EventsRecorder(CountDownLatch latch) {
			this.latch = latch;
		}

		@Override
		public void accept(Signal<?> sig) {
			if (sig.isOnComplete()) {
				onCompleteIsReceived.incrementAndGet();
				latch.countDown();
			}
		}

		@Override
		public void run() {
			onCompleteIsReceived.decrementAndGet();
			latch.countDown();
		}

		void recordBufferIsReleased() {
			bufferIsReleased.incrementAndGet();
			latch.countDown();
		}

		void recordFullResponseIsSent() {
			fullResponseIsSent.incrementAndGet();
			latch.countDown();
		}

		void recordOnTerminateIsReceived() {
			onTerminateIsReceived.incrementAndGet();
			latch.countDown();
		}
	}

	static class EventsRecorderHandler extends ChannelHandlerAdapter {
		final EventsRecorder recorder;

		int counter;

		EventsRecorderHandler(EventsRecorder recorder) {
			this.recorder = recorder;
		}

		@Override
		@SuppressWarnings("ReferenceEquality")
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			Buffer content = null;
			if (msg instanceof HttpContent<?> httpContent) {
				content = httpContent.payload();
				counter++;
			}

			ctx.fireChannelRead(msg);

			// "ReferenceEquality" this is deliberate
			if (content != null && !content.isAccessible()) {
				counter--;
			}
			if (msg instanceof LastHttpContent && counter == 0) {
				recorder.recordBufferIsReleased();
			}
		}

		@Override
		public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
			if (msg instanceof FullHttpResponse) {
				recorder.recordFullResponseIsSent();
			}

			return ctx.write(msg);
		}
	}
}