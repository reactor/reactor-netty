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

package reactor.netty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;

import io.netty.bootstrap.AbstractBootstrap;
import reactor.util.annotation.Nullable;

/**
 * Represents a failing attempt to bind a local socket address
 *
 * @author Stephane Maldini
 */
public class ChannelBindException extends RuntimeException {

	/**
	 * Build a {@link ChannelBindException}
	 *
	 * @param bootstrap a netty bootstrap
	 * @param cause a root cause
	 *
	 * @return a new {@link ChannelBindException}
	 */
	public static ChannelBindException fail(AbstractBootstrap<?, ?> bootstrap, @Nullable Throwable cause) {
		Objects.requireNonNull(bootstrap, "bootstrap");
		if (cause instanceof java.net.BindException ||
				// With epoll/kqueue transport it is
				// io.netty.channel.unix.Errors$NativeIoException: bind(..) failed: Address already in use
				(cause instanceof IOException && cause.getMessage() != null &&
						cause.getMessage().contains("Address already in use"))) {
			cause = null;
		}
		if (!(bootstrap.config().localAddress() instanceof InetSocketAddress)) {
			return new ChannelBindException(bootstrap.config().localAddress().toString(), -1, cause);
		}
		InetSocketAddress address = (InetSocketAddress)bootstrap.config().localAddress();

		return new ChannelBindException(address.getHostString(), address.getPort(), cause);
	}

	final String localHost;
	final int    localPort;

	protected ChannelBindException(String localHost, int localPort, @Nullable Throwable cause) {
		super("Failed to bind on ["+localHost+":"+localPort+"]", cause);
		this.localHost = localHost;
		this.localPort = localPort;
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return this;
	}

	/**
	 * Return the configured binding host
	 *
	 * @return the configured binding host
	 */
	public String localHost() {
		return localHost;
	}

	/**
	 * Return the configured binding port
	 *
	 * @return the configured local binding port
	 */
	public int localPort() {
		return localPort;
	}
}
