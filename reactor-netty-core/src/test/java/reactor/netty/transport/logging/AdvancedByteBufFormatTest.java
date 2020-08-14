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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;

import org.junit.Test;

import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public class AdvancedByteBufFormatTest {

	@Test
	public void createSimpleLoggingHandler() {
		final LoggingHandler loggingHandler =
			AdvancedByteBufFormat.SIMPLE.toLoggingHandler(
				AdvancedByteBufFormatTest.class.toString(),
				LogLevel.DEBUG,
				Charset.defaultCharset());

		assertThat(loggingHandler).isInstanceOf(LoggingHandler.class);
		assertThat(loggingHandler.byteBufFormat()).isSameAs(io.netty.handler.logging.ByteBufFormat.SIMPLE);
	}

	@Test
	public void createHexDumpLoggingHandler() {
		final LoggingHandler loggingHandler =
			AdvancedByteBufFormat.HEX_DUMP.toLoggingHandler(
				AdvancedByteBufFormatTest.class.toString(),
				LogLevel.DEBUG,
				Charset.defaultCharset());

		assertThat(loggingHandler).isInstanceOf(LoggingHandler.class);
		assertThat(loggingHandler.byteBufFormat()).isSameAs(io.netty.handler.logging.ByteBufFormat.HEX_DUMP);
	}

	@Test
	public void createTextualLoggingHandler() {
		final LoggingHandler loggingHandler =
			AdvancedByteBufFormat.TEXTUAL.toLoggingHandler(
				AdvancedByteBufFormatTest.class.toString(),
				LogLevel.DEBUG,
				Charset.defaultCharset());

		assertThat(loggingHandler).isInstanceOf(ReactorNettyLoggingHandler.class);
	}

}