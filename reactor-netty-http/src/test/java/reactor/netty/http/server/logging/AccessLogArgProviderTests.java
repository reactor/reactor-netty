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

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.server.logging.LoggingTests.URI;

/**
 * This test class verifies {@link AccessLogArgProvider}.
 *
 * @author limaoning
 */
class AccessLogArgProviderTests {

	private static final SocketAddress REMOTE_ADDRESS = new InetSocketAddress("127.0.0.1", 8080);
	private TestAccessLogArgProvider accessLogArgProvider;

	@BeforeEach
	void beforeEach() {
		accessLogArgProvider = new TestAccessLogArgProvider(REMOTE_ADDRESS);
	}

	@Test
	void remoteAddress() {
		assertThat(accessLogArgProvider.remoteAddress()).isEqualTo(REMOTE_ADDRESS);
	}

	@Test
	void user() {
		assertThat(accessLogArgProvider.user()).isEqualTo(AbstractAccessLogArgProvider.MISSING);
	}

	@Test
	void contentLength() {
		assertThat(accessLogArgProvider.contentLength()).isEqualTo(-1);
		accessLogArgProvider.increaseContentLength(100);
		assertThat(accessLogArgProvider.contentLength()).isEqualTo(-1);
		accessLogArgProvider.method = HttpMethod.HEAD.name();
		accessLogArgProvider.chunked(true);
		accessLogArgProvider.increaseContentLength(100);
		assertThat(accessLogArgProvider.contentLength()).isEqualTo(-1);
		accessLogArgProvider.method = HttpMethod.GET.name();
		accessLogArgProvider.increaseContentLength(100);
		assertThat(accessLogArgProvider.contentLength()).isEqualTo(100);
		accessLogArgProvider.increaseContentLength(200);
		assertThat(accessLogArgProvider.contentLength()).isEqualTo(300);
		accessLogArgProvider.increaseContentLength(-100);
		assertThat(accessLogArgProvider.contentLength()).isEqualTo(300);
	}

	@Test
	@SuppressWarnings("deprecation")
	void onRequest() {
		assertThat(accessLogArgProvider.accessDateTime()).isNull();
		assertThat(accessLogArgProvider.zonedDateTime()).isNull();
		assertThat(accessLogArgProvider.startTime).isZero();
		accessLogArgProvider.onRequest();
		assertThat(accessLogArgProvider.accessDateTime()).isNotNull();
		assertThat(accessLogArgProvider.zonedDateTime()).isNotNull();
		assertThat(accessLogArgProvider.startTime).isNotNull();
	}

	@Test
	@SuppressWarnings("deprecation")
	void clear() {
		assertAccessLogArgProviderInitState();
		accessLogArgProvider.onRequest();
		accessLogArgProvider
				.chunked(true)
				.increaseContentLength(100);
		accessLogArgProvider.cookies(Collections.emptyMap());
		assertThat(accessLogArgProvider.accessDateTime()).isNotNull();
		assertThat(accessLogArgProvider.zonedDateTime()).isNotNull();
		assertThat(accessLogArgProvider.method()).isEqualTo(HttpMethod.POST.name());
		assertThat(accessLogArgProvider.uri()).isEqualTo(URI);
		assertThat(accessLogArgProvider.protocol()).isEqualTo(HttpVersion.HTTP_1_1.text());
		assertThat(accessLogArgProvider.chunked).isTrue();
		assertThat(accessLogArgProvider.contentLength).isEqualTo(100);
		assertThat(accessLogArgProvider.startTime).isNotNull();
		assertThat(accessLogArgProvider.cookies).isNotNull();
		accessLogArgProvider.clear();
		assertAccessLogArgProviderInitState();
	}

	@SuppressWarnings("deprecation")
	private void assertAccessLogArgProviderInitState() {
		assertThat(accessLogArgProvider.accessDateTime()).isNull();
		assertThat(accessLogArgProvider.zonedDateTime()).isNull();
		assertThat(accessLogArgProvider.method()).isNull();
		assertThat(accessLogArgProvider.uri()).isNull();
		assertThat(accessLogArgProvider.protocol()).isNull();
		assertThat(accessLogArgProvider.chunked).isFalse();
		assertThat(accessLogArgProvider.contentLength).isEqualTo(-1);
		assertThat(accessLogArgProvider.startTime).isZero();
		assertThat(accessLogArgProvider.cookies).isNull();
	}

	static class TestAccessLogArgProvider extends AbstractAccessLogArgProvider<TestAccessLogArgProvider> {

		TestAccessLogArgProvider(SocketAddress remoteAddress) {
			super(remoteAddress);
		}

		@Override
		public CharSequence status() {
			return "200";
		}

		@Override
		public CharSequence requestHeader(CharSequence name) {
			return "requestHeader";
		}

		@Override
		public CharSequence responseHeader(CharSequence name) {
			return "responseHeader";
		}

		@Override
		void onRequest() {
			super.onRequest();
			super.method = HttpMethod.POST.name();
			super.uri = URI;
			super.protocol = HttpVersion.HTTP_1_1.text();
		}

		@Override
		public TestAccessLogArgProvider get() {
			return this;
		}

	}

}
