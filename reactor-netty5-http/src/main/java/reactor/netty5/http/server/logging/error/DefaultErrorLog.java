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

import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.Objects;

/**
 * Log the http default error information into a Logger named {@code reactor.netty.http.server.ErrorLog} at {@code ERROR} level.
 * <p>
 * See {@link ErrorLogFactory} for convenience methods to create an error log factory to be passed to
 * {@link reactor.netty5.http.server.HttpServer#errorLog(boolean, ErrorLogFactory)} during server configuration.
 *
 * @author raccoonback
 * @author Violeta Georgieva
 */
final class DefaultErrorLog implements ErrorLog {

	static final Logger LOGGER = Loggers.getLogger("reactor.netty.http.server.ErrorLog");

	final String logFormat;
	final Object[] args;

	DefaultErrorLog(String logFormat, Object... args) {
		Objects.requireNonNull(logFormat, "logFormat");
		this.logFormat = logFormat;
		this.args = args;
	}

	@Override
	public void log() {
		if (LOGGER.isErrorEnabled()) {
			LOGGER.error(logFormat, args);
		}
	}
}
