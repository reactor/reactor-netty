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
import reactor.util.context.ContextView;

import java.net.SocketAddress;

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
			String path,
			ContextView contextView,
			String method) {
		super(remoteAddress, proxyAddress, path, contextView, method);
		this.recorder = recorder;
	}

	@Override
	protected WebSocketClientMetricsRecorder recorder() {
		return recorder;
	}
}
