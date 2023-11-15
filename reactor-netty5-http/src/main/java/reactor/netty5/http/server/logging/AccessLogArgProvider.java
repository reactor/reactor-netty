/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.server.logging;

import io.netty5.handler.codec.http.headers.HttpCookiePair;
import reactor.netty5.http.server.ConnectionInformation;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A provider of the args required for access log.
 *
 * @author limaoning
 * @since 1.0.1
 */
public interface AccessLogArgProvider {

	/**
	 * Returns the date-time of the moment when the request was received.
	 *
	 * @return zoned date-time
	 * @since 1.0.6
	 */
	@Nullable
	ZonedDateTime accessDateTime();

	/**
	 * Returns the address of the remote peer or {@code null} in case of Unix Domain Sockets.
	 *
	 * @return the peer's address
	 * @deprecated as of 1.0.26. Use {@link ConnectionInformation#connectionRemoteAddress()}
	 */
	@Nullable
	@Deprecated
	SocketAddress remoteAddress();

	/**
	 * Returns the information about the current connection.
	 * <p> Note that the {@link ConnectionInformation#remoteAddress()} will return the forwarded
	 * remote client address if the server is configured in forwarded mode.
	 *
	 * @return the connection info
	 * @since 1.0.26
	 * @see reactor.netty5.http.server.HttpServer#forwarded(BiFunction)
	 */
	@Nullable
	ConnectionInformation connectionInformation();

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
	 * Returns the value of a request header with the specified name
	 * or {@code null} is case such request header does not exist.
	 *
	 * @param name the request header name
	 * @return the value of the request header
	 */
	@Nullable
	CharSequence requestHeader(CharSequence name);

	/**
	 * Returns the value of a response header with the specified name
	 * or {@code null} is case such response header does not exist.
	 *
	 * @param name the response header name
	 * @return the value of the response header
	 * @since 1.0.4
	 */
	@Nullable
	CharSequence responseHeader(CharSequence name);

	/**
	 * Returns resolved HTTP cookies.
	 * <p>
	 * Warning: Be cautious with cookies information and what kind of sensitive data is written to the logs.
	 * By default, no cookies information is written to the access log.
	 *
	 * @return Resolved HTTP cookies
	 * @since 1.0.6
	 */
	@Nullable
	Map<CharSequence, Set<HttpCookiePair>> cookies();
}