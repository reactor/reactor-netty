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

import io.netty.channel.ChannelDuplexHandler;
import org.jspecify.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

class BaseErrorLogHandler extends ChannelDuplexHandler {

	static String PID;
	static {
		String jvmName = ManagementFactory.getRuntimeMXBean().getName();
		int index = jvmName.indexOf('@');
		if (index != -1) {
			PID = jvmName.substring(0, index);
		}
		else {
			PID = jvmName;
		}
	}

	static final String DEFAULT_LOG_FORMAT = "[{}] [pid " + PID + "] [client {}] {}";
	static final DateTimeFormatter DATE_TIME_FORMATTER =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ");
	static final String MISSING = "-";

	static final Function<ErrorLogArgProvider, @Nullable ErrorLog> DEFAULT_ERROR_LOG =
			args -> ErrorLog.create(
					DEFAULT_LOG_FORMAT,
					args.errorDateTime().format(DATE_TIME_FORMATTER),
					refinedRemoteAddress(args.remoteAddress()),
					refinedExceptionMessage(args.cause()));

	final Function<ErrorLogArgProvider, @Nullable ErrorLog> errorLog;

	BaseErrorLogHandler(@Nullable Function<ErrorLogArgProvider, @Nullable ErrorLog> errorLog) {
		this.errorLog = errorLog == null ? DEFAULT_ERROR_LOG : errorLog;
	}

	private static String refinedRemoteAddress(@Nullable SocketAddress remoteAddress) {
		if (remoteAddress instanceof InetSocketAddress) {
			return ((InetSocketAddress) remoteAddress).getHostString();
		}

		return MISSING;
	}

	private static String refinedExceptionMessage(Throwable throwable) {
		String error = throwable.getClass().getName();
		String message = throwable.getLocalizedMessage();
		return message == null ? error : error + "." + message;
	}
}
