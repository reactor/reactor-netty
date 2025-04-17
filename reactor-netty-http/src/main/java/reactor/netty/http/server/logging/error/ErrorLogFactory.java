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

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An interface to declare more concisely a {@link Function} that apply an {@link ErrorLog} by an
 * {@link ErrorLogArgProvider}.
 * <p>
 * Can be used in {@link reactor.netty.http.server.HttpServer#errorLog(boolean, ErrorLogFactory) errorLog} method for example.
 *
 * @author raccoonback
 * @since 1.2.5
 */
public interface ErrorLogFactory extends Function<ErrorLogArgProvider, ErrorLog> {

	/**
	 * Helper method to create an error log factory that selectively enables error logs.
	 * <p>
	 * Any exception (represented as an {@link ErrorLogArgProvider}) that doesn't match the
	 * provided {@link Predicate} is excluded from the error log. Other exceptions are logged
	 * using the default format.
	 *
	 * @param predicate the filter that returns {@code true} if the exception should be logged, {@code false} otherwise
	 * @return an {@link ErrorLogFactory} to be used in
	 * {@link reactor.netty.http.server.HttpServer#errorLog(boolean, ErrorLogFactory)}
	 */
	static ErrorLogFactory createFilter(Predicate<ErrorLogArgProvider> predicate) {
		return input -> predicate.test(input) ? BaseErrorLogHandler.DEFAULT_ERROR_LOG.apply(input) : null;
	}

	/**
	 * Helper method to create an error log factory that selectively enables error logs and customizes
	 * the format to apply.
	 * <p>
	 * Any exception (represented as an {@link ErrorLogArgProvider}) that doesn't match the
	 * provided {@link Predicate} is excluded from the error log. Other exceptions are logged
	 * using the provided formatting {@link Function}.
	 * Create an {@link ErrorLog} instance by defining both the String format and a vararg of the relevant arguments,
	 * extracted from the {@link ErrorLogArgProvider}.
	 * <p>
	 *
	 * @param predicate      the filter that returns {@code true} if the exception should be logged, {@code false} otherwise
	 * @param formatFunction the {@link ErrorLogFactory} that creates {@link ErrorLog} instances, encapsulating the
	 *                       format
	 *                       and the extraction of relevant arguments
	 * @return an {@link ErrorLogFactory} to be used in
	 * {@link reactor.netty.http.server.HttpServer#errorLog(boolean, ErrorLogFactory)}
	 */
	static ErrorLogFactory createFilter(Predicate<ErrorLogArgProvider> predicate, ErrorLogFactory formatFunction) {
		return input -> predicate.test(input) ? formatFunction.apply(input) : null;
	}

}