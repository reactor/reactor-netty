/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server.logging;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AccessLogArgProviderH3Tests {
	static final CharSequence HEADER_TEST_NAME = "test";
	static final String HEADER_TEST_VALUE = "test";
	static final String URI = "/hello";

	private static final Http3HeadersFrame requestHeaders;
	private static final Http3HeadersFrame responseHeaders;

	static {
		Http3Headers requestHttpHeaders = new DefaultHttp3Headers();
		requestHttpHeaders.add(HEADER_TEST_NAME, HEADER_TEST_VALUE);
		requestHttpHeaders.method(HttpMethod.GET.name());
		requestHttpHeaders.path(URI);
		requestHeaders = new DefaultHttp3HeadersFrame(requestHttpHeaders);

		Http3Headers responseHttpHeaders = new DefaultHttp3Headers();
		responseHttpHeaders.add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
		responseHttpHeaders.status(HttpResponseStatus.OK.codeAsText());
		responseHeaders = new DefaultHttp3HeadersFrame(responseHttpHeaders);
	}

	private AccessLogArgProviderH3 accessLogArgProvider;

	@BeforeEach
	void beforeEach() {
		accessLogArgProvider = new AccessLogArgProviderH3(new InetSocketAddress("127.0.0.1", 8080));
	}

	@Test
	@SuppressWarnings("NullAway")
	void requestHeaders() {
		// Deliberately suppress "NullAway" for testing purposes
		assertThatNullPointerException().isThrownBy(() -> accessLogArgProvider.requestHeaders(null));
		accessLogArgProvider.requestHeaders(requestHeaders);
		assertThat(accessLogArgProvider.requestHeaders).isEqualTo(requestHeaders);
	}

	@Test
	void method() {
		assertThat(accessLogArgProvider.method()).isNull();
		accessLogArgProvider.requestHeaders(requestHeaders);
		assertThat(accessLogArgProvider.method()).isEqualTo(HttpMethod.GET.name());
	}

	@Test
	void uri() {
		assertThat(accessLogArgProvider.uri()).isNull();
		accessLogArgProvider.requestHeaders(requestHeaders);
		assertThat(accessLogArgProvider.uri()).isEqualTo(URI);
	}

	@Test
	void protocol() {
		assertThat(accessLogArgProvider.protocol()).isNull();
		accessLogArgProvider.requestHeaders(requestHeaders);
		assertThat(accessLogArgProvider.protocol()).isEqualTo(AccessLogArgProviderH3.H3_PROTOCOL_NAME);
	}

	@Test
	@SuppressWarnings("NullAway")
	void requestHeader() {
		// Deliberately suppress "NullAway" for testing purposes
		assertThatNullPointerException().isThrownBy(() -> accessLogArgProvider.requestHeader(null));
		assertThat(accessLogArgProvider.requestHeader(HEADER_TEST_NAME)).isNull();
		accessLogArgProvider.requestHeaders(requestHeaders);
		assertThat(accessLogArgProvider.requestHeader(HEADER_TEST_NAME))
				.isEqualTo(HEADER_TEST_VALUE);
	}

	@Test
	void clear() {
		assertThat(accessLogArgProvider.requestHeaders).isNull();
		assertThat(accessLogArgProvider.responseHeaders).isNull();
		accessLogArgProvider.requestHeaders(requestHeaders);
		accessLogArgProvider.responseHeaders(responseHeaders);
		assertThat(accessLogArgProvider.requestHeaders).isEqualTo(requestHeaders);
		assertThat(accessLogArgProvider.responseHeaders).isEqualTo(responseHeaders);
		accessLogArgProvider.clear();
		assertThat(accessLogArgProvider.requestHeaders).isNull();
		assertThat(accessLogArgProvider.responseHeaders).isNull();
	}

	@Test
	void get() {
		assertThat(accessLogArgProvider.get()).isEqualTo(accessLogArgProvider);
	}

	@Test
	void status() {
		assertThat(accessLogArgProvider.status()).isNull();
		accessLogArgProvider.responseHeaders(responseHeaders);
		assertThat(accessLogArgProvider.status()).isEqualTo(HttpResponseStatus.OK.codeAsText());
	}

	@Test
	void responseHeader() {
		assertThat(accessLogArgProvider.responseHeader(HttpHeaderNames.CONTENT_TYPE)).isNull();
		accessLogArgProvider.responseHeaders(responseHeaders);
		assertThat(accessLogArgProvider.responseHeader(HttpHeaderNames.CONTENT_TYPE))
				.isEqualTo(HttpHeaderValues.APPLICATION_JSON);
	}

	@Test
	@SuppressWarnings({"CollectionUndefinedEquality", "DataFlowIssue"})
	void requestHeaderIterator() {
		assertThat(accessLogArgProvider.requestHeaderIterator()).isNull();
		accessLogArgProvider.requestHeaders(requestHeaders);
		assertThat(accessLogArgProvider.requestHeaderIterator()).isNotNull();
		Map<CharSequence, CharSequence> requestHeaders = new HashMap<>();
		accessLogArgProvider.requestHeaderIterator().forEachRemaining(e -> requestHeaders.put(e.getKey(), e.getValue()));
		assertThat(requestHeaders.size()).isEqualTo(3);
		assertThat(requestHeaders.get(HEADER_TEST_NAME)).isEqualTo(HEADER_TEST_VALUE);
		assertThat(requestHeaders.get(Http3Headers.PseudoHeaderName.METHOD.value())).isEqualTo(HttpMethod.GET.name());
		assertThat(requestHeaders.get(Http3Headers.PseudoHeaderName.PATH.value())).isEqualTo(URI);
	}

	@Test
	@SuppressWarnings({"CollectionUndefinedEquality", "DataFlowIssue"})
	void responseHeaderIterator() {
		assertThat(accessLogArgProvider.responseHeaderIterator()).isNull();
		accessLogArgProvider.responseHeaders(responseHeaders);
		assertThat(accessLogArgProvider.responseHeaderIterator()).isNotNull();
		Map<CharSequence, CharSequence> responseHeaders = new HashMap<>();
		accessLogArgProvider.responseHeaderIterator().forEachRemaining(e -> responseHeaders.put(e.getKey(), e.getValue()));
		assertThat(responseHeaders.size()).isEqualTo(2);
		assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(HttpHeaderValues.APPLICATION_JSON);
		assertThat(responseHeaders.get(Http3Headers.PseudoHeaderName.STATUS.value())).isEqualTo(HttpResponseStatus.OK.codeAsText());
	}

}
