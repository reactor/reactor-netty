/*
 * Copyright (c) 2017-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Holds contextual information for the underlying server.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 * @since 0.8
 */
public interface DisposableServer extends DisposableChannel {

	@Override
	default SocketAddress address() {
		return channel().localAddress();
	}

	/**
	 * Returns the server's host string. That is, the hostname or in case the server was bound
	 * to a literal IP address, the IP string representation (rather than performing a reverse-DNS
	 * lookup).
	 *
	 * @return the host string, without reverse DNS lookup
	 * @throws UnsupportedOperationException when Unix Domain Sockets
	 * @see DisposableChannel#address()
	 * @see InetSocketAddress#getHostString()
	 */
	default String host() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the server's path to the domain socket.
	 * Returns {@code null} in case it is not Unix Domain Sockets.
	 *
	 * @return the path to the domain socket
	 * @throws UnsupportedOperationException when it is not Unix Domain Sockets
	 * @since 1.0.0
	 */
	default String path() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns this server's port.
	 *
	 * @return The port the server is bound to.
	 * @throws UnsupportedOperationException when Unix Domain Sockets
	 */
	default int port() {
		throw new UnsupportedOperationException();
	}
}