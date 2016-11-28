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

import java.util.function.BiFunction;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.cookie.Cookie;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyOutbound;

/**
 * An Http Reactive client write contract for outgoing requests. It inherits several accessor related to HTTP flow :
 * headers, params, URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 2.5
 */
public interface HttpOutbound extends HttpConnection, NettyOutbound {

	/**
	 * add an outbound cookie
	 *
	 * @return this outbound
	 */
	HttpOutbound addCookie(Cookie cookie);

	/**
	 * Add an outbound http header
	 *
	 * @param name header name
	 * @param value header value
	 *
	 * @return this outbound
	 */
	HttpOutbound addHeader(CharSequence name, CharSequence value);

	/**
	 * Set an outbound header
	 *
	 * @param name headers key
	 * @param value header value
	 *
	 * @return this outbound
	 */
	HttpOutbound header(CharSequence name, CharSequence value);

	/**
	 * set the request keepAlive if true otherwise remove the existing connection keep alive header
	 *
	 * @return this outbound
	 */
	HttpOutbound keepAlive(boolean keepAlive);

	/**
	 * Remove transfer-encoding: chunked header
	 *
	 * @return this outbound
	 */
	HttpOutbound disableChunkedTransfer();

	/**
	 * Set transfer-encoding header
	 *
	 * @param chunked true if transfer-encoding:chunked
	 *
	 * @return this outbound
	 */
	HttpOutbound chunkedTransfer(boolean chunked);

	/**
	 * Return  true if headers and status have been sent to the client
	 *
	 * @return true if headers and status have been sent to the client
	 */
	boolean hasSentHeaders();

	/**
	 * Return a {@link Mono} successful on committed response
	 *
	 * @return a {@link Mono} successful on committed response
	 */
	Mono<Void> sendHeaders();

	/**
	 * Collect all {@link io.netty.buffer.ByteBuf} and set the content-length if
	 * headers haven't been previously committed
	 *
	 * @param source the input source to send to the remote outbound.
	 *
	 * @return a {@link Mono} successful on committed response
	 * @see #send(Publisher)
	 */
	Mono<Void> sendAggregate(Publisher<? extends ByteBuf> source);



	/**
	 * Upgrade connection to Websocket
	 * @param websocketHandler the in/out handler for ws transport
	 * @return a {@link Mono} completing when upgrade is confirmed
	 */
	default Mono<Void> upgradeToWebsocket(BiFunction<? super HttpInbound, ? super HttpOutbound, ? extends Publisher<Void>> websocketHandler) {
		return upgradeToWebsocket(uri(), false, websocketHandler);
	}

	/**
	 * Upgrade connection to Websocket with text plain payloads
	 * @param websocketHandler the in/out handler for ws transport
	 * @return a {@link Mono} completing when upgrade is confirmed
	 */
	default Mono<Void> upgradeToTextWebsocket(BiFunction<? super HttpInbound, ? super HttpOutbound, ? extends Publisher<Void>> websocketHandler) {
		return upgradeToWebsocket(uri(), true, websocketHandler);
	}


	/**
	 * Upgrade connection to Websocket
	 * @param protocols
	 * @param textPlain
	 * @param websocketHandler the in/out handler for ws transport
	 *
	 * @return a {@link Mono} completing when upgrade is confirmed
	 */
	Mono<Void> upgradeToWebsocket(String protocols,
			boolean textPlain,
			BiFunction<? super HttpInbound, ? super HttpOutbound, ? extends Publisher<Void>> websocketHandler);
}
