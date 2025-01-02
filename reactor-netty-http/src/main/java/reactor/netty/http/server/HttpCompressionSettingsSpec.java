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
package reactor.netty.http.server;

import java.util.ArrayList;
import java.util.List;

import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.BrotliOptions;
import io.netty.handler.codec.compression.CompressionOptions;
import io.netty.handler.codec.compression.DeflateOptions;
import io.netty.handler.codec.compression.GzipOptions;
import io.netty.handler.codec.compression.SnappyOptions;
import io.netty.handler.codec.compression.StandardCompressionOptions;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.compression.ZstdOptions;
import io.netty.util.internal.ObjectUtil;

/**
 * HTTP Compression configuration builder for the {@link SimpleCompressionHandler}.
 *
 * @author raccoonback
 */
public final class HttpCompressionSettingsSpec {

	private final GzipOptions gzipOptions;
	private final DeflateOptions deflateOptions;
	private final SnappyOptions snappyOptions;
	private BrotliOptions brotliOptions;
	private ZstdOptions zstdOptions;

	private HttpCompressionSettingsSpec() {
		gzipOptions = StandardCompressionOptions.gzip();
		deflateOptions = StandardCompressionOptions.deflate();
		snappyOptions = StandardCompressionOptions.snappy();

		if (Brotli.isAvailable()) {
			brotliOptions = StandardCompressionOptions.brotli();
		}

		if (Zstd.isAvailable()) {
			zstdOptions = StandardCompressionOptions.zstd();
		}
	}

	private HttpCompressionSettingsSpec(Build build) {
		gzipOptions = build.gzipOptions;
		deflateOptions = build.gzipOptions;
		snappyOptions = StandardCompressionOptions.snappy();

		if (Brotli.isAvailable()) {
			brotliOptions = StandardCompressionOptions.brotli();
		}

		if (Zstd.isAvailable() && build.zstdOptions != null) {
			zstdOptions = build.zstdOptions;
		}
	}

	/**
	 * Creates a builder for {@link HttpCompressionSettingsSpec}.
	 *
	 * @return a new {@link HttpCompressionSettingsSpec.Builder}
	 */
	public static Builder builder() {
		return new Build();
	}

	static HttpCompressionSettingsSpec provideDefault() {
		return new HttpCompressionSettingsSpec();
	}

	CompressionOptions[] adaptToOptions() {
		List<CompressionOptions> options = new ArrayList<>();
		options.add(this.gzipOptions);
		options.add(this.deflateOptions);
		options.add(this.snappyOptions);

		if (brotliOptions != null) {
			options.add(this.brotliOptions);
		}

		if (zstdOptions != null) {
			options.add(this.zstdOptions);
		}

		return options.toArray(new CompressionOptions[0]);
	}

	public interface Builder {

		/**
		 * Build a new {@link HttpCompressionSettingsSpec}.
		 *
		 * @return a new {@link HttpCompressionSettingsSpec}
		 */
		HttpCompressionSettingsSpec build();

		/**
		 * Sets the gzip compression level.
		 *
		 * @return a new {@link HttpCompressionSettingsSpec.Builder}
		 */
		Builder gzip(int compressionLevel);

		/**
		 * Sets the deflate compression level.
		 *
		 * @return a new {@link HttpCompressionSettingsSpec.Builder}
		 */
		Builder deflate(int compressionLevel);

		/**
		 * Sets the zstd compression level.
		 *
		 * @return a new {@link HttpCompressionSettingsSpec.Builder}
		 */
		Builder zstd(int compressionLevel);
	}

	private static final class Build implements Builder {

		GzipOptions gzipOptions = StandardCompressionOptions.gzip();
		DeflateOptions deflateOptions = StandardCompressionOptions.deflate();
		ZstdOptions zstdOptions;

		private static final int DEFLATE_DEFAULT_WINDOW_BITS = 15;
		private static final int DEFLATE_DEFAULT_MEMORY_LEVEL = 8;
		private static final int ZSTD_DEFAULT_COMPRESSION_LEVEL = 3;
		private static final int ZSTD_DEFAULT_BLOCK_SIZE = 65536;
		private static final int ZSTD_MAX_BLOCK_SIZE = 1 << ZSTD_DEFAULT_COMPRESSION_LEVEL + 7 + 15;

		@Override
		public HttpCompressionSettingsSpec build() {
			return new HttpCompressionSettingsSpec(this);
		}

		@Override
		public Builder gzip(int compressionLevel) {
			ObjectUtil.checkInRange(compressionLevel, 0, 9, "compressionLevel");

			gzipOptions = StandardCompressionOptions.gzip(compressionLevel, DEFLATE_DEFAULT_WINDOW_BITS, DEFLATE_DEFAULT_MEMORY_LEVEL);
			return this;
		}

		@Override
		public Builder deflate(int compressionLevel) {
			ObjectUtil.checkInRange(compressionLevel, 0, 9, "compressionLevel");

			this.deflateOptions = StandardCompressionOptions.deflate(compressionLevel, DEFLATE_DEFAULT_WINDOW_BITS, DEFLATE_DEFAULT_MEMORY_LEVEL);
			return this;
		}

		@Override
		public Builder zstd(int compressionLevel) {
			if (!Zstd.isAvailable()) {
				throw new IllegalStateException("Unable to set compression level on zstd.");
			}
			ObjectUtil.checkInRange(compressionLevel, -7, 22, "compressionLevel");

			this.zstdOptions = StandardCompressionOptions.zstd(compressionLevel, ZSTD_DEFAULT_BLOCK_SIZE, ZSTD_MAX_BLOCK_SIZE);
			return this;
		}
	}
}
