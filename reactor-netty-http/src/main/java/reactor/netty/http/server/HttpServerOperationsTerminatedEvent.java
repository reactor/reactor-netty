/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

/**
 * A user event fired through the channel pipeline during
 * {@code ChannelOperations#terminate()} via
 * {@link HttpServerOperations#afterInboundComplete() afterInboundComplete()}.
 * <p>
 * This event signals that the previous {@link HttpServerOperations} has passed
 * the channel rebind point and that keep-alive request handoff may resume.
 *
 * @since 1.2.18
 */
final class HttpServerOperationsTerminatedEvent {

	static final HttpServerOperationsTerminatedEvent INSTANCE = new HttpServerOperationsTerminatedEvent();

	private HttpServerOperationsTerminatedEvent() {
	}

	@Override
	public String toString() {
		return "HttpServerOperationsTerminatedEvent";
	}
}
