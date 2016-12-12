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

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Flux;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.http.HttpInfos;
import reactor.ipc.netty.http.multipart.MultipartInbound;

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
	HttpClientResponse addHandler(ChannelHandler handler);

	@Override
	HttpClientResponse addHandler(String name, ChannelHandler handler);

	@Override
	HttpClientResponse onClose(Runnable onClose);

	@Override
	default HttpClientResponse onReadIdle(long idleTimeout, Runnable onReadIdle){
		NettyInbound.super.onReadIdle(idleTimeout, onReadIdle);
		return this;
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
