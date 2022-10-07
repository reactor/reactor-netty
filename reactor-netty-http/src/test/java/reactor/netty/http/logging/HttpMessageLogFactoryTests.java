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
package reactor.netty.http.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
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

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.util.internal.StringUtil.NEWLINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class HttpMessageLogFactoryTests extends BaseHttpTest {

	static final byte[] BYTES = "test".getBytes(Charset.defaultCharset());
	static final String URI = "/test?test=test";

	ByteBuf content;
	HttpHeaders headers;
	HttpHeaders trailingHeaders;

	@BeforeEach
	void setUp() {
		content = Unpooled.wrappedBuffer(BYTES);
		headers = new DefaultHttpHeaders();
		headers.set("header", "test");
		trailingHeaders = new DefaultHttpHeaders();
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
		assertThat(result).isEqualTo("HTTP/1.1 200 OK" + NEWLINE +
				"java.lang.IllegalArgumentException: Deliberate");
	}

	@Test
	void testExtendedReactorNettyHttpMessageLogFactory() {
		headers.set("test", "test");
		FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI,
				content, headers, trailingHeaders);
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
		assertThat(argProvider).isInstanceOf(FullHttpRequestArgProvider.class);
		ExtendedReactorNettyHttpMessageLogFactory customFactory = new ExtendedReactorNettyHttpMessageLogFactory();
		String result = customFactory.common(argProvider);
		assertThat(result).isEqualTo(
				"FULL_REQUEST(decodeResult: Success, version: HTTP/1.1, content: UnpooledHeapByteBuf(ridx: 0, widx: 4, cap: 4/4))" + NEWLINE +
						"GET /test?test=test HTTP/1.1" + NEWLINE +
						"header: prefix-test" + NEWLINE +
						"test: test" + NEWLINE +
						"trailing-header: prefix-test");
	}

	@Test
	void testExtendedReactorNettyHttpMessageLogFactoryFailedDecoding() {
		headers.set("test", "test");
		FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI,
				content, headers, trailingHeaders);
		request.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
		assertThat(argProvider).isInstanceOf(FullHttpRequestArgProvider.class);
		ExtendedReactorNettyHttpMessageLogFactory customFactory = new ExtendedReactorNettyHttpMessageLogFactory();
		String result = customFactory.common(argProvider);
		assertThat(result).isEqualTo(
				"FULL_REQUEST(decodeResult: Failure(java.lang.IllegalArgumentException), version: HTTP/1.1," +
						" content: UnpooledHeapByteBuf(ridx: 0, widx: 4, cap: 4/4))" + NEWLINE +
						"GET /test?test=test HTTP/1.1" + NEWLINE +
						"header: prefix-test" + NEWLINE +
						"test: test" + NEWLINE +
						"trailing-header: prefix-test");
	}

	@Test
	void testFullHttpRequest() {
		FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI,
				content, headers, trailingHeaders);
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
		assertThat(argProvider).isInstanceOf(FullHttpRequestArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"FULL_REQUEST(decodeResult: success, version: HTTP/1.1, content: UnpooledHeapByteBuf(ridx: 0, widx: 4, cap: 4/4))" + NEWLINE +
						"GET /test?<filtered> HTTP/1.1" + NEWLINE +
						"header: <filtered>" + NEWLINE +
						"trailing-header: <filtered>");
	}

	@Test
	void testFullHttpRequestFailedDecoding() {
		FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI,
				content, headers, trailingHeaders);
		request.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
		assertThat(argProvider).isInstanceOf(FullHttpRequestArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"FULL_REQUEST(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate)," +
						" version: HTTP/1.1, content: UnpooledHeapByteBuf(ridx: 0, widx: 4, cap: 4/4))" + NEWLINE +
						"GET /test?<filtered> HTTP/1.1" + NEWLINE +
						"header: <filtered>" + NEWLINE +
						"trailing-header: <filtered>");
	}

	@Test
	void testFullHttpResponse() {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
				content, headers, trailingHeaders);
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(response);
		assertThat(argProvider).isInstanceOf(FullHttpResponseArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"FULL_RESPONSE(decodeResult: success, version: HTTP/1.1, content: UnpooledHeapByteBuf(ridx: 0, widx: 4, cap: 4/4))" + NEWLINE +
						"HTTP/1.1 200 OK" + NEWLINE +
						"header: <filtered>" + NEWLINE +
						"trailing-header: <filtered>");
	}

	@Test
	void testFullHttpResponseFailedDecoding() {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
				content, headers, trailingHeaders);
		response.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(response);
		assertThat(argProvider).isInstanceOf(FullHttpResponseArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"FULL_RESPONSE(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate)," +
						" version: HTTP/1.1, content: UnpooledHeapByteBuf(ridx: 0, widx: 4, cap: 4/4))" + NEWLINE +
						"HTTP/1.1 200 OK" + NEWLINE +
						"header: <filtered>" + NEWLINE +
						"trailing-header: <filtered>");
	}

	@Test
	void testHttpRequest() {
		HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI, headers);
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
		assertThat(argProvider).isInstanceOf(HttpRequestArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"REQUEST(decodeResult: success, version: HTTP/1.1)" + NEWLINE +
						"GET /test?<filtered> HTTP/1.1" + NEWLINE +
						"header: <filtered>");
	}

	@Test
	void testHttpRequestFailedDecoding() {
		HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI, headers);
		request.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(request);
		assertThat(argProvider).isInstanceOf(HttpRequestArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"REQUEST(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate), version: HTTP/1.1)" + NEWLINE +
						"GET /test?<filtered> HTTP/1.1" + NEWLINE +
						"header: <filtered>");
	}

	@Test
	void testHttpResponse() {
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, headers);
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(response);
		assertThat(argProvider).isInstanceOf(HttpResponseArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"RESPONSE(decodeResult: success, version: HTTP/1.1)" + NEWLINE +
						"HTTP/1.1 200 OK" + NEWLINE +
						"header: <filtered>");
	}

	@Test
	void testHttpResponseFailedDecoding() {
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, headers);
		response.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(response);
		assertThat(argProvider).isInstanceOf(HttpResponseArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"RESPONSE(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate), version: HTTP/1.1)" + NEWLINE +
						"HTTP/1.1 200 OK" + NEWLINE +
						"header: <filtered>");
	}

	@Test
	void testHttpContent() {
		HttpContent httpContent = new DefaultHttpContent(content);
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(httpContent);
		assertThat(argProvider).isInstanceOf(HttpContentArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"CONTENT(decodeResult: success, content: UnpooledHeapByteBuf(ridx: 0, widx: 4, cap: 4/4))");
	}

	@Test
	void testHttpContentFailedDecoding() {
		HttpContent httpContent = new DefaultHttpContent(content);
		httpContent.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(httpContent);
		assertThat(argProvider).isInstanceOf(HttpContentArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"CONTENT(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate)," +
						" content: UnpooledHeapByteBuf(ridx: 0, widx: 4, cap: 4/4))");
	}

	@Test
	void testLastHttpResponse() {
		LastHttpContent lastContent = new DefaultLastHttpContent(content, trailingHeaders);
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(lastContent);
		assertThat(argProvider).isInstanceOf(LastHttpContentArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"LAST_CONTENT(decodeResult: success, content: UnpooledHeapByteBuf(ridx: 0, widx: 4, cap: 4/4))" + NEWLINE +
						"trailing-header: <filtered>");
	}

	@Test
	void testLastHttpResponseFailedDecoding() {
		LastHttpContent lastContent = new DefaultLastHttpContent(content, trailingHeaders);
		lastContent.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Deliberate")));
		HttpMessageArgProvider argProvider = HttpMessageArgProviderFactory.create(lastContent);
		assertThat(argProvider).isInstanceOf(LastHttpContentArgProvider.class);
		String result = ReactorNettyHttpMessageLogFactory.INSTANCE.common(argProvider);
		assertThat(result).isEqualTo(
				"LAST_CONTENT(decodeResult: failure(java.lang.IllegalArgumentException: Deliberate)," +
						" content: UnpooledHeapByteBuf(ridx: 0, widx: 4, cap: 4/4))" + NEWLINE +
						"trailing-header: <filtered>");
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
						.send(ByteBufMono.fromString(Mono.just("Hello")))
						.responseContent()
						.aggregate()
						.asString();

		LogAppender logAppender = new LogAppender();
		logAppender.start();
		Logger accessLogger = (Logger) LoggerFactory.getLogger("reactor.netty.http.client.HttpClientOperations");
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
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> HttpMessageArgProviderFactory.create(EMPTY_BUFFER))
				.withMessage("Unknown object: EmptyByteBufBE");
	}

	static Stream<Arguments> httpMessageLogFactories() {
		return Stream.of(
				Arguments.of(ReactorNettyHttpMessageLogFactory.INSTANCE,
						"RESPONSE(decodeResult: success, version: HTTP/1.1)" + NEWLINE +
								"HTTP/1.1 200 OK" + NEWLINE +
								"content-length: <filtered>"),
				Arguments.of(new ExtendedReactorNettyHttpMessageLogFactory(),
						"RESPONSE(decodeResult: Success, version: HTTP/1.1)" + NEWLINE +
								"HTTP/1.1 200 OK" + NEWLINE +
								"content-length: prefix-0"),
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
		protected Function<Map.Entry<String, String>, String> headerValueFunction() {
			return e -> "test".equals(e.getKey()) ? e.getValue() : "prefix-" + e.getValue();
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
					arg.protocol() + " " + arg.status() + NEWLINE + arg.decoderResult().cause() :
					null;
		}
	}
}
