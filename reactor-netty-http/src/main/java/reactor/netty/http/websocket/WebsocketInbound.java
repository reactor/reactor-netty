/*
 * Copyright (c) 2011-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.websocket;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.NettyInbound;
import reactor.netty.NettyPipeline;

/**
 * A websocket framed inbound.
 *
 * @author Stephane Maldini
 * @author Simon Baslé
 * @since 0.6
 */
public interface WebsocketInbound extends NettyInbound {

	/**
	 * Returns the websocket subprotocol negotiated by the client and server during
	 * the websocket handshake, or null if none was requested.
	 *
	 * @return the subprotocol, or null
	 */
	@Nullable String selectedSubprotocol();

	/**
	 * Returns the websocket remote headers sent during handshake.
	 *
	 * @return the websocket remote headers sent during handshake
	 */
	HttpHeaders headers();

	/**
	 * Receive the close status code and reason if sent by the remote peer,
	 * or empty if the connection completes otherwise.
	 * <p><strong>Note:</strong> Some close status codes are designated for use in applications expecting a status code
	 * to indicate that the connection was closed etc. They are not meant to be set as a status code in a
	 * Close control frame as such these status codes cannot be used with
	 * {@code reactor.netty.http.websocket.WebsocketOutbound#sendClose*} methods.
	 * Consider checking <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4.1">RFC 6455#section-7.4</a>
	 * for a complete list of the close status codes.
	 */
	Mono<WebSocketCloseStatus> receiveCloseStatus();

	/**
	 * Turn this {@link WebsocketInbound} into aggregating mode which will only produce
	 * fully formed frame that have been received fragmented.
	 *
	 * Will aggregate up to 65,536 bytes per frame
	 *
	 * @return this inbound
	 */
	default WebsocketInbound aggregateFrames() {
		return aggregateFrames(65_536);
	}

	/**
	 * Turn this {@link WebsocketInbound} into aggregating mode which will only produce
	 * fully formed frame that have been received fragmented.
	 *
	 * @param maxContentLength the maximum frame length
	 *
	 * @return this inbound
	 */
	default WebsocketInbound aggregateFrames(int maxContentLength) {
		withConnection(c -> c.addHandlerLast(NettyPipeline.WsFrameAggregator, new WebSocketFrameAggregator(maxContentLength)));
		return this;
	}

	/**
	 * Receive a {@link Flux} of {@link WebSocketFrame} formed frame content.
	 *
	 * @return a {@link Flux} of {@link WebSocketFrame} formed frame content
	 */
	default Flux<WebSocketFrame> receiveFrames() {
		return receiveObject().ofType(WebSocketFrame.class);
	}
}
