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
package reactor.netty.http.server.logging.error;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import reactor.netty.BaseHttpTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.server.logging.error.ErrorLog.LOGGER;

/**
 * This test class verifies {@link DefaultErrorLogHandler}.
 *
 * @author raccoonback
 */
class ErrorLogTest extends BaseHttpTest {

	static final Logger ROOT = (Logger) LoggerFactory.getLogger(LOGGER.getName());
	static final String CUSTOM_FORMAT = "method={}, uri={}";

	private Appender<ILoggingEvent> mockedAppender;
	private ArgumentCaptor<LoggingEvent> loggingEventArgumentCaptor;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		mockedAppender = (Appender<ILoggingEvent>) Mockito.mock(Appender.class);
		loggingEventArgumentCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
		Mockito.when(mockedAppender.getName()).thenReturn("MOCK");
		ROOT.addAppender(mockedAppender);
	}

	@AfterEach
	void tearDown() {
		ROOT.detachAppender(mockedAppender);
	}

	@Test
	void errorLogDefaultFormat() {
		disposableServer = createServer()
				.handle((req, resp) -> {
					resp.withConnection(
							conn -> conn.channel()
									.pipeline()
									.fireExceptionCaught(new RuntimeException())
					);
					return resp.send();
				})
				.errorLog(true)
				.bindNow();

		getHttpClientResponse("/example/test");

		Mockito.verify(mockedAppender, Mockito.times(1))
				.doAppend(loggingEventArgumentCaptor.capture());
		assertThat(loggingEventArgumentCaptor.getAllValues()).hasSize(1);

		LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(0);
		assertThat(relevantLog.getMessage())
				.isEqualTo(BaseErrorLogHandler.DEFAULT_LOG_FORMAT);
		assertThat(relevantLog.getFormattedMessage())
				.matches("\\[([0-9a-fA-F:.]+)(:\\d)*] -");
	}

	@Test
	void errorLogCustomFormat() {
		disposableServer = createServer()
				.handle((req, resp) -> {
					resp.withConnection(
							conn -> conn.channel()
									.pipeline()
									.fireExceptionCaught(new RuntimeException())
					);
					return resp.send();
				})
				.errorLog(
						true,
						args -> ErrorLog.create(
								CUSTOM_FORMAT,
								args.httpServerInfos().method(),
								args.httpServerInfos().uri()
						)
				)
				.bindNow();

		getHttpClientResponse("/example/test");

		Mockito.verify(mockedAppender, Mockito.times(1))
				.doAppend(loggingEventArgumentCaptor.capture());
		assertThat(loggingEventArgumentCaptor.getAllValues()).hasSize(1);

		LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(0);
		assertThat(relevantLog.getMessage())
				.isEqualTo(CUSTOM_FORMAT);
		assertThat(relevantLog.getFormattedMessage())
				.isEqualTo("method=GET, uri=/example/test");
	}

	@Test
	void secondCallToErrorLogOverridesPreviousOne() {
		disposableServer = createServer()
				.handle((req, resp) -> {
					resp.withConnection(
							conn -> conn.channel()
									.pipeline()
									.fireExceptionCaught(new RuntimeException())
					);
					return resp.send();
				})
				.errorLog(
						true,
						args -> ErrorLog.create(
								CUSTOM_FORMAT,
								args.httpServerInfos().method(),
								args.httpServerInfos().uri()
						)
				)
				.errorLog(false)
				.bindNow();

		getHttpClientResponse("/example/test");

		Mockito.verify(mockedAppender, Mockito.times(0))
				.doAppend(loggingEventArgumentCaptor.capture());
		assertThat(loggingEventArgumentCaptor.getAllValues()).isEmpty();
	}

	@Test
	void errorLogFilteringAndFormatting() {
		disposableServer = createServer()
				.handle((req, resp) -> {
					resp.withConnection(
							conn -> conn.channel()
									.pipeline()
									.fireExceptionCaught(new RuntimeException())
					);
					return resp.send();
				})
				.errorLog(
						true,
						ErrorLogFactory.createFilter(
								p -> p.httpServerInfos().uri().startsWith("/filtered"),
								args -> ErrorLog.create(CUSTOM_FORMAT, args.httpServerInfos().method(), args.httpServerInfos().uri())
						)
				)
				.bindNow();

		getHttpClientResponse("/example/test");
		getHttpClientResponse("/filtered/test");

		Mockito.verify(mockedAppender, Mockito.times(1)).doAppend(loggingEventArgumentCaptor.capture());
		assertThat(loggingEventArgumentCaptor.getAllValues()).hasSize(1);

		final LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(0);
		assertThat(relevantLog.getMessage())
				.isEqualTo(CUSTOM_FORMAT);
		assertThat(relevantLog.getFormattedMessage())
				.isEqualTo("method=GET, uri=/filtered/test");
	}

	private void getHttpClientResponse(String uri) {
		try {
			createClient(disposableServer.port())
					.get()
					.uri(uri)
					.response()
					.block(Duration.ofSeconds(30));
		}
		catch (Exception e) {
			// ignore
		}
	}
}
