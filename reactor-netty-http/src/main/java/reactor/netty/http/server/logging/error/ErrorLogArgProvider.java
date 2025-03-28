/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server.logging.error;

import reactor.netty.http.server.ConnectionInformation;
import reactor.netty.http.server.HttpServerInfos;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.ZonedDateTime;
import java.util.function.BiFunction;

/**
 * Provides information for logging errors that occur on the HTTP Server.
 *
 * @author raccoonback
 */
public interface ErrorLogArgProvider {

	/**
	 * Returns the date-time of the moment when the exception occurred.
	 *
	 * @return zoned date-time
	 */
	ZonedDateTime errorDateTime();

	/**
	 * Returns the address of the remote peer in case of Unix Domain Sockets.
	 *
	 * @return the peer's address
	 */
	SocketAddress remoteAddress();

	/**
	 * Returns information about the HTTP server-side connection information.
	 * <p> Note that the {@link ConnectionInformation#remoteAddress()} will return the forwarded
	 * remote client address if the server is configured in forwarded mode.
	 *
	 * @return HTTP server-side connection information
	 * @see reactor.netty.http.server.HttpServer#forwarded(BiFunction)
	 */
	@Nullable
	HttpServerInfos httpServerInfos();

	/**
	 * Returns the exception that occurred.
	 *
	 * @return exception
	 */
	Throwable cause();
}
