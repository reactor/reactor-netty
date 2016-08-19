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

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.common.NettyOutbound;

/**
 * An Http Reactive client write contract for outgoing requests. It inherits several accessor related to HTTP flow :
 * headers, params, URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 2.5
 */
public interface HttpOutbound extends HttpConnection, NettyOutbound {

	/**
	 * add the passed cookie
	 *
	 * @return this
	 */
	HttpOutbound addCookie(Cookie cookie);

	/**
	 * @param name
	 * @param value
	 *
	 * @return
	 */
	HttpOutbound addHeader(CharSequence name, CharSequence value);

	@Override
	HttpOutbound flushEach();

	/**
	 * @param name
	 * @param value
	 *
	 * @return
	 */
	HttpOutbound header(CharSequence name, CharSequence value);

	/**
	 * @return Resolved HTTP request headers
	 */
	HttpHeaders headers();

	/**
	 * set the request keepAlive if true otherwise remove the existing connection keep alive header
	 *
	 * @return is keep alive
	 */
	HttpOutbound keepAlive(boolean keepAlive);

	/**
	 *
	 */
	HttpOutbound removeTransferEncodingChunked();

	/**
	 * @return
	 */
	Mono<Void> sendHeaders();



	/**
	 * Upgrade connection to Websocket
	 * @return a {@link Mono} completing when upgrade is confirmed
	 */
	default Mono<Void> upgradeToWebsocket() {
		return upgradeToWebsocket(null, false);
	}

	/**
	 * Upgrade connection to Websocket with text plain payloads
	 * @return a {@link Mono} completing when upgrade is confirmed
	 */
	default Mono<Void> upgradeToTextWebsocket() {
		return upgradeToWebsocket(null, true);
	}


	/**
	 * Upgrade connection to Websocket
	 * @param protocols
	 * @param textPlain
	 *
	 * @return a {@link Mono} completing when upgrade is confirmed
	 */
	Mono<Void> upgradeToWebsocket(String protocols, boolean textPlain);
}
