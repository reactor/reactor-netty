/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.netty.http.client;

import java.io.IOException;

/**
 * An error for signalling that the connection was closed prematurely
 *
 * @author Violeta Georgieva
 */
public final class PrematureCloseException extends IOException {

	public static final PrematureCloseException BEFORE_RESPONSE_SENDING_REQUEST =
			new PrematureCloseException("Connection has been closed BEFORE response, while sending request body");

	public static final PrematureCloseException BEFORE_RESPONSE =
			new PrematureCloseException("Connection prematurely closed BEFORE response");

	public static final PrematureCloseException DURING_RESPONSE =
			new PrematureCloseException("Connection prematurely closed DURING response");

	PrematureCloseException(String message) {
		super(message);
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		// omit stacktrace for this exception
		return this;
	}
}
