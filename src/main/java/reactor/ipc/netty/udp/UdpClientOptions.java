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

import java.net.InetSocketAddress;
import javax.annotation.Nonnull;

import reactor.ipc.netty.options.ClientOptions;

/**
 * @author Stephane Maldini
 */
final class UdpClientOptions extends ClientOptions {

	@Override
	protected boolean useDatagramChannel() {
		return true;
	}

	UdpClientOptions() {
	}

	UdpClientOptions(ClientOptions options) {
		super(options);
	}

	@Override
	public UdpClientOptions duplicate() {
		return new UdpClientOptions(this);
	}

	@Override
	public ClientOptions connect(int port) {
		return connect(new InetSocketAddress(port));
	}

	@Override
	public ClientOptions connect(@Nonnull String host, int port) {
		return connect(new InetSocketAddress(host, port));
	}

	@Override
	public String toString() {
		return "UdpClientOptions{" + asDetailedString() + "}";
	}
}
