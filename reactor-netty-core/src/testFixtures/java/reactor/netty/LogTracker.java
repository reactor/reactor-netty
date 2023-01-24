/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

/**
 * Helper class used to track the occurrence of a given string from a given Logger.
 *
 * @since 1.0.27
 */
public final class LogTracker extends AppenderBase<ILoggingEvent> implements AutoCloseable {

	public final List<ILoggingEvent> actualMessages = new ArrayList<>();
	public final CountDownLatch latch;

	private final Logger logger;
	private final String[] expectedMessages;

	/**
	 * Creates a new {@link LogTracker}.
	 *
	 * @param className the logger name
	 * @param expectedMessages the expected messages
	 */
	public LogTracker(Class<?> className, String... expectedMessages) {
		this(className.getName(), expectedMessages);
	}

	/**
	 * Creates a new {@link LogTracker}.
	 *
	 * @param loggerName the logger name
	 * @param expectedMessages the expected messages
	 */
	public LogTracker(String loggerName, String... expectedMessages) {
		this(loggerName, expectedMessages.length, expectedMessages);
	}

	/**
	 * Creates a new {@link LogTracker}.
	 *
	 * @param loggerName the logger name
	 * @param expectedCount the number of times the expected messages will appear in the logs
	 * @param expectedMessages the expected messages
	 */
	public LogTracker(String loggerName, int expectedCount, String... expectedMessages) {
		this.logger = (Logger) LoggerFactory.getLogger(loggerName);
		this.expectedMessages = expectedMessages;
		this.latch = new CountDownLatch(expectedCount);

		start();
		this.logger.addAppender(this);
	}

	@Override
	protected void append(ILoggingEvent eventObject) {
		if (Stream.of(expectedMessages).anyMatch(msg -> eventObject.getFormattedMessage().contains(msg))) {
			actualMessages.add(eventObject);
			latch.countDown();
		}
	}

	@Override
	public void close() {
		logger.detachAppender(this);
		stop();
	}
}
