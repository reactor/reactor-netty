/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty;

import java.net.InetSocketAddress;

import reactor.core.publisher.Flux;

/**
 * Hold contextual information for the underlying server
 *
 * @author Stephane Maldini
 * @since 0.8
 */
public interface DisposableServer extends DisposableChannel {

	/**
	 * Return the {@link Flux} of remote {@link Connection}. The {@link Flux}
	 * subscriber must keep up with demand or will be receive an onError signal.
	 * {@link Flux} will emit onComplete if the server is disposed.
	 *
	 * @return a hot {@link Flux} of server {@link Connection}
	 */
	Flux<Connection> connections();

	/**
	 * Return the server's host String. That is, the hostname or in case the server was bound
	 * to a literal IP adress, the IP string representation (rather than performing a reverse-DNS
	 * lookup).
	 *
	 * @return the host string, without reverse DNS lookup
	 * @see DisposableChannel#address()
	 * @see InetSocketAddress#getHostString()
	 */
	default String host() {
		return address().getHostName();
	}

	/**
	 * Return this server's port.
	 * @return The port the server is bound to.
	 */
	default int port() {
		return address().getPort();
	}
}