/*
 * Copyright (c) 2022-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import reactor.util.annotation.Nullable;

import java.net.SocketAddress;

/**
 * Resolve information about the connection from which an http request is received.
 *
 * @since 1.0.26
 */
public interface ConnectionInformation {

	/**
	 * Returns the address of the host which received the request, possibly {@code null} in case of Unix Domain Sockets.
	 * The returned address is the merged information from all proxies.
	 *
	 * @return the address merged from all proxies of the host which received the request
	 */
	@Nullable
	SocketAddress hostAddress();

	/**
	 * Returns the address of the host which received the request, possibly {@code null} in case of Unix Domain Sockets.
	 *
	 * @return the address of the host which received the request
	 */
	@Nullable
	SocketAddress connectionHostAddress();

	/**
	 * Returns the address of the client that initiated the request, possibly {@code null} in case of Unix Domain Sockets.
	 * The returned address is the merged information from all proxies.
	 *
	 * @return the address merged from all proxies of the client that initiated the request
	 */
	@Nullable
	SocketAddress remoteAddress();

	/**
	 * Returns the address of the client that initiated the request, possibly {@code null} in case of Unix Domain Sockets.
	 *
	 * @return the address of the client that initiated the request
	 */
	@Nullable
	SocketAddress connectionRemoteAddress();

	/**
	 * Returns the current protocol scheme.
	 * The returned address is the merged information from all proxies.
	 *
	 * @return the protocol scheme merged from all proxies
	 */
	String scheme();

	/**
	 * Returns the current protocol scheme.
	 *
	 * @return the protocol scheme
	 */
	String connectionScheme();

	/**
	 * Returns the host name derived from the {@code Host}/{@code X-Forwarded-Host}/{@code Forwarded} header
	 * associated with this request.
	 *
	 * @return the host name derived from the {@code Host}/{@code X-Forwarded-Host}/{@code Forwarded} header
	 * associated with this request.
	 * @since 1.0.29
	 */
	String hostName();

	/**
	 * Returns the host port derived from the {@code Host}/{@code X-Forwarded-*}/{@code Forwarded} header
	 * associated with this request.
	 *
	 * @return the host port derived from the {@code Host}/{@code X-Forwarded-*}/{@code Forwarded} header
	 * associated with this request.
	 * @since 1.0.29
	 */
	int hostPort();
}
