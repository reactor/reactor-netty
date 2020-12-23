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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.Charset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

class ReactorNettyLoggingHandlerTest {

	private static final Logger ROOT = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

	private LoggingHandler defaultCharsetReactorNettyLoggingHandler;

	private Appender<ILoggingEvent> mockedAppender;
	private ArgumentCaptor<LoggingEvent> loggingEventArgumentCaptor;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		mockedAppender = (Appender<ILoggingEvent>) Mockito.mock(Appender.class);
		defaultCharsetReactorNettyLoggingHandler =
			new ReactorNettyLoggingHandler(
				ReactorNettyLoggingHandlerTest.class.getName(),
				LogLevel.DEBUG,
				Charset.defaultCharset());

		loggingEventArgumentCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
		Mockito.when(mockedAppender.getName()).thenReturn("MOCK");
		ROOT.addAppender(mockedAppender);
	}

	@AfterEach
	void tearDown() {
		ROOT.detachAppender(mockedAppender);
	}

	@Test
	void shouldLogByteBuf() {
		final ByteBuf byteBuf = Unpooled.copiedBuffer("TEST", Charset.defaultCharset());

		sendMessage(byteBuf, "[id: 0xembedded, L:embedded - R:embedded] READ: 4B TEST");
	}

	@Test
	void shouldLogByteBufHolder() {
		final ByteBufHolder byteBufHolder =
			new DefaultByteBufHolder(Unpooled.copiedBuffer("TEST", Charset.defaultCharset()));

		sendMessage(byteBufHolder, "[id: 0xembedded, L:embedded - R:embedded] READ: 4B TEST");
	}

	@Test
	void shouldLogObject() {
		sendMessage("TEST", "[id: 0xembedded, L:embedded - R:embedded] READ: TEST");
	}

	private void sendMessage(Object input, String expectedResult) {
		final EmbeddedChannel channel = new EmbeddedChannel(defaultCharsetReactorNettyLoggingHandler);
		channel.writeInbound(input);

		Mockito.verify(mockedAppender, Mockito.times(4)).doAppend(loggingEventArgumentCaptor.capture());

		final LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(2);
		assertThat(relevantLog.getMessage()).isEqualTo(expectedResult);
	}

	@Test
	void shouldThrowUnsupportedOperationExceptionWhenByteBufFormatIsCalled() {
		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> defaultCharsetReactorNettyLoggingHandler.byteBufFormat());
	}

}