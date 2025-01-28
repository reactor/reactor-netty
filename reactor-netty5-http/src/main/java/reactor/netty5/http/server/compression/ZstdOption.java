/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.server.compression;

import io.netty5.handler.codec.compression.CompressionOptions;
import io.netty5.handler.codec.compression.StandardCompressionOptions;
import io.netty5.handler.codec.compression.Zstd;
import io.netty5.util.internal.ObjectUtil;

/**
 * ZSTD compression option configuration.
 *
 * @author raccoonback
 * @since 1.2.3
 */
public final class ZstdOption implements HttpCompressionOption {

	private final int blockSize;
	private final int compressionLevel;
	private final int maxEncodeSize;

	private ZstdOption(Build build) {
		this.blockSize = build.blockSize;
		this.compressionLevel = build.compressionLevel;
		this.maxEncodeSize = build.maxEncodeSize;
	}

	static ZstdOption provideDefault() {
		return builder().build();
	}

	CompressionOptions adapt() {
		return StandardCompressionOptions.zstd(compressionLevel, blockSize, maxEncodeSize);
	}

	/**
	 * Creates a builder for {@link ZstdOption}.
	 *
	 * @return a new {@link ZstdOption.Builder}
	 */
	public static Builder builder() {
		if (!Zstd.isAvailable()) {
			throw new IllegalStateException("zstd is not available", Zstd.cause());
		}

		return new ZstdOption.Build();
	}

	public interface Builder {

		/**
		 * Build a new {@link ZstdOption}.
		 *
		 * @return a new {@link ZstdOption}
		 */
		ZstdOption build();

		/**
		 * Sets the zstd block size.
		 *
		 * @return a new {@link ZstdOption.Builder}
		 */
		Builder blockSize(int blockSize);

		/**
		 * Sets the zstd compression level.
		 *
		 * @return a new {@link ZstdOption.Builder}
		 */
		Builder compressionLevel(int compressionLevel);

		/**
		 * Sets the zstd memory level.
		 *
		 * @return a new {@link ZstdOption.Builder}
		 */
		Builder maxEncodeSize(int maxEncodeSize);
	}

	private static final class Build implements Builder {

		private int blockSize = 1 << 16; // 64KB
		private int compressionLevel = 3;
		private int maxEncodeSize = 1 << (compressionLevel + 7 + 0x0F); // 32MB

		@Override
		public ZstdOption build() {
			return new ZstdOption(this);
		}

		@Override
		public Builder blockSize(int blockSize) {
			ObjectUtil.checkPositive(blockSize, "blockSize");
			this.blockSize = blockSize;
			return this;
		}

		@Override
		public Builder compressionLevel(int compressionLevel) {
			ObjectUtil.checkInRange(compressionLevel, -(1 << 17), 22, "compressionLevel");
			this.compressionLevel = compressionLevel;
			return this;
		}

		@Override
		public Builder maxEncodeSize(int maxEncodeSize) {
			ObjectUtil.checkPositive(maxEncodeSize, "maxEncodeSize");
			this.maxEncodeSize = maxEncodeSize;
			return this;
		}
	}
}
