/*
 * Copyright (c) 2024-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server.compression;

import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.CompressionOptions;
import io.netty.handler.codec.compression.Zstd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HTTP Compression configuration for the SimpleCompressionHandler.
 *
 * @author raccoonback
 */
public final class HttpCompressionOptionsSpec {

	private GzipOption gzip;
	private DeflateOption deflate;
	private SnappyOption snappy;
	private BrotliOption brotli;
	private ZstdOption zstd;

	private HttpCompressionOptionsSpec() {
		gzip = StandardHttpCompressionOptions.gzip();
		deflate = StandardHttpCompressionOptions.deflate();
		snappy = StandardHttpCompressionOptions.snappy();

		if (Brotli.isAvailable()) {
			brotli = StandardHttpCompressionOptions.brotli();
		}

		if (Zstd.isAvailable()) {
			zstd = StandardHttpCompressionOptions.zstd();
		}
	}

	public HttpCompressionOptionsSpec(HttpCompressionOption... compressionOptions) {
		this();
		Arrays.stream(compressionOptions)
				.forEach(this::initializeOption);
	}

	private void initializeOption(HttpCompressionOption option) {
		if (option instanceof GzipOption) {
			this.gzip = (GzipOption) option;
		}
		else if (option instanceof DeflateOption) {
			this.deflate = (DeflateOption) option;
		}
		else if (option instanceof SnappyOption) {
			this.snappy = (SnappyOption) option;
		}
		else if (Brotli.isAvailable() && option instanceof BrotliOption) {
			this.brotli = (BrotliOption) option;
		}
		else if (Zstd.isAvailable() && option instanceof ZstdOption) {
			this.zstd = (ZstdOption) option;
		}
	}

	public static HttpCompressionOptionsSpec provideDefault() {
		return new HttpCompressionOptionsSpec();
	}

	public CompressionOptions[] adapt() {
		List<CompressionOptions> options = new ArrayList<>(
				Arrays.asList(
						gzip.adapt(),
						deflate.adapt(),
						snappy.adapt()
				)
		);

		if (brotli != null) {
			options.add(brotli.adapt());
		}

		if (zstd != null) {
			options.add(zstd.adapt());
		}

		return options.toArray(new CompressionOptions[0]);
	}
}