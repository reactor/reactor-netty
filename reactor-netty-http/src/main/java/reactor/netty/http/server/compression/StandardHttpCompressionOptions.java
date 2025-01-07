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

import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.Zstd;
import io.netty.util.internal.ObjectUtil;

/**
 * Standard Compression Options for {@link GzipOption}, {@link DeflateOption} and {@link ZstdOption}.
 *
 * @author raccoonback
 */
public final class StandardHttpCompressionOptions {

	private StandardHttpCompressionOptions() {
	}

	/**
	 * Default GZIP options.
	 * The default compression level is 6.
	 *
	 * @return a new {@link GzipOption}
	 */
	public static GzipOption gzip() {
		return new GzipOption();
	}

	/**
	 * Set GZIP options.
	 *
	 * @return a new {@link GzipOption}
	 * @throws IllegalStateException If invalid parameters.
	 */
	public static GzipOption gzip(int compressionLevel, int windowBits, int memoryLevel) {
		ObjectUtil.checkInRange(compressionLevel, 0, 9, "compressionLevel");
		ObjectUtil.checkInRange(windowBits, 9, 15, "windowBits");
		ObjectUtil.checkInRange(memoryLevel, 1, 9, "memLevel");

		return new GzipOption(compressionLevel, windowBits, memoryLevel);
	}

	/**
	 * Default Deflate options.
	 * The default compression level is 6.
	 *
	 * @return a new {@link DeflateOption}
	 */
	public static DeflateOption deflate() {
		return new DeflateOption();
	}

	/**
	 * Set Deflate options.
	 *
	 * @return a new {@link DeflateOption}
	 * @throws IllegalStateException If invalid parameters.
	 */
	public static DeflateOption deflate(int compressionLevel, int windowBits, int memoryLevel) {
		ObjectUtil.checkInRange(compressionLevel, 0, 9, "compressionLevel");
		ObjectUtil.checkInRange(windowBits, 9, 15, "windowBits");
		ObjectUtil.checkInRange(memoryLevel, 1, 9, "memLevel");

		return new DeflateOption(compressionLevel, windowBits, memoryLevel);
	}

	/**
	 * Default ZSTD options.
	 * The default compression level is 3.
	 *
	 * @return a new {@link ZstdOption}
	 */
	public static ZstdOption zstd() {
		return new ZstdOption();
	}

	/**
	 * Set ZSTD options.
	 *
	 * @return a new {@link ZstdOption}
	 * @throws IllegalStateException If invalid parameters.
	 */
	public static ZstdOption zstd(int compressionLevel, int blockSize, int maxEncodeSize) {
		ObjectUtil.checkInRange(compressionLevel, -(1 << 17), 22, "compressionLevel");
		ObjectUtil.checkPositive(blockSize, "blockSize");
		ObjectUtil.checkPositive(maxEncodeSize, "maxEncodeSize");

		if (!Zstd.isAvailable()) {
			throw new IllegalStateException("ZSTD is not available", Zstd.cause());
		}

		return new ZstdOption(compressionLevel, blockSize, maxEncodeSize);
	}

	/**
	 * Default Brotli options.
	 *
	 * @return a new {@link BrotliOption}
	 */
	public static BrotliOption brotli() {
		if (!Brotli.isAvailable()) {
			throw new IllegalStateException("Brotli is not available", Brotli.cause());
		}

		return new BrotliOption();
	}

	/**
	 * Default Snappy options.
	 *
	 * @return a new {@link SnappyOption}
	 */
	public static SnappyOption snappy() {
		return new SnappyOption();
	}
}
