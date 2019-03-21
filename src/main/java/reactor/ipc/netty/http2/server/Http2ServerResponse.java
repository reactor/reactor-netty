/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty.http2.server;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.Http2Headers;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.http.HttpInfos;
import reactor.ipc.netty.http2.Http2StreamOutbound;

import java.util.function.Consumer;

/**
 *
 * An HTTP/2 Reactive Channel with several accessor related to HTTP flow : headers, params,
 * URI, method ...
 *
 * @author Violeta Georgieva
 */
public interface Http2ServerResponse extends Http2StreamOutbound {

	/**
	 * Add an outbound http header, appending the value if the header already exist.
	 *
	 * @param name header name
	 * @param value header value
	 *
	 * @return this outbound
	 */
	Http2ServerResponse addHeader(CharSequence name, CharSequence value);

	@Override
	Http2ServerResponse withConnection(Consumer<? super Connection> withConnection);

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
	Http2ServerResponse header(CharSequence name, CharSequence value);

	/**
	 * Set outbound headers, replacing any pre-existing value for these headers.
	 *
	 * @param headers netty headers map
	 *
	 * @return this outbound
	 */
	Http2ServerResponse headers(Http2Headers headers);

	@Override
	default Http2ServerResponse options(Consumer<? super NettyPipeline.SendOptions> configurator){
		Http2StreamOutbound.super.options(configurator);
		return this;
	}

	/**
	 * Return headers sent back to the clients
	 * @return headers sent back to the clients
	 */
	Http2Headers responseHeaders();

	/**
	 * Send headers and empty content thus delimiting a full empty body http response.
	 *
	 * @return a {@link Mono} successful on committed response
	 * @see #send(Publisher)
	 */
	default Mono<Void> send(){
		return sendObject(Unpooled.EMPTY_BUFFER).then();
	}

	/**
	 * Return a {@link NettyOutbound} successful on committed response
	 *
	 * @return a {@link NettyOutbound} successful on committed response
	 */
	NettyOutbound sendHeaders();

	/**
	 * Send 404 status {@link HttpResponseStatus#NOT_FOUND}.
	 *
	 * @return a {@link Mono} successful on flush confirmation
	 */
	Mono<Void> sendNotFound();

	/**
	 * Return the assigned HTTP status
	 * @return the assigned HTTP status
	 */
	CharSequence status();

	/**
	 * Set an HTTP status to be sent along with the headers
	 * @param status an HTTP status to be sent along with the headers
	 * @return this response
	 */
	Http2ServerResponse status(CharSequence status);

	/**
	 * Set an HTTP status to be sent along with the headers
	 * @param status an HTTP status to be sent along with the headers
	 * @return this response
	 */
	default Http2ServerResponse status(int status){
		return status(HttpResponseStatus.valueOf(status).codeAsText());
	}


}
