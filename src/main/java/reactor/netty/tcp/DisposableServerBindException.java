/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
package reactor.netty.tcp;

import java.net.SocketAddress;

/**
 * An extended {@link java.net.BindException} that provides information
 * for the local address.
 *
 * @author Violeta Georgieva
 */
public class DisposableServerBindException extends RuntimeException {
	final SocketAddress localAddress;

	DisposableServerBindException(String message, SocketAddress localAddress) {
		super(message);
		this.localAddress = localAddress;
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return this;
	}

	public SocketAddress getLocalAddress() {
		return localAddress;
	}
}
