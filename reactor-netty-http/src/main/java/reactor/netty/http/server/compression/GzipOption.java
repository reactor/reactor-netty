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
package reactor.netty.http.server.compression;

import io.netty.handler.codec.compression.CompressionOptions;
import io.netty.handler.codec.compression.StandardCompressionOptions;
import io.netty.util.internal.ObjectUtil;

/**
 * GZIP compression option configuration.
 *
 * @author raccoonback
 * @since 1.2.3
 */
public final class GzipOption implements HttpCompressionOption {

	private final int compressionLevel;
	private final int memoryLevel;
	private final int windowBits;

	private GzipOption(Build build) {
		this.compressionLevel = build.compressionLevel;
		this.memoryLevel = build.memoryLevel;
		this.windowBits = build.windowBits;
	}

	static GzipOption provideDefault() {
		return builder().build();
	}

	CompressionOptions adapt() {
		return StandardCompressionOptions.gzip(compressionLevel, windowBits, memoryLevel);
	}

	/**
	 * Creates a builder for {@link GzipOption}.
	 *
	 * @return a new {@link GzipOption.Builder}
	 */
	public static Builder builder() {
		return new Build();
	}

	public interface Builder {

		/**
		 * Build a new {@link GzipOption}.
		 *
		 * @return a new {@link GzipOption}
		 */
		GzipOption build();

		/**
		 * Sets the gzip compression level.
		 *
		 * @return a new {@link GzipOption.Builder}
		 */
		Builder compressionLevel(int compressionLevel);

		/**
		 * Sets the gzip memory level.
		 *
		 * @return a new {@link GzipOption.Builder}
		 */
		Builder memoryLevel(int memoryLevel);

		/**
		 * Sets the gzip window bits.
		 *
		 * @return a new {@link GzipOption.Builder}
		 */
		Builder windowBits(int windowBits);
	}

	private static final class Build implements Builder {
		static final io.netty.handler.codec.compression.GzipOptions DEFAULT = StandardCompressionOptions.gzip();

		private int compressionLevel = DEFAULT.compressionLevel();
		private int memoryLevel = DEFAULT.memLevel();
		private int windowBits = DEFAULT.windowBits();

		@Override
		public GzipOption build() {
			return new GzipOption(this);
		}

		@Override
		public Builder compressionLevel(int compressionLevel) {
			ObjectUtil.checkInRange(compressionLevel, 0, 9, "compressionLevel");
			this.compressionLevel = compressionLevel;
			return this;
		}

		@Override
		public Builder memoryLevel(int memoryLevel) {
			ObjectUtil.checkInRange(memoryLevel, 1, 9, "memoryLevel");
			this.memoryLevel = memoryLevel;
			return this;
		}

		@Override
		public Builder windowBits(int windowBits) {
			ObjectUtil.checkInRange(windowBits, 9, 15, "windowBits");
			this.windowBits = windowBits;
			return this;
		}
	}
}
