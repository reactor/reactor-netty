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

import static io.netty5.buffer.DefaultBufferAllocators.preferredAllocator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.Charset;

import io.netty5.buffer.Buffer;
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
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.handler.logging.LogLevel;
import io.netty5.handler.logging.LoggingHandler;

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
		final Buffer buffer = preferredAllocator().copyOf("TEST".getBytes(Charset.defaultCharset()));

		sendMessage(buffer, "[embedded, L:embedded - R:embedded] READ: 4B TEST");
	}

	@Test
	void shouldLogObject() {
		sendMessage("TEST", "[embedded, L:embedded - R:embedded] READ: TEST");
	}

	private void sendMessage(Object input, String expectedResult) {
		final EmbeddedChannel channel = new EmbeddedChannel(defaultCharsetReactorNettyLoggingHandler);
		channel.writeInbound(input);

		Mockito.verify(mockedAppender, Mockito.times(4)).doAppend(loggingEventArgumentCaptor.capture());

		final LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(2);
		assertThat(relevantLog.getMessage()).isEqualTo(expectedResult);
	}

	@Test
	void shouldThrowUnsupportedOperationExceptionWhenBufferFormatIsCalled() {
		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> defaultCharsetReactorNettyLoggingHandler.bufferFormat());
	}

}