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

package reactor.ipc.netty.http.client;

import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.http.HttpInfos;
import reactor.ipc.netty.http.multipart.MultipartInbound;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;

/**
 * An HttpClient Reactive read contract for incoming response. It inherits several
 * accessor
 * related to HTTP
 * flow : headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 0.5
 */
public interface HttpClientResponse extends NettyInbound, HttpInfos, NettyContext {

	@Override
	default HttpClientResponse addEncoder(ChannelHandler handler) {
		NettyContext.super.addEncoder(handler);
		return this;
	}

	@Override
	HttpClientResponse addEncoder(String name, ChannelHandler handler);

	@Override
	default HttpClientResponse addDecoder(ChannelHandler handler) {
		return addDecoder(handler.getClass().getSimpleName(), handler);
	}

	@Override
	HttpClientResponse addDecoder(String name, ChannelHandler handler);

	@Override
	HttpClientResponse onClose(Runnable onClose);

	@Override
	default HttpClientResponse onReadIdle(long idleTimeout, Runnable onReadIdle) {
		NettyInbound.super.onReadIdle(idleTimeout, onReadIdle);
		return this;
	}

	/**
	 * Return a {@link Flux} of {@link HttpContent} containing received chunks
	 *
	 * @return a {@link Flux} of {@link HttpContent} containing received chunks
	 */
	default Flux<HttpContent> receiveContent(){
		return receiveObject().ofType(HttpContent.class);
	}

	/**
	 * Return a decoded {@link MultipartInbound}
	 *
	 * @return a decoded {@link MultipartInbound}
	 */
	default MultipartInbound receiveMultipart() {
		HttpClientResponse thiz = this;
		return new MultipartInbound() {

			@Override
			public NettyContext context() {
				return thiz.context();
			}

			@Override
			public Flux<ByteBufFlux> receiveParts() {
				return MultipartInbound.from(thiz);
			}
		};
	}

	/**
	 * Unidirectional conversion to a {@link WebsocketInbound}.
	 * receive operations are
	 * invoked on handshake
	 * success,
	 * otherwise the returned {@link WebsocketInbound} fail or wasn't upgraded by the
	 * server.
	 *
	 * @return a {@link WebsocketInbound} completing when upgrade is confirmed
	 */
	WebsocketInbound receiveWebsocket();

	/**
	 * Duplex conversion to {@link WebsocketInbound}, {@link WebsocketOutbound} and a
	 * closing {@link Publisher}. Mono and Callback are
	 * invoked on
	 * handshake
	 * success,
	 * otherwise the returned {@link Mono} fail.
	 *
	 * @param websocketHandler the in/out handler for ws transport
	 *
	 * @return a {@link Mono} completing when upgrade is confirmed
	 */
	default Mono<Void> receiveWebsocket(BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		return receiveWebsocket(null, websocketHandler);
	}

	/**
	 * Duplex conversion to {@link WebsocketInbound}, {@link WebsocketOutbound} and a
	 * closing {@link Publisher}. Mono and Callback are
	 * invoked on
	 * handshake
	 * success,
	 * otherwise the returned {@link Mono} fail.
	 *
	 * @param protocols optional sub-protocol
	 * @param websocketHandler the in/out handler for ws transport
	 *
	 * @return a {@link Mono} completing when upgrade is confirmed
	 */
	Mono<Void> receiveWebsocket(String protocols,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler);

	/**
	 * Return the previous redirections or empty array
	 *
	 * @return the previous redirections or empty array
	 */
	String[] redirectedFrom();

	/**
	 * Return response HTTP headers.
	 *
	 * @return response HTTP headers.
	 */
	HttpHeaders responseHeaders();

	/**
	 * @return the resolved HTTP Response Status
	 */
	HttpResponseStatus status();
}
