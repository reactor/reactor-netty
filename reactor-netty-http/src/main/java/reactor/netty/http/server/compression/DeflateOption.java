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

import io.netty.handler.codec.compression.CompressionOptions;
import io.netty.handler.codec.compression.StandardCompressionOptions;

/**
 * Deflate compression option configuration
 *
 * @author raccoonback
 */
final class DeflateOption implements HttpCompressionOption {

	private final CompressionOptions option;

	DeflateOption() {
		this.option = StandardCompressionOptions.gzip();
	}

	DeflateOption(int compressionLevel, int windowBits, int memoryLevel) {
		this.option = StandardCompressionOptions.gzip(
				compressionLevel,
				windowBits,
				memoryLevel
		);
	}

	CompressionOptions adapt() {
		return option;
	}
}
