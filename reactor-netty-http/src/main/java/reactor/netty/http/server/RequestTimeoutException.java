/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import io.netty.channel.ChannelException;

final class RequestTimeoutException extends ChannelException {

	static final RequestTimeoutException INSTANCE = new RequestTimeoutException();

	private static final long serialVersionUID = 422626851161276356L;

	RequestTimeoutException() {
		super(null, null, true);
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return this;
	}
}
