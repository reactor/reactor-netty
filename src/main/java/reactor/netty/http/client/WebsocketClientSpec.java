/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import reactor.netty.http.websocket.WebsocketSpec;

/**
 * Websocket client configuration
 *
 * @author Violeta Georgieva
 * @since 0.9.7
 */
public interface WebsocketClientSpec extends WebsocketSpec {

	/**
	 * Create builder with default properties:<br>
	 * protocols = null
	 * <br>
	 * maxFramePayloadLength = 65536
	 * <br>
	 * handlePing = false
	 *
	 * @return {@link Builder}
	 */
	static Builder builder() {
		return new Builder();
	}

	final class Builder extends WebsocketSpec.Builder<Builder> {

		private Builder() {
		}

		/**
		 * Builds new {@link WebsocketClientSpec}
		 *
		 * @return builds new {@link WebsocketClientSpec}
		 */
		public final WebsocketClientSpec build() {
			return new WebsocketClientSpecImpl(this);
		}
	}
}
