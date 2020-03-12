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
package reactor.netty.http.server;

import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.Cookie;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.NettyOutbound;
import reactor.netty.http.HttpInfos;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;

/**
 *
 * An Http Reactive Channel with several accessors related to HTTP flow: headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 0.5
 */
public interface HttpServerResponse extends NettyOutbound, HttpInfos {

	/**
	 * Adds an outbound cookie
	 *
	 * @return this {@link HttpServerResponse}
	 */
	HttpServerResponse addCookie(Cookie cookie);

	/**
	 * Adds an outbound HTTP header, appending the value if the header already exist.
	 *
	 * @param name header name
	 * @param value header value
	 *
	 * @return this {@link HttpServerResponse}
	 */
	HttpServerResponse addHeader(CharSequence name, CharSequence value);

	/**
	 * Sets Transfer-Encoding header
	 *
	 * @param chunked true if Transfer-Encoding: chunked
	 *
	 * @return this {@link HttpServerResponse}
	 */
	HttpServerResponse chunkedTransfer(boolean chunked);

	@Override
	HttpServerResponse withConnection(Consumer<? super Connection> withConnection);

	/**
	 * Enables/Disables compression handling (gzip/deflate) for the underlying response
	 *
	 * @param compress should handle compression
	 *
	 * @return this {@link HttpServerResponse}
	 */
	HttpServerResponse compression(boolean compress);

	/**
	 * Returns true if headers and status have been sent to the client
	 *
	 * @return true if headers and status have been sent to the client
	 */
	boolean hasSentHeaders();

	/**
	 * Sets an outbound HTTP header, replacing any pre-existing value.
	 *
	 * @param name headers key
	 * @param value header value
	 *
	 * @return this {@link HttpServerResponse}
	 */
	HttpServerResponse header(CharSequence name, CharSequence value);

	/**
	 * Sets outbound HTTP headers, replacing any pre-existing value for these headers.
	 *
	 * @param headers netty headers map
	 *
	 * @return this {@link HttpServerResponse}
	 */
	HttpServerResponse headers(HttpHeaders headers);

	/**
	 * Sets the request {@code keepAlive} if true otherwise remove the existing connection keep alive header
	 *
	 * @return this {@link HttpServerResponse}
	 */
	HttpServerResponse keepAlive(boolean keepAlive);

	/**
	 * Returns the outbound HTTP headers, sent back to the clients
	 *
	 * @return headers sent back to the clients
	 */
	HttpHeaders responseHeaders();

	/**
	 * Sends the HTTP headers and empty content thus delimiting a full empty body http response.
	 *
	 * @return a {@link Mono} successful on committed response
	 * @see #send(Publisher)
	 */
	Mono<Void> send();

	/**
	 * Returns a {@link NettyOutbound} successful on committed response
	 *
	 * @return a {@link NettyOutbound} successful on committed response
	 */
	NettyOutbound sendHeaders();

	/**
	 * Sends 404 status {@link HttpResponseStatus#NOT_FOUND}.
	 *
	 * @return a {@link Mono} successful on flush confirmation
	 */
	Mono<Void> sendNotFound();

	/**
	 * Sends redirect status {@link HttpResponseStatus#FOUND} along with a location
	 * header to the remote client.
	 *
	 * @param location the location to redirect to
	 *
	 * @return a {@link Mono} successful on flush confirmation
	 */
	Mono<Void> sendRedirect(String location);

	/**
	 * Upgrades the connection to websocket. A {@link Mono} completing when the upgrade
	 * is confirmed, then the provided callback is invoked, if the upgrade is not
	 * successful the returned {@link Mono} fails.
	 *
	 * @param websocketHandler the I/O handler for websocket transport
	 * @return a {@link Mono} completing when upgrade is confirmed, otherwise fails
	 */
	default Mono<Void> sendWebsocket(BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		return sendWebsocket(websocketHandler, WebsocketServerSpec.builder().build());
	}

	/**
	 * Upgrades the connection to websocket. A {@link Mono} completing when the upgrade
	 * is confirmed, then the provided callback is invoked, if the upgrade is not
	 * successful the returned {@link Mono} fails.
	 *
	 * @param websocketHandler the I/O handler for websocket transport
	 * @param websocketServerSpec {@link WebsocketServerSpec} for websocket configuration
	 * @return a {@link Mono} completing when upgrade is confirmed, otherwise fails
	 * @since 0.9.5
	 */
	Mono<Void> sendWebsocket(
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler,
			WebsocketServerSpec websocketServerSpec);

	/**
	 * Adds "text/event-stream" content-type for Server-Sent Events
	 *
	 * @return this {@link HttpServerResponse}
	 */
	HttpServerResponse sse();

	/**
	 * Returns the assigned HTTP status
	 *
	 * @return the assigned HTTP status
	 */
	HttpResponseStatus status();

	/**
	 * Sets an HTTP status to be sent along with the headers
	 *
	 * @param status an HTTP status to be sent along with the headers
	 * @return this {@link HttpServerResponse}
	 */
	HttpServerResponse status(HttpResponseStatus status);

	/**
	 * Sets an HTTP status to be sent along with the headers
	 *
	 * @param status an HTTP status to be sent along with the headers
	 * @return this {@link HttpServerResponse}
	 */
	default HttpServerResponse status(int status){
		return status(HttpResponseStatus.valueOf(status));
	}


}
