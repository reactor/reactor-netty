/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.transport;

import io.netty.channel.unix.DomainSocketAddress;

import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;

/**
 * Internal utility class for UDS on Java 17.
 *
 * @author Violeta Georgieva
 * @since 1.2.0
 */
public final class DomainSocketAddressUtils {

	/**
	 * Returns whether the {@code SocketAddress} is {@code DomainSocketAddress} or {@code UnixDomainSocketAddress}.
	 *
	 * @param socketAddress the {@code SocketAddress} to test
	 * @return whether the {@code SocketAddress} is {@code DomainSocketAddress} or {@code UnixDomainSocketAddress}
	 */
	public static boolean isDomainSocketAddress(SocketAddress socketAddress) {
		return socketAddress instanceof DomainSocketAddress || socketAddress instanceof UnixDomainSocketAddress;
	}

	/**
	 * Returns the path to the domain socket.
	 *
	 * @param socketAddress the {@code SocketAddress} to test
	 * @return the path to the domain socket
	 */
	public static String path(SocketAddress socketAddress) {
		if (socketAddress instanceof DomainSocketAddress) {
			return ((DomainSocketAddress) socketAddress).path();
		}
		else if (socketAddress instanceof UnixDomainSocketAddress) {
			return ((UnixDomainSocketAddress) socketAddress).getPath().toString();
		}
		throw new IllegalArgumentException(socketAddress + " not supported");
	}

	private DomainSocketAddressUtils() {
	}
}
