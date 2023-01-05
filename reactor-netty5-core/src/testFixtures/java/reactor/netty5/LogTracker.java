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
package reactor.netty5;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

/**
 * Helper class used to track the occurrence of a given string from a given Logger.
 *
 * @since 1.0.27
 */
public class LogTracker extends AppenderBase<ILoggingEvent> implements AutoCloseable {

	public final CountDownLatch latch;
	private final ch.qos.logback.classic.Logger logger;
	private final String[] messages;

	public LogTracker(Class<?> className, String... messages) {
		this(className.getName(), messages);
	}
	public LogTracker(String loggerName, String... messages) {
		this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName);
		this.messages = messages;
		this.latch = new CountDownLatch(messages.length);

		start();
		this.logger.addAppender(this);
	}

	@Override
	protected void append(ILoggingEvent eventObject) {
		if (Stream.of(messages).anyMatch(msg -> eventObject.getFormattedMessage().contains(msg))) {
			latch.countDown();
		}
	}

	@Override
	public void close() {
		logger.detachAppender(this);
		stop();
	}

}
