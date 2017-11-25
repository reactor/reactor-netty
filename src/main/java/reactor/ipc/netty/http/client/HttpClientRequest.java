/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.client;

import javax.annotation.Nullable;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.http.HttpInfos;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;

import java.util.function.Consumer;

/**
 * An Http Reactive client write contract for outgoing requests. It inherits several
 * accessor related to HTTP flow : headers, params, URI, method, websocket...
 *
 * @author Stephane Maldini
 * @author Simon Baslé
 */
public interface HttpClientRequest extends NettyOutbound, HttpInfos {

	/**
	 * Add an outbound cookie
	 *
	 * @return this outbound
	 */
	HttpClientRequest addCookie(Cookie cookie);

	@Override
	HttpClientRequest withConnection(Consumer<? super Connection> withConnection);

	/**
	 * Add an outbound http header, appending the value if the header is already set.
	 *
	 * @param name header name
	 * @param value header value
	 *
	 * @return this outbound
	 */
	HttpClientRequest addHeader(CharSequence name, CharSequence value);

	/**
	 * Set transfer-encoding header
	 *
	 * @param chunked true if transfer-encoding:chunked
	 *
	 * @return this outbound
	 */
	HttpClientRequest chunkedTransfer(boolean chunked);

	@Override
	default HttpClientRequest options(Consumer<? super NettyPipeline.SendOptions> configurator){
		NettyOutbound.super.options(configurator);
		return this;
	}

	/**
	 * Enable http status 302 auto-redirect support
	 *
	 * @return {@literal this}
	 */
	HttpClientRequest followRedirect();

	/**
	 * Return  true if headers and status have been sent to the client
	 *
	 * @return true if headers and status have been sent to the client
	 */
	boolean hasSentHeaders();

	/**
	 * Set an outbound header, replacing any pre-existing value.
	 *
	 * @param name headers key
	 * @param value header value
	 *
	 * @return this outbound
	 */
	HttpClientRequest header(CharSequence name, CharSequence value);

	/**
	 * Set outbound headers from the passed headers. It will however ignore {@code
	 * HOST} header key. Any pre-existing value for the passed headers will be replaced.
	 *
	 * @param headers a netty headers map
	 *
	 * @return this outbound
	 */
	HttpClientRequest headers(HttpHeaders headers);

	/**
	 * Return true  if redirected will be followed
	 *
	 * @return true if redirected will be followed
	 */
	boolean isFollowRedirect();

	/**
	 * set the request keepAlive if true otherwise remove the existing connection keep alive header
	 *
	 * @return this outbound
	 */
	HttpClientRequest keepAlive(boolean keepAlive);

	@Override
	default HttpClientRequest onWriteIdle(long idleTimeout, Runnable onWriteIdle){
		NettyOutbound.super.onWriteIdle(idleTimeout, onWriteIdle);
		return this;
	}

	/**
	 * Return the previous redirections or empty array
	 *
	 * @return the previous redirections or empty array
	 */
	String[] redirectedFrom();

	/**
	 * Return outbound headers to be sent
	 *
	 * @return outbound headers to be sent
	 */
	HttpHeaders requestHeaders();

	/**
	 * Send headers and empty content thus delimiting a full empty body http request
	 *
	 * @return a {@link Mono} successful on committed response
	 *
	 * @see #sendObject(Object)
	 */
	default Mono<Void> send() {
		return sendObject(Unpooled.EMPTY_BUFFER).then();
	}

	/**
	 * Prepare to send an HTTP Form including Multipart encoded Form which support
	 * chunked file upload. It will by default be encoded as Multipart but can be
	 * adapted via {@link HttpClientForm#multipart(boolean)}.
	 *
	 * @param formCallback called when form generator is created
	 *
	 * @return a {@link Flux} of latest in-flight or uploaded bytes,
	 */
	Flux<Long> sendForm(Consumer<HttpClientForm> formCallback);

	/**
	 * Send the headers.
	 *
	 * @return a {@link NettyOutbound} completing when headers have been sent.
	 */
	NettyOutbound sendHeaders();

	/**
	 * Upgrade connection to Websocket.
	 *
	 * @return a {@link WebsocketOutbound} completing when upgrade is confirmed
	 */
	WebsocketOutbound sendWebsocket();

	/**
	 * Upgrade connection to Websocket, negotiating one of the given subprotocol(s).
	 * <p>
	 * The negotiated subprotocol cannot be directly accessed on the returned outbound,
	 * as the negotiation hasn't yet occurred. However, the response to this request will
	 * usually allow access to that information (by upgrading it to a websocket outbound
	 * via {@link HttpClientResponse#receiveWebsocket()} then calling
	 * {@link WebsocketOutbound#selectedSubprotocol()}).
	 *
	 * @param subprotocols the subprotocol(s) to negotiate, comma-separated, or null if not relevant.
	 * Can be several protocols, separated by a comma, or null if no subprotocol is required.
	 * @return a {@link WebsocketOutbound} completing when upgrade is confirmed
	 */
	WebsocketOutbound sendWebsocket(@Nullable String subprotocols);
}
