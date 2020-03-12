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
package reactor.netty.http;

import java.util.Map;
import java.util.Set;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.Cookie;

/**
 * An Http Reactive Channel with several accessors related to HTTP flow: headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 0.5
 */
public interface HttpInfos {

	/**
	 * Returns resolved HTTP cookies
	 *
	 * @return Resolved HTTP cookies
	 */
	Map<CharSequence, Set<Cookie>> cookies();


	/**
	 * Is the request keep alive
	 *
	 * @return is keep alive
	 */
	boolean isKeepAlive();

	/**
	 * Returns true if websocket connection (upgraded)
	 *
	 * @return true if websocket connection
	 */
	boolean isWebsocket();

	/**
	 * Returns the resolved request method (HTTP 1.1 etc)
	 *
	 * @return the resolved request method (HTTP 1.1 etc)
	 */
	HttpMethod method();

	/**
	 * Returns the decoded path portion from the {@link #uri()} without the leading and trailing '/' if present
	 *
	 * @return the decoded path portion from the {@link #uri()} without the leading and trailing '/' if present
	 */
	default String path() {
		String path = fullPath();
		if (!path.isEmpty()) {
			if (path.charAt(0) == '/') {
				path = path.substring(1);
				if (path.isEmpty()) {
					return path;
				}
			}
			if (path.charAt(path.length() - 1) == '/') {
				return path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	/**
	 * Returns the decoded path portion from the {@link #uri()}
	 *
	 * @return the decoded path portion from the {@link #uri()}
	 * @since 0.9.6
	 */
	String fullPath();

	/**
	 * Returns the resolved target address
	 *
	 * @return the resolved target address
	 */
	String uri();

	/**
	 * Returns the resolved request version (HTTP 1.1 etc)
	 *
	 * @return the resolved request version (HTTP 1.1 etc)
	 */
	HttpVersion version();

}
