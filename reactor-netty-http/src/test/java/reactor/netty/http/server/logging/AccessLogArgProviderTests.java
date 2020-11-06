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

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static reactor.netty.http.server.logging.LoggingTests.URI;

/**
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
	void status() {
		assertThat(accessLogArgProvider.status()).isNull();
		assertThatNullPointerException().isThrownBy(() -> accessLogArgProvider.status(null));
		accessLogArgProvider.status("200");
		assertThat(accessLogArgProvider.status()).isEqualTo("200");
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
	void onRequest() {
		assertThat(accessLogArgProvider.zonedDateTime()).isNull();
		assertThat(accessLogArgProvider.startTime).isZero();
		accessLogArgProvider.onRequest();
		assertThat(accessLogArgProvider.zonedDateTime()).isNotNull();
		assertThat(accessLogArgProvider.startTime).isNotNull();
	}

	@Test
	void clear() {
		assertAccessLogArgProviderInitState();
		accessLogArgProvider.onRequest();
		accessLogArgProvider
				.chunked(true)
				.increaseContentLength(100)
				.status("200");
		assertThat(accessLogArgProvider.zonedDateTime()).isNotNull();
		assertThat(accessLogArgProvider.method()).isEqualTo(HttpMethod.POST.name());
		assertThat(accessLogArgProvider.uri()).isEqualTo(URI);
		assertThat(accessLogArgProvider.protocol()).isEqualTo(HttpVersion.HTTP_1_1.text());
		assertThat(accessLogArgProvider.status()).isEqualTo("200");
		assertThat(accessLogArgProvider.chunked).isTrue();
		assertThat(accessLogArgProvider.contentLength).isEqualTo(100);
		assertThat(accessLogArgProvider.startTime).isNotNull();
		accessLogArgProvider.clear();
		assertAccessLogArgProviderInitState();
	}

	private void assertAccessLogArgProviderInitState() {
		assertThat(accessLogArgProvider.zonedDateTime()).isNull();
		assertThat(accessLogArgProvider.method()).isNull();
		assertThat(accessLogArgProvider.uri()).isNull();
		assertThat(accessLogArgProvider.protocol()).isNull();
		assertThat(accessLogArgProvider.status()).isNull();
		assertThat(accessLogArgProvider.chunked).isFalse();
		assertThat(accessLogArgProvider.contentLength).isEqualTo(-1);
		assertThat(accessLogArgProvider.startTime).isZero();
	}

	static class TestAccessLogArgProvider extends AbstractAccessLogArgProvider<TestAccessLogArgProvider> {

		TestAccessLogArgProvider(SocketAddress remoteAddress) {
			super(remoteAddress);
		}

		@Override
		public CharSequence requestHeader(CharSequence name) {
			return "requestHeader";
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
