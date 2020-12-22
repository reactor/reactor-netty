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
package reactor.netty.http.server.logging;

import reactor.util.annotation.Nullable;

import java.net.SocketAddress;

/**
 * A provider of the args required for access log.
 *
 * @author limaoning
 * @since 1.0.1
 */
public interface AccessLogArgProvider {

	/**
	 * Returns the date-time string with a time-zone, (e.g. "30/Oct/2020:03:52:11 +0000").
	 *
	 * @return the date-time string with a time-zone
	 */
	@Nullable
	String zonedDateTime();

	/**
	 * Returns the address of the remote peer or {@code null} in case of Unix Domain Sockets.
	 *
	 * @return the peer's address
	 */
	@Nullable
	SocketAddress remoteAddress();

	/**
	 * Returns the name of this method, (e.g. "GET").
	 *
	 * @return the name of this method
	 */
	@Nullable
	CharSequence method();

	/**
	 * Returns the requested URI, (e.g. "/hello").
	 *
	 * @return the requested URI
	 */
	@Nullable
	CharSequence uri();

	/**
	 * Returns the protocol version, (e.g. "HTTP/1.1" or "HTTP/2.0").
	 *
	 * @return the protocol version
	 */
	@Nullable
	String protocol();

	/**
	 * Returns the user identifier.
	 *
	 * @return the user identifier
	 */
	@Nullable
	String user();

	/**
	 * Returns the response status, (e.g. 200).
	 *
	 * @return the response status
	 */
	@Nullable
	CharSequence status();

	/**
	 * Returns the response content length.
	 *
	 * @return the response content length
	 */
	long contentLength();

	/**
	 * Returns the request/response duration.
	 *
	 * @return the request/response duration in milliseconds
	 */
	long duration();

	/**
	 * Returns the value of a request header with the specified name.
	 *
	 * @param name the request header name
	 * @return the value of the request header
	 */
	@Nullable
	CharSequence requestHeader(CharSequence name);

}
