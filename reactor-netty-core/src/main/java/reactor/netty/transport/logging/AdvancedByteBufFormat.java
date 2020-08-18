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
	SIMPLE,
	HEX_DUMP,
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
