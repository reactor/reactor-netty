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
package reactor.netty.http.server.logging;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static reactor.netty.http.server.logging.LoggingTests.HEADER_CONNECTION_NAME;
import static reactor.netty.http.server.logging.LoggingTests.HEADER_CONNECTION_VALUE;
import static reactor.netty.http.server.logging.LoggingTests.URI;


/**
 * @author limaoning
 */
class AccessLogArgProviderH1Tests {

	private static final HttpRequest request;

	static {
		HttpHeaders httpHeaders = new DefaultHttpHeaders();
		httpHeaders.add(HEADER_CONNECTION_NAME, HEADER_CONNECTION_VALUE);
		request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI, httpHeaders);
	}

	private AccessLogArgProviderH1 accessLogArgProvider;

	@BeforeEach
	void beforeEach() {
		accessLogArgProvider = new AccessLogArgProviderH1(new InetSocketAddress("127.0.0.1", 8080));
	}

	@Test
	void request() {
		assertThat(accessLogArgProvider.request).isNull();
		assertThatNullPointerException().isThrownBy(() -> accessLogArgProvider.request(null));
		accessLogArgProvider.request(request);
		assertThat(accessLogArgProvider.request).isEqualTo(request);
	}

	@Test
	void method() {
		assertThat(accessLogArgProvider.method()).isNull();
		accessLogArgProvider.request(request);
		assertThat(accessLogArgProvider.method()).isEqualTo(HttpMethod.GET.name());
	}

	@Test
	void uri() {
		assertThat(accessLogArgProvider.uri()).isNull();
		accessLogArgProvider.request(request);
		assertThat(accessLogArgProvider.uri()).isEqualTo(URI);
	}

	@Test
	void protocol() {
		assertThat(accessLogArgProvider.protocol()).isNull();
		accessLogArgProvider.request(request);
		assertThat(accessLogArgProvider.protocol()).isEqualTo(HttpVersion.HTTP_1_1.text());
	}

	@Test
	void requestHeader() {
		assertThatNullPointerException().isThrownBy(() -> accessLogArgProvider.requestHeader(null));
		assertThat(accessLogArgProvider.requestHeader(HEADER_CONNECTION_NAME)).isNull();
		accessLogArgProvider.request(request);
		assertThat(accessLogArgProvider.requestHeader(HEADER_CONNECTION_NAME))
				.isEqualTo(HEADER_CONNECTION_VALUE);
	}

	@Test
	void clear() {
		assertThat(accessLogArgProvider.request).isNull();
		accessLogArgProvider.request(request);
		assertThat(accessLogArgProvider.request).isEqualTo(request);
		accessLogArgProvider.clear();
		assertThat(accessLogArgProvider.request).isNull();
	}

	@Test
	void get() {
		assertThat(accessLogArgProvider.get()).isEqualTo(accessLogArgProvider);
	}

	@Test
	void contentLength() {
		assertThat(accessLogArgProvider.contentLength()).isEqualTo(-1);
		accessLogArgProvider.contentLength(100);
		assertThat(accessLogArgProvider.contentLength()).isEqualTo(100);
	}

}
