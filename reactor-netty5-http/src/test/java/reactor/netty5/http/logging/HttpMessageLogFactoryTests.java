/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.netty5.buffer.Buffer;
import io.netty5.handler.codec.DecoderResult;
import io.netty5.handler.codec.http.DefaultFullHttpRequest;
import io.netty5.handler.codec.http.DefaultFullHttpResponse;
import io.netty5.handler.codec.http.DefaultHttpContent;
import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.DefaultHttpResponse;
import io.netty5.handler.codec.http.DefaultLastHttpContent;
import io.netty5.handler.codec.http.FullHttpRequest;
import io.netty5.handler.codec.http.FullHttpResponse;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.HttpVersion;
import io.netty5.handler.codec.http.LastHttpContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty5.BaseHttpTest;
import reactor.netty5.BufferMono;
import reactor.netty5.http.client.HttpClient;
import reactor.test.StepVerifier;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.netty5.buffer.DefaultBufferAllocators.preferredAllocator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class HttpMessageLogFactoryTests extends BaseHttpTest {

	static final byte[] BYTES = "test".getBytes(Charset.defaultCharset());
	static final String URI = "/test?test=test";

	HttpHeaders headers;
	HttpHeaders trailingHeaders;

	@BeforeEach
	void setUp() {
		headers = HttpHeaders.newHeaders();
		headers.set("header", "test");
		trailingHeaders = HttpHeaders.newHeaders();
		trailingHeaders.set("trailing-header", "test");
	}

	@Test
	void testCustomHttpMessageLogFactory() {
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, headers);
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(response);
		assertThat(argProvider).isInstanceOf(HttpResponseArgProvider.class);
		TestHttpMessageLogFactory customFactory = new TestHttpMessageLogFactory();
		String result = customFactory.debug(argProvider);
		assertThat(result).isEqualTo("HTTP/1.1 200 OK");
	}

	@Test
	void testCustomHttpMessageLogFactoryFailedDecoding() {
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, headers);
		response.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(response);
		assertThat(argProvider).isInstanceOf(HttpResponseArgProvider.class);
		TestHttpMessageLogFactory customFactory = new TestHttpMessageLogFactory();
		String result = customFactory.error(argProvider);
		assertThat(result).isEqualTo("HTTP/1.1 200 OK\n" +
				"java.lang.IllegalArgumentException: Deliberate");
	}

	@Test
	void testExtendedReactorNettyHttpMessageLogFactory() {
		headers.set("test", "test");
		try (FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI,
				preferredAllocator().copyOf(BYTES), headers, trailingHeaders)) {
			HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
			assertThat(argProvider).isInstanceOf(FullHttpRequestArgProvider.class);
			ExtendedReactorNettyHttpMessageLogFactory customFactory = new ExtendedReactorNettyHttpMessageLogFactory();
			String result = customFactory.common(argProvider);
			assertThat(result).isEqualTo(
					"""
							FULL_REQUEST(decodeResult: Success, version: HTTP/1.1, content: Buffer[roff:0, woff:4, cap:4])
							GET /test?test=test HTTP/1.1
							test: test
							header: prefix-test
							trailing-header: prefix-test""");
		}
	}

	@Test
	void testExtendedReactorNettyHttpMessageLogFactoryFailedDecoding() {
		headers.set("test", "test");
		try (FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI,
				preferredAllocator().copyOf(BYTES), headers, trailingHeaders)) {
			request.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
			HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
			assertThat(argProvider).isInstanceOf(FullHttpRequestArgProvider.class);
			ExtendedReactorNettyHttpMessageLogFactory customFactory = new ExtendedReactorNettyHttpMessageLogFactory();
			String result = customFactory.common(argProvider);
			assertThat(result).isEqualTo(
					"""
							FULL_REQUEST(decodeResult: Failure(java.lang.IllegalArgumentException), version: HTTP/1.1, content: Buffer[roff:0, woff:4, cap:4])
							GET /test?test=test HTTP/1.1
							test: test
							header: prefix-test
							trailing-header: prefix-test""");
		}
	}

	@Test
	void testFullHttpRequest() {
		try (FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI,
				preferredAllocator().copyOf(BYTES), headers, trailingHeaders)) {
			HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
			assertThat(argProvider).isInstanceOf(FullHttpRequestArgProvider.class);
			String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
			assertThat(result).isEqualTo(
					"""
							FULL_REQUEST(decodeResult: success, version: HTTP/1.1, content: Buffer[roff:0, woff:4, cap:4])
							GET /test?<filtered> HTTP/1.1
							header: <filtered>
							trailing-header: <filtered>""");
		}
	}

	@Test
	void testFullHttpRequestFailedDecoding() {
		try (FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI,
				preferredAllocator().copyOf(BYTES), headers, trailingHeaders)) {
			request.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
			HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
			assertThat(argProvider).isInstanceOf(FullHttpRequestArgProvider.class);
			String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
			assertThat(result).isEqualTo(
					"""
							FULL_REQUEST(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate), version: HTTP/1.1, content: Buffer[roff:0, woff:4, cap:4])
							GET /test?<filtered> HTTP/1.1
							header: <filtered>
							trailing-header: <filtered>""");
		}
	}

	@Test
	void testFullHttpResponse() {
		try (FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
				preferredAllocator().copyOf(BYTES), headers, trailingHeaders)) {
			HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(response);
			assertThat(argProvider).isInstanceOf(FullHttpResponseArgProvider.class);
			String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
			assertThat(result).isEqualTo(
					"""
							FULL_RESPONSE(decodeResult: success, version: HTTP/1.1, content: Buffer[roff:0, woff:4, cap:4])
							HTTP/1.1 200 OK
							header: <filtered>
							trailing-header: <filtered>""");
		}
	}

	@Test
	void testFullHttpResponseFailedDecoding() {
		try (FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
				preferredAllocator().copyOf(BYTES), headers, trailingHeaders)) {
			response.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
			HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(response);
			assertThat(argProvider).isInstanceOf(FullHttpResponseArgProvider.class);
			String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
			assertThat(result).isEqualTo(
					"""
							FULL_RESPONSE(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate), version: HTTP/1.1, content: Buffer[roff:0, woff:4, cap:4])
							HTTP/1.1 200 OK
							header: <filtered>
							trailing-header: <filtered>""");
		}
	}

	@Test
	void testHttpRequest() {
		HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI, headers);
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
		assertThat(argProvider).isInstanceOf(HttpRequestArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"""
						REQUEST(decodeResult: success, version: HTTP/1.1)
						GET /test?<filtered> HTTP/1.1
						header: <filtered>""");
	}

	@Test
	void testHttpRequestFailedDecoding() {
		HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI, headers);
		request.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
		assertThat(argProvider).isInstanceOf(HttpRequestArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"""
						REQUEST(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate), version: HTTP/1.1)
						GET /test?<filtered> HTTP/1.1
						header: <filtered>""");
	}

	@Test
	void testHttpResponse() {
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, headers);
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(response);
		assertThat(argProvider).isInstanceOf(HttpResponseArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"""
						RESPONSE(decodeResult: success, version: HTTP/1.1)
						HTTP/1.1 200 OK
						header: <filtered>""");
	}

	@Test
	void testHttpResponseFailedDecoding() {
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, headers);
		response.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(response);
		assertThat(argProvider).isInstanceOf(HttpResponseArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"""
						RESPONSE(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate), version: HTTP/1.1)
						HTTP/1.1 200 OK
						header: <filtered>""");
	}

	@Test
	void testHttpContent() {
		try (HttpContent<?> httpContent = new DefaultHttpContent(preferredAllocator().copyOf(BYTES))) {
			HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(httpContent);
			assertThat(argProvider).isInstanceOf(HttpContentArgProvider.class);
			String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
			assertThat(result).isEqualTo(
					"CONTENT(decodeResult: success, content: Buffer[roff:0, woff:4, cap:4])");
		}
	}

	@Test
	void testHttpContentFailedDecoding() {
		try (HttpContent<?> httpContent = new DefaultHttpContent(preferredAllocator().copyOf(BYTES))) {
			httpContent.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
			HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(httpContent);
			assertThat(argProvider).isInstanceOf(HttpContentArgProvider.class);
			String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
			assertThat(result).isEqualTo(
					"CONTENT(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate)," +
							" content: Buffer[roff:0, woff:4, cap:4])");
		}
	}

	@Test
	void testLastHttpResponse() {
		try (LastHttpContent<?> lastContent = new DefaultLastHttpContent(preferredAllocator().copyOf(BYTES), trailingHeaders)) {
			HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(lastContent);
			assertThat(argProvider).isInstanceOf(LastHttpContentArgProvider.class);
			String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
			assertThat(result).isEqualTo(
					"LAST_CONTENT(decodeResult: success, content: Buffer[roff:0, woff:4, cap:4])\n" +
							"trailing-header: <filtered>");
		}
	}

	@Test
	void testLastHttpResponseFailedDecoding() {
		try (LastHttpContent<?> lastContent = new DefaultLastHttpContent(preferredAllocator().copyOf(BYTES), trailingHeaders)) {
			lastContent.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
			HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(lastContent);
			assertThat(argProvider).isInstanceOf(LastHttpContentArgProvider.class);
			String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
			assertThat(result).isEqualTo(
					"LAST_CONTENT(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate)," +
							" content: Buffer[roff:0, woff:4, cap:4])\n" +
							"trailing-header: <filtered>");
		}
	}

	@ParameterizedTest
	@MethodSource("httpMessageLogFactories")
	void testLogMessage(HttpMessageLogFactory httpMessageLogFactory, String expectation) throws Exception {
		disposableServer =
				createServer()
						.route(r -> r.post("/test/{param}", (req, res) -> Mono.empty()))
						.bindNow();

		HttpClient client = httpMessageLogFactory == ReactorNettyHttpMessageLogFactory.INSTANCE ?
				createClient(disposableServer.port()) :
				createClient(disposableServer.port()).httpMessageLogFactory(httpMessageLogFactory);

		Mono<String> content =
				client.headers(h -> h.add("Content-Type", "text/plain"))
						.post()
						.uri("/test/World")
						.send(BufferMono.fromString(Mono.just("Hello")))
						.responseContent()
						.aggregate()
						.asString();

		LogAppender logAppender = new LogAppender();
		logAppender.start();
		Logger accessLogger = (Logger) LoggerFactory.getLogger("reactor.netty5.http.client.HttpClientOperations");
		try {
			accessLogger.addAppender(logAppender);

			StepVerifier.create(content)
					.expectComplete()
					.verify(Duration.ofSeconds(30));

			assertThat(logAppender.latch.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(logAppender.list).hasSize(2);
			assertThat(logAppender.list.get(0).getFormattedMessage()).contains(expectation);
		}
		finally {
			accessLogger.detachAppender(logAppender);
			logAppender.stop();
		}
	}

	@Test
	void testUnsupportedMessage() {
		try (Buffer empty = preferredAllocator().allocate(0)) {
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> HttpMessageArgProviderFactory.create(empty))
					.withMessage("Unknown object: Buffer[roff:0, woff:0, cap:0]");
		}
	}

	static Stream<Arguments> httpMessageLogFactories() {
		return Stream.of(
				Arguments.of(ReactorNettyHttpMessageLogFactory.INSTANCE,
						"""
								RESPONSE(decodeResult: success, version: HTTP/1.1)
								HTTP/1.1 200 OK
								content-length: <filtered>"""),
				Arguments.of(new ExtendedReactorNettyHttpMessageLogFactory(),
						"""
								RESPONSE(decodeResult: Success, version: HTTP/1.1)
								HTTP/1.1 200 OK
								content-length: prefix-0"""),
				Arguments.of(new TestHttpMessageLogFactory(), "HTTP/1.1 200 OK"));
	}

	static final class ExtendedReactorNettyHttpMessageLogFactory extends ReactorNettyHttpMessageLogFactory {

		@Override
		protected Function<String, String> uriValueFunction() {
			return Function.identity();
		}

		@Override
		protected Function<DecoderResult, String> decoderResultFunction() {
			return decoderResult -> {
				if (decoderResult.isSuccess()) {
					return "Success";
				}
				String cause = decoderResult.cause().getClass().getName();
				return new StringBuilder(cause.length() + 17)
						.append("Failure(")
						.append(cause)
						.append(')')
						.toString();
			};
		}

		@Override
		protected Function<Map.Entry<CharSequence, CharSequence>, String> headerValueFunction() {
			return e -> "test".contentEquals(e.getKey()) ? e.getValue().toString() : "prefix-" + e.getValue();
		}
	}

	static final class LogAppender extends AppenderBase<ILoggingEvent> {

		final CountDownLatch latch = new CountDownLatch(1);
		final List<ILoggingEvent> list = new ArrayList<>();

		@Override
		protected void append(ILoggingEvent eventObject) {
			list.add(eventObject);
			latch.countDown();
		}
	}

	static final class TestHttpMessageLogFactory implements HttpMessageLogFactory {

		@Override
		public String trace(HttpMessageArgProvider arg) {
			return null;
		}

		@Override
		public String debug(HttpMessageArgProvider arg) {
			return arg.httpMessageType() == HttpMessageType.RESPONSE ?
					arg.protocol() + " " + arg.status() : null;
		}

		@Override
		public String info(HttpMessageArgProvider arg) {
			return null;
		}

		@Override
		public String warn(HttpMessageArgProvider arg) {
			return null;
		}

		@Override
		public String error(HttpMessageArgProvider arg) {
			return arg.httpMessageType() == HttpMessageType.RESPONSE ?
					arg.protocol() + " " + arg.status() + '\n' + arg.decoderResult().cause() :
					null;
		}
	}
}
