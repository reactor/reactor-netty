/*
 * Copyright (c) 2019-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public class HttpDecoderSpecTest {

	private HttpDecoderSpecImpl conf;

	@BeforeEach
	void init() {
		conf = new HttpDecoderSpecImpl();
	}

	@Test
	void maxInitialLineLength() {
		checkDefaultMaxInitialLineLength(conf);

		conf.maxInitialLineLength(123);

		assertThat(conf.maxInitialLineLength()).as("initial line length").isEqualTo(123);

		checkDefaultMaxHeaderSize(conf);
		checkDefaultMaxChunkSize(conf);
		checkDefaultValidateHeaders(conf);
		checkDefaultInitialBufferSize(conf);
		checkDefaultAllowDuplicateContentLengths(conf);
	}

	@Test
	void maxInitialLineLengthBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> conf.maxInitialLineLength(0))
				.as("rejects 0")
				.withMessage("maxInitialLineLength must be strictly positive");

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> conf.maxInitialLineLength(-1))
				.as("rejects negative")
				.withMessage("maxInitialLineLength must be strictly positive");
	}

	@Test
	void maxHeaderSize() {
		checkDefaultMaxHeaderSize(conf);

		conf.maxHeaderSize(123);

		assertThat(conf.maxHeaderSize()).as("header size").isEqualTo(123);

		checkDefaultMaxInitialLineLength(conf);
		checkDefaultMaxChunkSize(conf);
		checkDefaultValidateHeaders(conf);
		checkDefaultInitialBufferSize(conf);
		checkDefaultAllowDuplicateContentLengths(conf);
	}

	@Test
	void maxHeaderSizeBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> conf.maxHeaderSize(0))
				.as("rejects 0")
				.withMessage("maxHeaderSize must be strictly positive");

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> conf.maxHeaderSize(-1))
				.as("rejects negative")
				.withMessage("maxHeaderSize must be strictly positive");
	}

	@Test
	@SuppressWarnings("deprecation")
	void maxChunkSize() {
		checkDefaultMaxChunkSize(conf);

		conf.maxChunkSize(123);

		assertThat(conf.maxChunkSize()).as("chunk size").isEqualTo(123);

		checkDefaultMaxInitialLineLength(conf);
		checkDefaultMaxHeaderSize(conf);
		checkDefaultValidateHeaders(conf);
		checkDefaultInitialBufferSize(conf);
		checkDefaultAllowDuplicateContentLengths(conf);
	}

	@Test
	@SuppressWarnings("deprecation")
	void maxChunkSizeBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> conf.maxChunkSize(0))
				.as("rejects 0")
				.withMessage("maxChunkSize must be strictly positive");

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> conf.maxChunkSize(-1))
				.as("rejects negative")
				.withMessage("maxChunkSize must be strictly positive");
	}

	@Test
	void validateHeaders() {
		checkDefaultValidateHeaders(conf);

		conf.validateHeaders(false);

		assertThat(conf.validateHeaders()).as("validate headers").isFalse();

		checkDefaultMaxInitialLineLength(conf);
		checkDefaultMaxHeaderSize(conf);
		checkDefaultMaxChunkSize(conf);
		checkDefaultInitialBufferSize(conf);
		checkDefaultAllowDuplicateContentLengths(conf);
	}

	@Test
	void initialBufferSize() {
		checkDefaultInitialBufferSize(conf);

		conf.initialBufferSize(123);

		assertThat(conf.initialBufferSize()).as("initial buffer size").isEqualTo(123);

		checkDefaultMaxInitialLineLength(conf);
		checkDefaultMaxHeaderSize(conf);
		checkDefaultMaxChunkSize(conf);
		checkDefaultValidateHeaders(conf);
		checkDefaultAllowDuplicateContentLengths(conf);
	}

	@Test
	void initialBufferSizeBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> conf.initialBufferSize(0))
				.as("rejects 0")
				.withMessage("initialBufferSize must be strictly positive");

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> conf.initialBufferSize(-1))
				.as("rejects negative")
				.withMessage("initialBufferSize must be strictly positive");
	}

	@Test
	void allowDuplicateContentLengths() {
		checkDefaultAllowDuplicateContentLengths(conf);

		conf.allowDuplicateContentLengths(true);

		assertThat(conf.allowDuplicateContentLengths()).as("allow duplicate Content-Length headers").isTrue();

		checkDefaultMaxInitialLineLength(conf);
		checkDefaultMaxHeaderSize(conf);
		checkDefaultMaxChunkSize(conf);
		checkDefaultValidateHeaders(conf);
		checkDefaultInitialBufferSize(conf);
	}

	public static void checkDefaultMaxInitialLineLength(HttpDecoderSpec<?> conf) {
		assertThat(conf.maxInitialLineLength()).as("default initial line length")
				.isEqualTo(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH)
				.isEqualTo(4096);
	}

	public static void checkDefaultMaxHeaderSize(HttpDecoderSpec<?> conf) {
		assertThat(conf.maxHeaderSize()).as("default header size")
				.isEqualTo(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE)
				.isEqualTo(8192);
	}

	@SuppressWarnings("deprecation")
	public static void checkDefaultMaxChunkSize(HttpDecoderSpec<?> conf) {
		assertThat(conf.maxChunkSize()).as("default chunk size")
				.isEqualTo(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE)
				.isEqualTo(8192);
	}

	public static void checkDefaultValidateHeaders(HttpDecoderSpec<?> conf) {
		assertThat(conf.validateHeaders()).as("default validate headers")
				.isEqualTo(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS)
				.isTrue();
	}

	public static void checkDefaultInitialBufferSize(HttpDecoderSpec<?> conf) {
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes")
				.isEqualTo(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE)
				.isEqualTo(128);
	}

	public static void checkDefaultAllowDuplicateContentLengths(HttpDecoderSpec<?> conf) {
		assertThat(conf.allowDuplicateContentLengths()).as("default allow duplicate Content-Length headers")
				.isEqualTo(HttpDecoderSpec.DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS)
				.isFalse();
	}

	private static final class HttpDecoderSpecImpl extends HttpDecoderSpec<HttpDecoderSpecImpl> {

		@Override
		public HttpDecoderSpecImpl get() {
			return this;
		}
	}
}