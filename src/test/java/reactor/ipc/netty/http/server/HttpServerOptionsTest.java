/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.server;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static reactor.ipc.netty.http.server.HttpServerOptions.Builder.*;

public class HttpServerOptionsTest {

	@Test
	public void minResponseForCompressionNegative() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.compression(-1))
				.withMessage("minResponseSize must be positive");
	}

	@Test
	public void minResponseForCompressionZero() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.compression(0);

		assertThat(builder.build().minCompressionResponseSize()).isZero();
	}

	@Test
	public void minResponseForCompressionPositive() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.compression(10);

		assertThat(builder.build().minCompressionResponseSize()).isEqualTo(10);
	}

	@Test
	public void maxInitialLineLength() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.maxInitialLineLength(123);

		HttpServerOptions conf = builder.build();

		assertThat(conf.httpCodecMaxInitialLineLength()).as("initial line length").isEqualTo(123);

		assertThat(conf.httpCodecMaxHeaderSize()).as("default header size").isEqualTo(DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.httpCodecMaxChunkSize()).as("default chunk size").isEqualTo(DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.httpCodecValidateHeaders()).as("default validate headers").isEqualTo(DEFAULT_VALIDATE_HEADERS);
		assertThat(conf.httpCodecInitialBufferSize()).as("default initial buffer sizez").isEqualTo(DEFAULT_INITIAL_BUFFER_SIZE);
	}

	@Test
	public void maxInitialLineLengthBadValues() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();


		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxInitialLineLength(0))
				.as("rejects 0")
				.withMessage("maxInitialLineLength must be strictly positive");

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxInitialLineLength(-1))
				.as("rejects negative")
				.withMessage("maxInitialLineLength must be strictly positive");
	}

	@Test
	public void maxHeaderSize() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.maxHeaderSize(123);

		HttpServerOptions conf = builder.build();

		assertThat(conf.httpCodecMaxHeaderSize()).as("header size").isEqualTo(123);

		assertThat(conf.httpCodecMaxInitialLineLength()).as("default initial line length").isEqualTo(DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.httpCodecMaxChunkSize()).as("default chunk size").isEqualTo(DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.httpCodecValidateHeaders()).as("default validate headers").isEqualTo(DEFAULT_VALIDATE_HEADERS);
		assertThat(conf.httpCodecInitialBufferSize()).as("default initial buffer sizez").isEqualTo(DEFAULT_INITIAL_BUFFER_SIZE);
	}

	@Test
	public void maxHeaderSizeBadValues() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxHeaderSize(0))
				.as("rejects 0")
				.withMessage("maxHeaderSize must be strictly positive");

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxHeaderSize(-1))
				.as("rejects negative")
				.withMessage("maxHeaderSize must be strictly positive");
	}

	@Test
	public void maxChunkSize() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.maxChunkSize(123);

		HttpServerOptions conf = builder.build();

		assertThat(conf.httpCodecMaxChunkSize()).as("chunk size").isEqualTo(123);

		assertThat(conf.httpCodecMaxInitialLineLength()).as("default initial line length").isEqualTo(DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.httpCodecMaxHeaderSize()).as("default header size").isEqualTo(DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.httpCodecValidateHeaders()).as("default validate headers").isEqualTo(DEFAULT_VALIDATE_HEADERS);
		assertThat(conf.httpCodecInitialBufferSize()).as("default initial buffer sizez").isEqualTo(DEFAULT_INITIAL_BUFFER_SIZE);
	}

	@Test
	public void maxChunkSizeBadValues() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxChunkSize(0))
				.as("rejects 0")
				.withMessage("maxChunkSize must be strictly positive");

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxChunkSize(-1))
				.as("rejects negative")
				.withMessage("maxChunkSize must be strictly positive");
	}

	@Test
	public void validateHeaders() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.validateHeaders(false);

		HttpServerOptions conf = builder.build();

		assertThat(conf.httpCodecValidateHeaders()).as("validate headers").isFalse();

		assertThat(conf.httpCodecMaxInitialLineLength()).as("default initial line length").isEqualTo(DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.httpCodecMaxHeaderSize()).as("default header size").isEqualTo(DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.httpCodecMaxChunkSize()).as("default chunk size").isEqualTo(DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.httpCodecInitialBufferSize()).as("default initial buffer sizez").isEqualTo(DEFAULT_INITIAL_BUFFER_SIZE);
	}

	@Test
	public void initialBufferSize() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.initialBufferSize(123);

		HttpServerOptions conf = builder.build();

		assertThat(conf.httpCodecInitialBufferSize()).as("initial buffer size").isEqualTo(123);

		assertThat(conf.httpCodecMaxInitialLineLength()).as("default initial line length").isEqualTo(DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.httpCodecMaxHeaderSize()).as("default header size").isEqualTo(DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.httpCodecMaxChunkSize()).as("default chunk size").isEqualTo(DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.httpCodecValidateHeaders()).as("default validate headers").isEqualTo(DEFAULT_VALIDATE_HEADERS);
	}

	@Test
	public void initialBufferSizeBadValues() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.initialBufferSize(0))
				.as("rejects 0")
				.withMessage("initialBufferSize must be strictly positive");

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.initialBufferSize(-1))
				.as("rejects negative")
				.withMessage("initialBufferSize must be strictly positive");
	}

	@Test
	public void asSimpleString() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();

		assertThat(builder.build().asSimpleString())
				.matches("^listening on (0\\.0\\.0\\.0/0\\.0\\.0\\.0:0|/0:0:0:0:0:0:0:1.*)$");

		//address
		builder.host("foo").port(123);
		assertThat(builder.build().asSimpleString()).isEqualTo("listening on foo:123");

		//gzip
		builder.compression(true);
		assertThat(builder.build().asSimpleString()).isEqualTo("listening on foo:123, gzip");

		//gzip with threshold
		builder.compression(534);
		assertThat(builder.build().asSimpleString()).isEqualTo("listening on foo:123, gzip over 534 bytes");
	}

	@Test
	public void asDetailedStringAddressAndCompression() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();

		assertThat(builder.build().asDetailedString())
				.matches("^address=(0\\.0\\.0\\.0/0\\.0\\.0\\.0:0|/0:0:0:0:0:0:0:1).*")
				.contains(", minCompressionResponseSize=-1");

		//address
		builder.host("foo").port(123);
		assertThat(builder.build().asDetailedString())
				.startsWith("address=foo:123")
				.contains(", minCompressionResponseSize=-1");

		//gzip
		builder.compression(true);
		assertThat(builder.build().asDetailedString())
				.startsWith("address=foo:123")
				.contains(", minCompressionResponseSize=0");

		//gzip with threshold
		builder.compression(534);
		assertThat(builder.build().asDetailedString())
				.startsWith("address=foo:123")
				.endsWith(", minCompressionResponseSize=534, httpCodecSizes={initialLine=4096,header=8192,chunk=8192}");
	}

	@Test
	public void asDetailedStringHttpCodecSizes() {
		//defaults
		assertThat(HttpServerOptions.builder()
		                            .build().asDetailedString())
				.endsWith(", httpCodecSizes={initialLine=4096,header=8192,chunk=8192}");

		//changed line length
		assertThat(HttpServerOptions.builder()
		                            .maxInitialLineLength(123)
		                            .build().asDetailedString())
				.endsWith(", httpCodecSizes={initialLine=123,header=8192,chunk=8192}");

		//changed header size
		assertThat(HttpServerOptions.builder()
		                            .maxHeaderSize(123)
		                            .build().asDetailedString())
				.endsWith(", httpCodecSizes={initialLine=4096,header=123,chunk=8192}");

		//changed chunk size
		assertThat(HttpServerOptions.builder()
		                            .maxChunkSize(123)
		                            .build().asDetailedString())
				.endsWith(", httpCodecSizes={initialLine=4096,header=8192,chunk=123}");

		//changed all sizes
		assertThat(HttpServerOptions.builder()
		                            .maxInitialLineLength(123)
		                            .maxHeaderSize(456)
		                            .maxChunkSize(789)
		                            .build().asDetailedString())
				.endsWith(", httpCodecSizes={initialLine=123,header=456,chunk=789}");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder()
		                                                     .compression(534)
		                                                     .host("google.com")
		                                                     .port(123);
		HttpServerOptions options = builder.build();
		assertThat(options.toString())
				.startsWith("HttpServerOptions{address=google.com")
				.contains(":123")
				.endsWith(", minCompressionResponseSize=534, httpCodecSizes={initialLine=4096,header=8192,chunk=8192}}");
	}

}