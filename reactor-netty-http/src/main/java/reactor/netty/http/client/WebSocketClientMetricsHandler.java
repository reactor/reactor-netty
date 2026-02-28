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
package reactor.netty.http.client;

import org.jspecify.annotations.Nullable;

import java.net.SocketAddress;
import java.util.function.Function;

/**
 * {@link AbstractWebSocketClientMetricsHandler} for collecting metrics on WebSocket {@link HttpClient} level.
 *
 * @author LivingLikeKrillin
 * @since 1.3.5
 */
final class WebSocketClientMetricsHandler extends AbstractWebSocketClientMetricsHandler {

	final WebSocketClientMetricsRecorder recorder;

	WebSocketClientMetricsHandler(WebSocketClientMetricsRecorder recorder,
			SocketAddress remoteAddress,
			@Nullable SocketAddress proxyAddress,
			@Nullable Function<String, String> uriTagValue) {
		super(remoteAddress, proxyAddress, uriTagValue);
		this.recorder = recorder;
	}

	WebSocketClientMetricsHandler(WebSocketClientMetricsHandler copy) {
		super(copy);
		this.recorder = copy.recorder;
	}

	@Override
	protected WebSocketClientMetricsRecorder recorder() {
		return recorder;
	}
}
