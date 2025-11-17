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
package reactor.netty.http.client;

/**
 * Exception thrown to trigger HTTP authentication retry.
 * <p>
 * This exception is used internally by the generic HTTP authentication framework
 * to signal that the current request requires authentication and should be retried.
 * The framework will invoke the configured authenticator before retrying the request.
 * </p>
 *
 * @author Oliver Ko
 * @since 1.3.0
 */
final class HttpClientAuthenticationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	HttpClientAuthenticationException() {
		super("HTTP authentication required, triggering retry");
	}
}