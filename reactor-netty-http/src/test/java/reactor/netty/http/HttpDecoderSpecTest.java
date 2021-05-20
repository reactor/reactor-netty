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
package reactor.netty.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class HttpDecoderSpecTest {

	private HttpDecoderSpecImpl conf;

	@BeforeEach
	void init() {
		conf = new HttpDecoderSpecImpl();
	}

	@Test
	void maxInitialLineLength() {
		assertThat(conf.maxInitialLineLength()).as("default initial line length").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);

		conf.maxInitialLineLength(123);

		assertThat(conf.maxInitialLineLength()).as("initial line length").isEqualTo(123);

		assertThat(conf.maxHeaderSize()).as("default header size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.maxChunkSize()).as("default chunk size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.validateHeaders()).as("default validate headers").isEqualTo(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS);
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes").isEqualTo(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);
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
		assertThat(conf.maxHeaderSize()).as("default header size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE);

		conf.maxHeaderSize(123);

		assertThat(conf.maxHeaderSize()).as("header size").isEqualTo(123);

		assertThat(conf.maxInitialLineLength()).as("default initial line length").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.maxChunkSize()).as("default chunk size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.validateHeaders()).as("default validate headers").isEqualTo(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS);
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes").isEqualTo(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);
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
	void maxChunkSize() {
		assertThat(conf.maxChunkSize()).as("default chunk size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);

		conf.maxChunkSize(123);

		assertThat(conf.maxChunkSize()).as("chunk size").isEqualTo(123);

		assertThat(conf.maxInitialLineLength()).as("default initial line length").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.maxHeaderSize()).as("default header size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.validateHeaders()).as("default validate headers").isEqualTo(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS);
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes").isEqualTo(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);
	}

	@Test
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
		assertThat(conf.validateHeaders()).as("default validate headers").isTrue();

		conf.validateHeaders(false);

		assertThat(conf.validateHeaders()).as("validate headers").isFalse();

		assertThat(conf.maxInitialLineLength()).as("default initial line length").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.maxHeaderSize()).as("default header size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.maxChunkSize()).as("default chunk size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes").isEqualTo(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);
	}

	@Test
	void initialBufferSize() {
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes").isEqualTo(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);

		conf.initialBufferSize(123);

		assertThat(conf.initialBufferSize()).as("initial buffer size").isEqualTo(123);

		assertThat(conf.maxInitialLineLength()).as("default initial line length").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.maxHeaderSize()).as("default header size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.maxChunkSize()).as("default chunk size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.validateHeaders()).as("default validate headers").isEqualTo(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS);
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
		assertThat(conf.allowDuplicateContentLengths()).as("default allow duplicate Content-Length headers").isFalse();

		conf.allowDuplicateContentLengths(true);

		assertThat(conf.allowDuplicateContentLengths()).as("allow duplicate Content-Length headers").isTrue();

		assertThat(conf.maxInitialLineLength()).as("default initial line length").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.maxHeaderSize()).as("default header size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.maxChunkSize()).as("default chunk size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes").isEqualTo(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);
	}

	private static final class HttpDecoderSpecImpl extends HttpDecoderSpec<HttpDecoderSpecImpl> {

		@Override
		public HttpDecoderSpecImpl get() {
			return this;
		}
	}
}