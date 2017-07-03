/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.udp;

import io.netty.bootstrap.Bootstrap;
import reactor.ipc.netty.options.ClientOptions;

/**
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class UdpClientOptions extends ClientOptions {

	/**
	 * Creates a builder for {@link ClientOptions ClientOptions}
	 *
	 * @return a new ClientOptions builder
	 */
	@SuppressWarnings("unchecked")
	public static UdpClientOptions.Builder builder() {
		return new Builder();
	}

	@Override
	protected boolean useDatagramChannel() {
		return true;
	}

	UdpClientOptions(UdpClientOptions.Builder builder) {
		super(builder);
	}

	@Override
	public UdpClientOptions duplicate() {
		return builder().from(this).build();
	}

	@Override
	public String toString() {
		return "UdpClientOptions{" + asDetailedString() + "}";
	}

	public static final class Builder extends ClientOptions.Builder<Builder> {

		private Builder() {
			super(new Bootstrap());
		}

		@Override
		public UdpClientOptions build() {
			super.build();
			return new UdpClientOptions(this);
		}
	}
}
