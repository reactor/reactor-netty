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
package reactor.netty.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;
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

public class ReactorNettyLoggingHandlerTest {

	private LoggingHandler defaultCharsetReactorNettyLoggingHandler;

	private Appender<ILoggingEvent> mockedAppender;
	private ArgumentCaptor<LoggingEvent> loggingEventArgumentCaptor;

	@Before
	@SuppressWarnings("unchecked")
	public void setUp() {
		defaultCharsetReactorNettyLoggingHandler =
			new ReactorNettyLoggingHandler(
				ReactorNettyLoggingHandlerTest.class.getName(),
				LogLevel.DEBUG,
				Charset.defaultCharset());

		mockedAppender = (Appender<ILoggingEvent>) Mockito.mock(Appender.class);
		loggingEventArgumentCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
		Mockito.when(mockedAppender.getName()).thenReturn("MOCK");
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		root.addAppender(mockedAppender);
	}

	@Test
	public void shouldLogByteBuf() throws Exception {
		final ByteBuf msg = Unpooled.copiedBuffer("TEST", StandardCharsets.UTF_8);
		final EmbeddedChannel channel = new EmbeddedChannel(defaultCharsetReactorNettyLoggingHandler);

		channel.writeInbound(msg);

		Mockito.verify(mockedAppender, Mockito.times(4)).doAppend(loggingEventArgumentCaptor.capture());

		final LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(2);
		assertThat(relevantLog.getMessage()).isEqualTo("[id: 0xembedded, L:embedded - R:embedded] READ: 4B TEST");
	}

	@Test
	public void shouldLogByteBufHolder() throws Exception {
		final ByteBufHolder byteBufHolder =
			new DefaultByteBufHolder(Unpooled.copiedBuffer("TEST", StandardCharsets.UTF_8));
		final EmbeddedChannel channel = new EmbeddedChannel(defaultCharsetReactorNettyLoggingHandler);

		channel.writeInbound(byteBufHolder);

		Mockito.verify(mockedAppender, Mockito.times(4)).doAppend(loggingEventArgumentCaptor.capture());

		final LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(2);
		assertThat(relevantLog.getMessage()).isEqualTo("[id: 0xembedded, L:embedded - R:embedded] READ: 4B TEST");
	}

	@Test
	public void shouldLogObject() throws Exception {
		final EmbeddedChannel channel = new EmbeddedChannel(defaultCharsetReactorNettyLoggingHandler);

		channel.writeInbound("TEST");

		Mockito.verify(mockedAppender, Mockito.times(4)).doAppend(loggingEventArgumentCaptor.capture());

		final LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(2);
		assertThat(relevantLog.getMessage()).isEqualTo("[id: 0xembedded, L:embedded - R:embedded] READ: TEST");
	}

}