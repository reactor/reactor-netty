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
package reactor.netty5.http.server.logging.error;

/**
 * Represents a log entry for HTTP server errors.
 * Implementations of this interface define how the error information is logged.
 *
 * @author raccoonback
 * @author Violeta Georgieva
 * @since 1.2.6
 */
public interface ErrorLog {

	/**
	 * Creates a default {@code ErrorLog} with the given log format and arguments.
	 *
	 * @param logFormat the log format string
	 * @param args the list of arguments
	 * @return a new {@link DefaultErrorLog}
	 * @see DefaultErrorLog
	 */
	static ErrorLog create(String logFormat, Object... args) {
		return new DefaultErrorLog(logFormat, args);
	}

	/**
	 * Logs the error information.
	 */
	void log();
}
