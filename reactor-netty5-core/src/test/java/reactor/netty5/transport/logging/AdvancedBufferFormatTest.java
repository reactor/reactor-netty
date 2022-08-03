/*
 * Copyright (c) 2020-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.transport.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static reactor.netty5.transport.logging.AdvancedBufferFormat.HEX_DUMP;
import static reactor.netty5.transport.logging.AdvancedBufferFormat.SIMPLE;
import static reactor.netty5.transport.logging.AdvancedBufferFormat.TEXTUAL;

import java.nio.charset.Charset;

import org.junit.jupiter.api.Test;

import io.netty5.handler.logging.LogLevel;
import io.netty5.handler.logging.LoggingHandler;

class AdvancedBufferFormatTest {

	@Test
	void createSimpleLoggingHandler() {
		final LoggingHandler loggingHandler =
			SIMPLE.toLoggingHandler(
				AdvancedBufferFormatTest.class.toString(),
				LogLevel.DEBUG,
				Charset.defaultCharset());

		assertThat(loggingHandler).isInstanceOf(LoggingHandler.class);
		assertThat(loggingHandler.bufferFormat()).isSameAs(io.netty5.handler.logging.BufferFormat.SIMPLE);
	}

	@Test
	void createHexDumpLoggingHandler() {
		final LoggingHandler loggingHandler =
			HEX_DUMP.toLoggingHandler(
				AdvancedBufferFormatTest.class.toString(),
				LogLevel.DEBUG,
				Charset.defaultCharset());

		assertThat(loggingHandler).isInstanceOf(LoggingHandler.class);
		assertThat(loggingHandler.bufferFormat()).isSameAs(io.netty5.handler.logging.BufferFormat.HEX_DUMP);
	}

	@Test
	void createTextualLoggingHandler() {
		final LoggingHandler loggingHandler =
			TEXTUAL.toLoggingHandler(
				AdvancedBufferFormatTest.class.toString(),
				LogLevel.DEBUG,
				Charset.defaultCharset());

		assertThat(loggingHandler).isInstanceOf(ReactorNettyLoggingHandler.class);
	}

	@Test
	void simpleToLoggingHandlerBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SIMPLE.toLoggingHandler(null, LogLevel.DEBUG, Charset.defaultCharset()))
				.withMessage("name");

		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SIMPLE.toLoggingHandler("name", null, Charset.defaultCharset()))
				.withMessage("level");
	}

	@Test
	void hexdumpToLoggingHandlerBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> HEX_DUMP.toLoggingHandler(null, LogLevel.DEBUG, Charset.defaultCharset()))
				.withMessage("name");

		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> HEX_DUMP.toLoggingHandler("name", null, Charset.defaultCharset()))
				.withMessage("level");
	}

	@Test
	void textualToLoggingHandlerBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> TEXTUAL.toLoggingHandler(null, LogLevel.DEBUG, Charset.defaultCharset()))
				.withMessage("name");

		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> TEXTUAL.toLoggingHandler("name", null, Charset.defaultCharset()))
				.withMessage("level");

		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> TEXTUAL.toLoggingHandler("name", LogLevel.DEBUG, null))
				.withMessage("charset");
	}
}