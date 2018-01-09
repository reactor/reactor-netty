/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
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
	public void httpCodecSizesModified() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(123, 456, 789);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(123);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(456);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(789);
	}

	@Test
	public void httpCodecSizesDefaults() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(4096);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(8192);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(8192);
	}

	@Test
	public void httpCodecSizesLineNegativeDefaults() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(-1, 456, 789);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(4096);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(456);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(789);
	}

	@Test
	public void httpCodecSizesLineZeroDefaults() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(0, 456, 789);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(4096);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(456);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(789);
	}

	@Test
	public void httpCodecSizesLineNegativeIgnored() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(123, 456, 789)
		       .httpCodecOptions(-1, 1, 2);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(123);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(1);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(2);
	}

	@Test
	public void httpCodecSizesLineZeroIgnored() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(123, 456, 789)
		       .httpCodecOptions(0, 1, 2);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(123);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(1);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(2);
	}

	@Test
	public void httpCodecSizesHeaderNegativeDefaults() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(123, -1, 789);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(123);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(8192);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(789);
	}

	@Test
	public void httpCodecSizesHeaderZeroDefaults() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(123, 0, 789);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(123);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(8192);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(789);
	}

	@Test
	public void httpCodecSizesHeaderNegativeIgnored() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(123, 456, 789)
		       .httpCodecOptions(1, -1, 2);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(1);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(456);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(2);
	}

	@Test
	public void httpCodecSizesHeaderZeroIgnored() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(123, 456, 789)
		       .httpCodecOptions(1, 0, 2);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(1);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(456);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(2);
	}

	@Test
	public void httpCodecSizesChunkNegativeDefaults() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(123, 456, -1);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(123);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(456);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(8192);
	}

	@Test
	public void httpCodecSizesChunkZeroDefaults() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(123, 456, 0);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(123);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(456);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(8192);
	}

	@Test
	public void httpCodecSizesChunkNegativeIgnored() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(123, 456, 789)
		       .httpCodecOptions(1, 2, -1);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(1);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(2);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(789);
	}

	@Test
	public void httpCodecSizesChunkZeroIgnored() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.httpCodecOptions(123, 456, 789)
		       .httpCodecOptions(1, 2, 0);

		assertThat(builder.build().httpCodecMaxInitialLineLength()).isEqualTo(1);
		assertThat(builder.build().httpCodecMaxHeaderSize()).isEqualTo(2);
		assertThat(builder.build().httpCodecMaxChunkSize()).isEqualTo(789);
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
		                            .httpCodecOptions(123, 0, -1)
		                            .build().asDetailedString())
				.endsWith(", httpCodecSizes={initialLine=123,header=8192,chunk=8192}");

		//changed header size
		assertThat(HttpServerOptions.builder()
		                            .httpCodecOptions(0, 123, -1)
		                            .build().asDetailedString())
				.endsWith(", httpCodecSizes={initialLine=4096,header=123,chunk=8192}");

		//changed chunk size
		assertThat(HttpServerOptions.builder()
		                            .httpCodecOptions(0, -1, 123)
		                            .build().asDetailedString())
				.endsWith(", httpCodecSizes={initialLine=4096,header=8192,chunk=123}");

		//changed all sizes
		assertThat(HttpServerOptions.builder()
		                            .httpCodecOptions(123, 456, 789)
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