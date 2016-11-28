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
package reactor.ipc.netty.http.server;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.Cookie;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.HttpOutbound;

/**
 *
 * An Http Reactive Channel with several accessor related to HTTP flow : headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 2.5
 */
public interface HttpServerResponse extends HttpOutbound {

	@Override
	HttpServerResponse addCookie(Cookie cookie);

	@Override
	HttpServerResponse addHeader(CharSequence name, CharSequence value);

	@Override
	HttpServerResponse chunkedTransfer(boolean chunked);

	@Override
	HttpServerResponse disableChunkedTransfer();

	@Override
	HttpServerResponse flushEach();

	@Override
	HttpServerResponse header(CharSequence name, CharSequence value);

	@Override
	HttpServerResponse keepAlive(boolean keepAlive);

	/**
	 * Send redirect status {@link HttpResponseStatus#FOUND} along with a location
	 * header to the remote client.
	 *
	 * @param location the location to redirect to
	 *
	 * @return a {@link Mono} successful on flush confirmation
	 */
	Mono<Void> sendRedirect(String location);

	/**
	 *
	 * @return a
	 */
	Mono<Void> sendNotFound();

	/**
	 * Return headers sent back to the clients
	 * @return headers sent back to the clients
	 */
	HttpHeaders responseHeaders();

	/**
	 * Add Server-Side-Event content-type
	 * @return this response
	 */
	HttpServerResponse sse();

	/**
	 * Return the assigned HTTP status
	 * @return the assigned HTTP status
	 */
	HttpResponseStatus status();

	/**
	 * Set an HTTP status to be sent along with the headers
	 * @param status an HTTP status to be sent along with the headers
	 * @return this response
	 */
	HttpServerResponse status(HttpResponseStatus status);

	/**
	 * Set an HTTP status to be sent along with the headers
	 * @param status an HTTP status to be sent along with the headers
	 * @return this response
	 */
	default HttpServerResponse status(int status){
		return status(HttpResponseStatus.valueOf(status));
	}


}
