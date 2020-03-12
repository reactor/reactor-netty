/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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

package reactor.netty.http.websocket;

/**
 * Configurer implementation for {@link WebsocketSpec}
 *
 * @author Dmitrii Borin
 * @author Violeta Georgieva
 * @since 0.9.5
 */
public class WebsocketSpecImpl implements WebsocketSpec {
	private final String protocols;
	private final int maxFramePayloadLength;
	private final boolean proxyPing;
	private final boolean compress;

	protected WebsocketSpecImpl(WebsocketSpec.Builder<?> builder) {
		this.protocols = builder.protocols;
		this.maxFramePayloadLength = builder.maxFramePayloadLength;
		this.proxyPing = builder.handlePing;
		this.compress = builder.compress;
	}

	@Override
	public final String protocols() {
		return protocols;
	}

	@Override
	public final int maxFramePayloadLength() {
		return maxFramePayloadLength;
	}

	@Override
	public final boolean handlePing() {
		return proxyPing;
	}

	@Override
	public boolean compress() {
		return compress;
	}
}
