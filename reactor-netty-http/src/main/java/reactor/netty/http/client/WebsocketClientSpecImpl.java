/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import reactor.netty.http.websocket.WebsocketSpecImpl;

/**
 * Websocket client configuration.
 *
 * @author Violeta Georgieva
 * @since 0.9.7
 */
final class WebsocketClientSpecImpl extends WebsocketSpecImpl implements WebsocketClientSpec {

	@Override
	public boolean compressionAllowClientNoContext() {
		return allowClientNoContext;
	}

	@Override
	public boolean compressionRequestedServerNoContext() {
		return requestedServerNoContext;
	}

	@Override
	public WebSocketVersion version() {
		return version;
	}

	private final boolean allowClientNoContext;
	private final boolean requestedServerNoContext;
	private final WebSocketVersion version;

	WebsocketClientSpecImpl(WebsocketClientSpec.Builder builder) {
		super(builder);
		this.allowClientNoContext = builder.allowClientNoContext;
		this.requestedServerNoContext = builder.requestedServerNoContext;
		this.version = builder.version;
	}
}
