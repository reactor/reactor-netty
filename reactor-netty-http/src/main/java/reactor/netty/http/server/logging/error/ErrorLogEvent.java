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

/**
 * Define an interface to handle error log events propagated through UserEvent.
 *
 * @author raccoonback
 * @author Violeta Georgieva
 * @since 1.2.6
 */
public interface ErrorLogEvent {

	/**
	 * Creates a default {@code ErrorLogEvent} with the given throwable.
	 *
	 * @param t the throwable that occurred
	 * @return a new {@link DefaultErrorLogEvent}
	 * @see DefaultErrorLogEvent
	 */
	static ErrorLogEvent create(Throwable t) {
		return new DefaultErrorLogEvent(t);
	}

	/**
	 * Returns the throwable that occurred.
	 *
	 * @return the throwable that occurred
	 */
	Throwable cause();
}
