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

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An interface to declare more concisely a {@link Function} that apply an {@link AccessLog} by an
 * {@link AccessLogArgProvider}.
 * <p>
 * Can be used in {@link reactor.netty.http.server.HttpServer#accessLog(boolean, AccessLogFactory) accessLog} method for example.
 *
 * @author Simon Baslé
 * @author Audrey Neveu
 * @since 1.0.3
 */
public interface AccessLogFactory extends Function<AccessLogArgProvider, AccessLog> {

	/**
	 * Helper method to create an access log factory that selectively enables access logs.
	 * <p>
	 * Any request (represented as an {@link AccessLogArgProvider}) that doesn't match the
	 * provided {@link Predicate} is excluded from the access log. Other requests are logged
	 * using the default format.
	 *
	 * @param predicate the filter that returns {@code true} if the request should be logged, {@code false} otherwise
	 * @return an {@link AccessLogFactory} to be used in
	 * {@link reactor.netty.http.server.HttpServer#accessLog(boolean, AccessLogFactory)}
	 * @since 1.0.3
	 */
	static AccessLogFactory createFilter(Predicate<AccessLogArgProvider> predicate) {
		return input -> predicate.test(input) ? BaseAccessLogHandler.DEFAULT_ACCESS_LOG.apply(input) : null;
	}

	/**
	 * Helper method to create an access log factory that selectively enables access logs and customizes
	 * the format to apply.
	 * <p>
	 * Any request (represented as an {@link AccessLogArgProvider}) that doesn't match the
	 * provided {@link Predicate} is excluded from the access log. Other requests are logged
	 * using the provided formatting {@link Function}.
	 * Said function is expected to {@link AccessLog#create(String, Object...) create} an {@link AccessLog} instance,
	 * defining both the String format and a vararg of the relevant arguments, extracted from the
	 * {@link AccessLogArgProvider}.
	 * <p>
	 *
	 * @param predicate the filter that returns {@code true} if the request should be logged, {@code false} otherwise
	 * @param formatFunction the {@link AccessLogFactory} that creates {@link AccessLog} instances, encapsulating the
	 * format
	 * and the extraction of relevant arguments
	 * @return an {@link AccessLogFactory} to be used in
	 * {@link reactor.netty.http.server.HttpServer#accessLog(boolean, AccessLogFactory)}
	 * @since 1.0.3
	 */
	static AccessLogFactory createFilter(Predicate<AccessLogArgProvider> predicate, AccessLogFactory formatFunction) {
		return input -> predicate.test(input) ? formatFunction.apply(input) : null;
	}

}