/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.util.concurrent.CountDownLatch;

/**
 * Helper class used to track occurrences of string from logs.
 */
public class LogTracker extends AppenderBase<ILoggingEvent>  implements AutoCloseable {

	public static final String LOG_FLUXRECEIVE = "reactor.netty.channel.FluxReceive";
	public static final String MSG_FLUXRECEIVE_DROPPED = "FluxReceive{pending=0, cancelled=true, inboundDone=false, inboundError=null}: dropping frame";

	public final CountDownLatch latch;
	private final Logger logger;
	private final String message;

	public LogTracker(String loggerName, String message, int occurrences) {
		this.logger = (Logger) LoggerFactory.getLogger(loggerName);
		this.message = message;
		this.latch = new CountDownLatch(occurrences);

		start();
		this.logger.addAppender(this);
	}

	public LogTracker(String loggerName, String message) {
		this(loggerName, message, 1);
	}

	public LogTracker(Class<?> loggerName, String message) {
		this(loggerName.getName(), message, 1);
	}

	@Override
	protected void append(ILoggingEvent eventObject) {
		if (eventObject.getFormattedMessage().contains(message)) {
			latch.countDown();
		}
	}
	@Override
	public void close() {
		logger.detachAppender(this);
		stop();
	}

}
