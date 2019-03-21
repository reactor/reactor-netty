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

import java.util.Objects;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;

/**
 * An error for signalling that an error occurred during a communication over HTTP version
 *
 */
final class RedirectClientException extends RuntimeException {

	final String location;

	RedirectClientException(HttpHeaders headers) {
		location = Objects.requireNonNull(headers.get(HttpHeaderNames.LOCATION));
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		// omit stacktrace for this exception
		return this;
	}
}
