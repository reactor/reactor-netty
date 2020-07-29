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

import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public class HttpDecoderSpecTest {

	private HttpDecoderSpecImpl conf;

	@Before
	public void init() {
		conf = new HttpDecoderSpecImpl();
	}

	@Test
	public void maxInitialLineLength() {
		conf.maxInitialLineLength(123);

		assertThat(conf.maxInitialLineLength()).as("initial line length").isEqualTo(123);

		assertThat(conf.maxHeaderSize()).as("default header size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.maxChunkSize()).as("default chunk size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.validateHeaders()).as("default validate headers").isEqualTo(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS);
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes").isEqualTo(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);
	}

	@Test
	public void maxInitialLineLengthBadValues() {
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
	public void maxHeaderSize() {
		conf.maxHeaderSize(123);

		assertThat(conf.maxHeaderSize()).as("header size").isEqualTo(123);

		assertThat(conf.maxInitialLineLength()).as("default initial line length").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.maxChunkSize()).as("default chunk size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.validateHeaders()).as("default validate headers").isEqualTo(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS);
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes").isEqualTo(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);
	}

	@Test
	public void maxHeaderSizeBadValues() {
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
	public void maxChunkSize() {
		conf.maxChunkSize(123);

		assertThat(conf.maxChunkSize()).as("chunk size").isEqualTo(123);

		assertThat(conf.maxInitialLineLength()).as("default initial line length").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.maxHeaderSize()).as("default header size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.validateHeaders()).as("default validate headers").isEqualTo(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS);
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes").isEqualTo(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);
	}

	@Test
	public void maxChunkSizeBadValues() {
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
	public void validateHeaders() {
		conf.validateHeaders(false);

		assertThat(conf.validateHeaders()).as("validate headers").isFalse();

		assertThat(conf.maxInitialLineLength()).as("default initial line length").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.maxHeaderSize()).as("default header size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.maxChunkSize()).as("default chunk size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes").isEqualTo(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);
	}

	@Test
	public void initialBufferSize() {
		conf.initialBufferSize(123);

		assertThat(conf.initialBufferSize()).as("initial buffer size").isEqualTo(123);

		assertThat(conf.maxInitialLineLength()).as("default initial line length").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.maxHeaderSize()).as("default header size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.maxChunkSize()).as("default chunk size").isEqualTo(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.validateHeaders()).as("default validate headers").isEqualTo(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS);
	}

	@Test
	public void initialBufferSizeBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> conf.initialBufferSize(0))
				.as("rejects 0")
				.withMessage("initialBufferSize must be strictly positive");

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> conf.initialBufferSize(-1))
				.as("rejects negative")
				.withMessage("initialBufferSize must be strictly positive");
	}

	private static final class HttpDecoderSpecImpl extends HttpDecoderSpec<HttpDecoderSpecImpl> {

		@Override
		public HttpDecoderSpecImpl get() {
			return this;
		}
	}
}