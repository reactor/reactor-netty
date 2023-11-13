/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
 * This test class verifies {@link HttpServer} request decoding.
 *
 * @author Violeta Georgieva
 */
class HttpRequestDecoderSpecTests {
	private HttpRequestDecoderSpec conf;

	@BeforeEach
	void setUp() {
		conf = new HttpRequestDecoderSpec();
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
	}

	private static void checkDefaultH2cMaxContentLength(HttpRequestDecoderSpec conf) {
		assertThat(conf.h2cMaxContentLength()).as("default H2C max content length")
				.isEqualTo(HttpRequestDecoderSpec.DEFAULT_H2C_MAX_CONTENT_LENGTH)
				.isEqualTo(0);
	}
}
