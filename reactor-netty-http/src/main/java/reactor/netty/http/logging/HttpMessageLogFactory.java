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
package reactor.netty.http.logging;

/**
 * Factory for generating a log message based on a given {@link HttpMessageArgProvider}.
 *
 * @author Violeta Georgieva
 * @since 1.0.4
 */
public interface HttpMessageLogFactory {

	/**
	 * Generates a log message based on a given {@link HttpMessageArgProvider} for a log with a level {@code TRACE}.
	 *
	 * @param arg the {@link HttpMessageArgProvider}
	 * @return the generated log message
	 */
	String trace(HttpMessageArgProvider arg);

	/**
	 * Generates a log message based on a given {@link HttpMessageArgProvider} for a log with a level {@code DEBUG}.
	 *
	 * @param arg the {@link HttpMessageArgProvider}
	 * @return the generated log message
	 */
	String debug(HttpMessageArgProvider arg);

	/**
	 * Generates a log message based on a given {@link HttpMessageArgProvider} for a log with a level {@code INFO}.
	 *
	 * @param arg the {@link HttpMessageArgProvider}
	 * @return the generated log message
	 */
	String info(HttpMessageArgProvider arg);

	/**
	 * Generates a log message based on a given {@link HttpMessageArgProvider} for a log with a level {@code WARN}.
	 *
	 * @param arg the {@link HttpMessageArgProvider}
	 * @return the generated log message
	 */
	String warn(HttpMessageArgProvider arg);

	/**
	 * Generates a log message based on a given {@link HttpMessageArgProvider} for a log with a level {@code ERROR}.
	 *
	 * @param arg the {@link HttpMessageArgProvider}
	 * @return the generated log message
	 */
	String error(HttpMessageArgProvider arg);
}
