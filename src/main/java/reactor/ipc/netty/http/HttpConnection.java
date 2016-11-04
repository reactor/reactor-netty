/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty.http;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.Cookie;

/**
 *
 * An Http Reactive Channel with several accessor related to HTTP flow : headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 2.5
 */
public interface HttpConnection {

	/**
	 * @return Resolved HTTP cookies
	 */
	Map<CharSequence, Set<Cookie>> cookies();


	/**
	 * Is the request keepAlive
	 * @return is keep alive
	 */
	boolean isKeepAlive();

	/**
	 *
	 * @return
	 */
	boolean isWebsocket();

	/**
	 * @return the resolved request method (HTTP 1.1 etc)
	 */
	HttpMethod method();

	/**
	 * Get the address of the remote peer.
	 *
	 * @return the peer's address
	 */
	InetSocketAddress remoteAddress();

	/**
	 * @return the resolved target address
	 */
	String uri();

	/**
	 * @return the resolved request version (HTTP 1.1 etc)
	 */
	HttpVersion version();

}
