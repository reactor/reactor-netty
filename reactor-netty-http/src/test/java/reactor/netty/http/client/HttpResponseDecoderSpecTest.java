/*
 * Copyright (c) 2019-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.HttpDecoderSpecTest.checkDefaultAllowDuplicateContentLengths;
import static reactor.netty.http.HttpDecoderSpecTest.checkDefaultInitialBufferSize;
import static reactor.netty.http.HttpDecoderSpecTest.checkDefaultMaxChunkSize;
import static reactor.netty.http.HttpDecoderSpecTest.checkDefaultMaxHeaderSize;
import static reactor.netty.http.HttpDecoderSpecTest.checkDefaultMaxInitialLineLength;
import static reactor.netty.http.HttpDecoderSpecTest.checkDefaultValidateHeaders;

/**
 * This test class verifies {@link HttpClient} response decoding.
 *
 * @author Violeta Georgieva
 */
class HttpResponseDecoderSpecTest {

	private HttpResponseDecoderSpec conf;

	@BeforeEach
	void setUp() {
		conf = new HttpResponseDecoderSpec();
	}

	@Test
	void failOnMissingResponse() {
		checkDefaultFailOnMissingResponse(conf);

		conf.failOnMissingResponse(true);

		assertThat(conf.failOnMissingResponse).as("fail on missing response").isTrue();

		checkDefaultMaxInitialLineLength(conf);
		checkDefaultMaxHeaderSize(conf);
		checkDefaultMaxChunkSize(conf);
		checkDefaultValidateHeaders(conf);
		checkDefaultInitialBufferSize(conf);
		checkDefaultAllowDuplicateContentLengths(conf);
		checkDefaultParseHttpAfterConnectRequest(conf);
		checkDefaultH2cMaxContentLength(conf);
	}

	@Test
	void h2cMaxContentLength() {
		checkDefaultH2cMaxContentLength(conf);

		conf.h2cMaxContentLength(256);

		assertThat(conf.h2cMaxContentLength()).as("H2C max content length").isEqualTo(256);

		checkDefaultMaxInitialLineLength(conf);
		checkDefaultMaxHeaderSize(conf);
		checkDefaultMaxChunkSize(conf);
		checkDefaultValidateHeaders(conf);
		checkDefaultInitialBufferSize(conf);
		checkDefaultAllowDuplicateContentLengths(conf);
		checkDefaultFailOnMissingResponse(conf);
		checkDefaultParseHttpAfterConnectRequest(conf);
	}

	@Test
	void parseHttpAfterConnectRequest() {
		checkDefaultParseHttpAfterConnectRequest(conf);

		conf.parseHttpAfterConnectRequest(true);

		assertThat(conf.parseHttpAfterConnectRequest).as("parse http after connect request").isTrue();

		checkDefaultMaxInitialLineLength(conf);
		checkDefaultMaxHeaderSize(conf);
		checkDefaultMaxChunkSize(conf);
		checkDefaultValidateHeaders(conf);
		checkDefaultInitialBufferSize(conf);
		checkDefaultAllowDuplicateContentLengths(conf);
		checkDefaultFailOnMissingResponse(conf);
		checkDefaultH2cMaxContentLength(conf);
	}

	private static void checkDefaultFailOnMissingResponse(HttpResponseDecoderSpec conf) {
		assertThat(conf.failOnMissingResponse).as("default fail on missing response")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_FAIL_ON_MISSING_RESPONSE)
				.isFalse();
	}

	private static void checkDefaultH2cMaxContentLength(HttpResponseDecoderSpec conf) {
		assertThat(conf.h2cMaxContentLength()).as("default H2C max content length")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_H2C_MAX_CONTENT_LENGTH)
				.isEqualTo(65536);
	}

	private static void checkDefaultParseHttpAfterConnectRequest(HttpResponseDecoderSpec conf) {
		assertThat(conf.parseHttpAfterConnectRequest).as("default parse http after connect request")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_PARSE_HTTP_AFTER_CONNECT_REQUEST)
				.isFalse();
	}
}
