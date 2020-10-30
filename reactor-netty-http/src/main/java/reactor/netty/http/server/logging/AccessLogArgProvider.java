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

/**
 * A provider of the args required for access log.
 *
 * @author limaoning
 */
public interface AccessLogArgProvider {

	/**
	 * Returns the date-time string with a time-zone, (e.g. "30/Oct/2020:03:52:11 +0000").
	 *
	 * @return the date-time string with a time-zone
	 */
	String zonedDateTime();

	/**
	 * Returns the remote hostname.
	 *
	 * @return the remote hostname
	 */
	String address();

	/**
	 * Returns the remote port.
	 *
	 * @return the remote port
	 */
	int port();

	/**
	 * Returns the name of this method, (e.g. "GET").
	 *
	 * @return the name of this method
	 */
	CharSequence method();

	/**
	 * Returns the requested URI, (e.g. "/hello").
	 *
	 * @return the requested URI
	 */
	CharSequence uri();

	/**
	 * Returns the protocol version, (e.g. "HTTP/1.1" or "HTTP/2.0").
	 *
	 * @return the protocol version
	 */
	String protocol();

	/**
	 * Returns the user identifier.
	 *
	 * @return the user identifier
	 */
	String user();

	/**
	 * Returns the response status, (e.g. 200).
	 *
	 * @return the response status
	 */
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
	 * Returns the value of a header with the specified name.
	 *
	 * @param name the header name
	 * @return the value of the header
	 */
	CharSequence header(CharSequence name);

}
