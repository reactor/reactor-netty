/*
 * Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import java.util.Objects;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * An error for signalling that an error occurred during a communication over HTTP version
 *
 */
final class RedirectClientException extends RuntimeException {

	final String location;
	final HttpResponseStatus status;

	RedirectClientException(HttpHeaders headers, HttpResponseStatus status) {
		location = Objects.requireNonNull(headers.get(HttpHeaderNames.LOCATION), "location");
		this.status = Objects.requireNonNull(status, "status");
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		// omit stacktrace for this exception
		return this;
	}

	private static final long serialVersionUID = -8887076761196723045L;
}
