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
package reactor.netty.transport.logging;

import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * Used to control the format and verbosity of logging for {@link ByteBuf}s and {@link ByteBufHolder}s.
 *
 * Adds {@link AdvancedByteBufFormat#TEXTUAL} format to io.netty.handler.logging.ByteBufFormat.
 *
 * @author Maximilian Goeke
 * @since 1.0.0
 */
public enum AdvancedByteBufFormat {
	/**
	 * When wire logging is enabled with this format, only the events will be logged.
	 * <p>Examples:</p>
	 * <pre>
	 * {@code
	 * reactor.netty.http.HttpTests - [id: 0x230d3686, L:/0:0:0:0:0:0:0:1:60241 - R:/0:0:0:0:0:0:0:1:60245] REGISTERED
	 * reactor.netty.http.HttpTests - [id: 0x230d3686, L:/0:0:0:0:0:0:0:1:60241 - R:/0:0:0:0:0:0:0:1:60245] ACTIVE
	 * reactor.netty.http.HttpTests - [id: 0x230d3686, L:/0:0:0:0:0:0:0:1:60241 - R:/0:0:0:0:0:0:0:1:60245] READ: 145B
	 * reactor.netty.http.HttpTests - [id: 0x230d3686, L:/0:0:0:0:0:0:0:1:60241 - R:/0:0:0:0:0:0:0:1:60245] WRITE: 38B
	 * }
	 * </pre>
	 */
	SIMPLE,
	/**
	 * When wire logging is enabled with this format, both events and content will be logged.
	 * The content will be in hex format.
	 * <p>Examples:</p>
	 * <pre>
	 * {@code
	 * reactor.netty.http.HttpTests - [id: 0xd5230a14, L:/0:0:0:0:0:0:0:1:60267 - R:/0:0:0:0:0:0:0:1:60269] REGISTERED
	 * reactor.netty.http.HttpTests - [id: 0xd5230a14, L:/0:0:0:0:0:0:0:1:60267 - R:/0:0:0:0:0:0:0:1:60269] ACTIVE
	 * reactor.netty.http.HttpTests - [id: 0xd5230a14, L:/0:0:0:0:0:0:0:1:60267 - R:/0:0:0:0:0:0:0:1:60269] READ: 145B
	 *          +-------------------------------------------------+
	 *          |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |
	 * +--------+-------------------------------------------------+----------------+
	 * |00000000| 50 4f 53 54 20 2f 74 65 73 74 2f 57 6f 72 6c 64 |POST /test/World|
	 * |00000010| 20 48 54 54 50 2f 31 2e 31 0d 0a 43 6f 6e 74 65 | HTTP/1.1..Conte|
	 * |00000020| 6e 74 2d 54 79 70 65 3a 20 74 65 78 74 2f 70 6c |nt-Type: text/pl|
	 * |00000030| 61 69 6e 0d 0a 75 73 65 72 2d 61 67 65 6e 74 3a |ain..user-agent:|
	 * |00000040| 20 52 65 61 63 74 6f 72 4e 65 74 74 79 2f 64 65 | ReactorNetty/de|
	 * ...
	 * reactor.netty.http.HttpTests - [id: 0xd5230a14, L:/0:0:0:0:0:0:0:1:60267 - R:/0:0:0:0:0:0:0:1:60269] WRITE: 38B
	 *          +-------------------------------------------------+
	 *          |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |
	 * +--------+-------------------------------------------------+----------------+
	 * |00000000| 48 54 54 50 2f 31 2e 31 20 32 30 30 20 4f 4b 0d |HTTP/1.1 200 OK.|
	 * |00000010| 0a 63 6f 6e 74 65 6e 74 2d 6c 65 6e 67 74 68 3a |.content-length:|
	 * |00000020| 20 30 0d 0a 0d 0a                               | 0....          |
	 * +--------+-------------------------------------------------+----------------+
	 * }
	 * </pre>
	 */
	HEX_DUMP,
	/**
	 * When wire logging is enabled with this format, both events and content will be logged.
	 * The content will be in plain text format.
	 * <p>Examples:</p>
	 * <pre>
	 * {@code
	 * reactor.netty.http.HttpTests - [id: 0x02c3db6c, L:/0:0:0:0:0:0:0:1:60317 - R:/0:0:0:0:0:0:0:1:60319] REGISTERED
	 * reactor.netty.http.HttpTests - [id: 0x02c3db6c, L:/0:0:0:0:0:0:0:1:60317 - R:/0:0:0:0:0:0:0:1:60319] ACTIVE
	 * reactor.netty.http.HttpTests - [id: 0x02c3db6c, L:/0:0:0:0:0:0:0:1:60317 - R:/0:0:0:0:0:0:0:1:60319] READ: 145B POST /test/World HTTP/1.1
	 * Content-Type: text/plain
	 * user-agent: ReactorNetty/dev
	 * ...
	 * reactor.netty.http.HttpTests - [id: 0x02c3db6c, L:/0:0:0:0:0:0:0:1:60317 - R:/0:0:0:0:0:0:0:1:60319] WRITE: 38B HTTP/1.1 200 OK
	 * content-length: 0
	 * }
	 * </pre>
	 */
	TEXTUAL;

	/**
	 * Creates the matching LoggingHandler regarding the format.
	 *
	 * @param category the logger category
	 * @param level    the logger level
	 * @param charset  the charset (only relevant for {@link AdvancedByteBufFormat#TEXTUAL})
	 * @return a new {@link LoggingHandler} reference
	 */
	public LoggingHandler toLoggingHandler(String category, LogLevel level, Charset charset) {
		switch (this) {
		case SIMPLE:
			return new LoggingHandler(category, level, io.netty.handler.logging.ByteBufFormat.SIMPLE);
		case HEX_DUMP:
			return new LoggingHandler(category, level, io.netty.handler.logging.ByteBufFormat.HEX_DUMP);
		default:
			return new ReactorNettyLoggingHandler(category, level, charset);
		}
	}
}
