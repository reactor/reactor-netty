/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static reactor.netty.http.server.logging.LoggingTests.HEADER_TEST_NAME;
import static reactor.netty.http.server.logging.LoggingTests.HEADER_TEST_VALUE;
import static reactor.netty.http.server.logging.LoggingTests.URI;


/**
 * This test class verifies {@link AccessLogArgProviderH2}.
 *
 * @author limaoning
 */
class AccessLogArgProviderH2Tests {

	private static final Http2HeadersFrame requestHeaders;
	private static final Http2HeadersFrame responseHeaders;

	static {
		Http2Headers requestHttpHeaders = new DefaultHttp2Headers();
		requestHttpHeaders.add(HEADER_TEST_NAME, HEADER_TEST_VALUE);
		requestHttpHeaders.method(HttpMethod.GET.name());
		requestHttpHeaders.path(URI);
		requestHeaders = new DefaultHttp2HeadersFrame(requestHttpHeaders);

		Http2Headers responseHttpHeaders = new DefaultHttp2Headers();
		responseHttpHeaders.add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
		responseHttpHeaders.status(HttpResponseStatus.OK.codeAsText());
		responseHeaders = new DefaultHttp2HeadersFrame(responseHttpHeaders);
	}

	private AccessLogArgProviderH2 accessLogArgProvider;

	@BeforeEach
	void beforeEach() {
		accessLogArgProvider = new AccessLogArgProviderH2(new InetSocketAddress("127.0.0.1", 8080));
	}

	@Test
	void requestHeaders() {
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
		assertThat(accessLogArgProvider.protocol()).isEqualTo(AccessLogArgProviderH2.H2_PROTOCOL_NAME);
	}

	@Test
	void requestHeader() {
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

}
