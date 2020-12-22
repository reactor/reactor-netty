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

package reactor.netty.http.server.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import io.netty.channel.ChannelHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.http.server.HttpServer;
import reactor.util.annotation.Nullable;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.server.logging.AccessLog.LOG;

class AccessLogTest {
	public static final String ACCESS_LOG_HANDLER = "AccessLogHandler";

	private static final Logger ROOT = (Logger) LoggerFactory.getLogger(LOG.getName());
	public static final String NOT_FOUND = "NOT FOUND";
	public static final String FOUND = "FOUND";
	public static final String CUSTOM_FORMAT = "method={}, uri={}";

	private Appender<ILoggingEvent> mockedAppender;
	private ArgumentCaptor<LoggingEvent> loggingEventArgumentCaptor;

	private DisposableServer disposableServer;

	@BeforeEach
	@SuppressWarnings("unchecked")
	public void setUp() {
		mockedAppender = (Appender<ILoggingEvent>) Mockito.mock(Appender.class);
		loggingEventArgumentCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
		Mockito.when(mockedAppender.getName()).thenReturn("MOCK");
		ROOT.addAppender(mockedAppender);
	}

	@AfterEach
	public void tearDown() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
		ROOT.detachAppender(mockedAppender);
	}

	@Test
	public void accessLogDefaultFormat() {
		disposableServer = HttpServer.create()
				.port(0)
				.handle((req, resp) -> {
					resp.withConnection(conn -> {
						ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
						resp.header(ACCESS_LOG_HANDLER, handler != null ? FOUND : NOT_FOUND);
					});
					return resp.send();
				})
				.accessLog(true)
				.wiretap(true)
				.bindNow();

		HttpClientResponse response = HttpClient.create()
				.port(disposableServer.port())
				.wiretap(true)
				.get()
				.uri("/test")
				.response()
				.block();

		assertAccessLogging(response, true, null);
	}

	@Test
	public void accessLogCustomFormat() {
		disposableServer = HttpServer.create()
				.port(8080)
				.handle((req, resp) -> {
					resp.withConnection(conn -> {
						ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
						resp.header(ACCESS_LOG_HANDLER, handler != null ? FOUND : NOT_FOUND);
					});
					return resp.send();
				})
				.accessLog(true, args -> AccessLog.create(CUSTOM_FORMAT, args.method(), args.uri()))
				.wiretap(true)
				.bindNow();

		HttpClientResponse response = HttpClient.create()
				.port(disposableServer.port())
				.wiretap(true)
				.get()
				.uri("/test")
				.response()
				.block();

		assertAccessLogging(response, true, CUSTOM_FORMAT);
	}

	@Test
	public void secondCallToAccessLogOverridesPreviousOne() {
		disposableServer = HttpServer.create()
				.port(8080)
				.handle((req, resp) -> {
					resp.withConnection(conn -> {
						ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
						resp.header(ACCESS_LOG_HANDLER, handler != null ? FOUND : NOT_FOUND);
					});
					return resp.send();
				})
				.accessLog(true, args -> AccessLog.create(CUSTOM_FORMAT, args.method(), args.uri()))
				.accessLog(false)
				.wiretap(true)
				.bindNow();

		HttpClientResponse response = HttpClient.create()
				.port(disposableServer.port())
				.wiretap(true)
				.get()
				.uri("/test")
				.response()
				.block();

		assertAccessLogging(response, false, null);
	}

	void assertAccessLogging(@Nullable HttpClientResponse response, boolean enable, @Nullable String loggerFormat) {
		if (enable) {
			Mockito.verify(mockedAppender, Mockito.times(1)).doAppend(loggingEventArgumentCaptor.capture());
			final LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(0);

			if (null == loggerFormat) {
				assertThat(relevantLog.getMessage()).isEqualTo(BaseAccessLogHandler.DEFAULT_LOG_FORMAT);
				assertThat(relevantLog.getFormattedMessage()).contains("GET /test HTTP/1.1\" 200");
			}
			else {
				assertThat(relevantLog.getMessage()).isEqualTo(loggerFormat);
				assertThat(relevantLog.getFormattedMessage()).isEqualTo("method=GET, uri=/test");
			}
		}
		else {
			Mockito.verify(mockedAppender, Mockito.times(0)).doAppend(loggingEventArgumentCaptor.capture());
			assertThat(loggingEventArgumentCaptor.getAllValues()).isEmpty();
		}

		assertThat(response).isNotNull();
		assertThat(response.responseHeaders().get(ACCESS_LOG_HANDLER)).isEqualTo(enable ? FOUND : NOT_FOUND);
	}

}