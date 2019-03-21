/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty.udp;

import io.netty.bootstrap.Bootstrap;
import reactor.ipc.netty.options.ClientOptions;

/**
 * @author Violeta Georgieva
 */
final class UdpServerOptions extends ClientOptions {

	/**
	 * Creates a builder for {@link UdpServerOptions UdpServerOptions}
	 *
	 * @return a new UdpServerOptions builder
	 */
	@SuppressWarnings("unchecked")
	public static UdpServerOptions.Builder builder() {
		return new Builder();
	}

	@Override
	protected boolean useDatagramChannel() {
		return true;
	}

	UdpServerOptions(UdpServerOptions.Builder builder) {
		super(builder);
	}

	@Override
	public UdpServerOptions duplicate() {
		return builder().from(this).build();
	}

	@Override
	public String toString() {
		return "UdpServerOptions{" + asDetailedString() + "}";
	}

	public static final class Builder extends ClientOptions.Builder<Builder> {

		private Builder() {
			super(new Bootstrap());
		}

		@Override
		public UdpServerOptions build() {
			super.build();
			return new UdpServerOptions(this);
		}
	}

}
