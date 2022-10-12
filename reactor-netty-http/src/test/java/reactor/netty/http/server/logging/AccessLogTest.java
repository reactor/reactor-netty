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
package reactor.netty.http.server.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.cookie.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.NettyPipeline;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.server.logging.AccessLog.LOG;

class AccessLogTest extends BaseHttpTest {

	static final String ACCESS_LOG_HANDLER = "AccessLogHandler";
	static final Logger ROOT = (Logger) LoggerFactory.getLogger(LOG.getName());
	static final String NOT_FOUND = "NOT FOUND";
	static final String FOUND = "FOUND";
	static final String CUSTOM_FORMAT = "method={}, uri={}, cookie: {}";
	static final String URI_1 = "/example/test";
	static final String URI_2 = "/filtered/test";
	static final String EXPECTED_FORMATTED_MESSAGE_1 = "GET /example/test HTTP/1.1\" 200";
	static final String EXPECTED_FORMATTED_MESSAGE_2 = "method=GET, uri=/example/test, cookie: key=value";
	static final String COOKIE_KEY = "key";
	static final String COOKIE_VALUE = "value";

	static final AccessLogFactory CUSTOM_ACCESS_LOG =
			args -> AccessLog.create(CUSTOM_FORMAT, args.method(), args.uri(), cookieToString(args.cookies()));

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
	void accessLogDefaultFormat() {
		disposableServer = createServer()
				.handle((req, resp) -> {
					resp.withConnection(conn -> {
						ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
						resp.header(ACCESS_LOG_HANDLER, handler != null ? FOUND : NOT_FOUND);
					});
					return resp.send();
				})
				.accessLog(true)
				.bindNow();

		Tuple2<String, String> response = getHttpClientResponse(URI_1);

		assertAccessLogging(response, true, false, null);
	}

	@Test
	void accessLogCustomFormat() {
		disposableServer = createServer()
				.handle((req, resp) -> {
					resp.withConnection(conn -> {
						ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
						resp.header(ACCESS_LOG_HANDLER, handler != null ? FOUND : NOT_FOUND);
					});
					return resp.send();
				})
				.accessLog(true, CUSTOM_ACCESS_LOG)
				.bindNow();

		Tuple2<String, String> response = getHttpClientResponse(URI_1);

		assertAccessLogging(response, true, false, CUSTOM_FORMAT);
	}

	@Test
	void secondCallToAccessLogOverridesPreviousOne() {
		disposableServer = createServer()
				.handle((req, resp) -> {
					resp.withConnection(conn -> {
						ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
						resp.header(ACCESS_LOG_HANDLER, handler != null ? FOUND : NOT_FOUND);
					});
					return resp.send();
				})
				.accessLog(true, CUSTOM_ACCESS_LOG)
				.accessLog(false)
				.bindNow();

		Tuple2<String, String> response = getHttpClientResponse(URI_1);

		assertAccessLogging(response, false, false, null);
	}

	@Test
	void accessLogFiltering() {
		disposableServer = createServer()
				.handle((req, resp) -> {
					resp.withConnection(conn -> {
						ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
						resp.header(ACCESS_LOG_HANDLER, handler != null ? FOUND : NOT_FOUND);
					});
					return resp.send();
				})
				.accessLog(true, AccessLogFactory.createFilter(p -> !String.valueOf(p.uri()).startsWith("/filtered/")))
				.bindNow();

		Tuple2<String, String> response = getHttpClientResponse(URI_1);

		getHttpClientResponse(URI_2);

		assertAccessLogging(response, true, true, null);
	}


	@Test
	void accessLogFilteringAndFormatting() {
		disposableServer = createServer()
				.handle((req, resp) -> {
					resp.withConnection(conn -> {
						ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
						resp.header(ACCESS_LOG_HANDLER, handler != null ? FOUND : NOT_FOUND);
					});
					return resp.send();
				})
				.accessLog(true, AccessLogFactory.createFilter(p -> !String.valueOf(p.uri()).startsWith("/filtered/"),
						CUSTOM_ACCESS_LOG))
				.bindNow();

		Tuple2<String, String> response = getHttpClientResponse(URI_1);

		getHttpClientResponse(URI_2);

		assertAccessLogging(response, true, true, CUSTOM_FORMAT);
	}

	void assertAccessLogging(
			@Nullable Tuple2<String, String> response,
			boolean enable, boolean filteringEnabled,
			@Nullable String loggerFormat) {
		sleep(20);
		if (enable) {
			Mockito.verify(mockedAppender, Mockito.times(1)).doAppend(loggingEventArgumentCaptor.capture());
			assertThat(loggingEventArgumentCaptor.getAllValues()).hasSize(1);
			final LoggingEvent relevantLog = loggingEventArgumentCaptor.getAllValues().get(0);

			if (null == loggerFormat) {
				assertThat(relevantLog.getMessage()).isEqualTo(BaseAccessLogHandler.DEFAULT_LOG_FORMAT);
				assertThat(relevantLog.getFormattedMessage()).contains(EXPECTED_FORMATTED_MESSAGE_1);

				if (filteringEnabled) {
					assertThat(relevantLog.getFormattedMessage()).doesNotContain("filtered");
				}
			}

			else {
				assertThat(relevantLog.getMessage()).isEqualTo(loggerFormat);
				assertThat(relevantLog.getFormattedMessage()).isEqualTo(EXPECTED_FORMATTED_MESSAGE_2);
			}
		}
		else {
			Mockito.verify(mockedAppender, Mockito.times(0)).doAppend(loggingEventArgumentCaptor.capture());
			assertThat(loggingEventArgumentCaptor.getAllValues()).isEmpty();
		}

		assertThat(response).isNotNull();
		assertThat(response.getT2()).isEqualTo(enable ? FOUND : NOT_FOUND);
	}

	@Nullable
	@SuppressWarnings("deprecation")
	private Tuple2<String, String> getHttpClientResponse(String uri) {
		return createClient(disposableServer.port())
				.cookie(COOKIE_KEY, cookie -> cookie.setValue(COOKIE_VALUE))
				.get()
				.uri(uri)
				.responseSingle((res, bytes) ->
						bytes.asString()
						     .defaultIfEmpty("")
						     .zipWith(Mono.just(res.responseHeaders().get(ACCESS_LOG_HANDLER))))
				.block(Duration.ofSeconds(30));
	}

	private void sleep(long ms) {
		try {
			TimeUnit.MILLISECONDS.sleep(ms);
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	static String cookieToString(@Nullable Map<CharSequence, Set<Cookie>> cookies) {
		if (cookies != null) {
			Optional<Map.Entry<CharSequence, Set<Cookie>>> firstEntry = cookies.entrySet().stream().findFirst();
			if (firstEntry.isPresent()) {
				String key = firstEntry.get().getKey().toString();
				Optional<Cookie> firstCookie = firstEntry.get().getValue().stream().findFirst();
				String value = firstCookie.isPresent() ? firstCookie.get().value() : "";
				return key + "=" + value;
			}
		}
		return "";
	}
}
