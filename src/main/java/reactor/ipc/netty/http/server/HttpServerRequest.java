/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import reactor.core.publisher.Flux;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.http.HttpInfos;

/**
 * An Http Reactive Channel with several accessor related to HTTP flow : headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 0.5
 */
public interface HttpServerRequest extends NettyInbound, HttpInfos {

	@Override
	default HttpServerRequest onReadIdle(long idleTimeout, Runnable onReadIdle) {
		NettyInbound.super.onReadIdle(idleTimeout, onReadIdle);
		return this;
	}

	@Override
	HttpServerRequest withConnection(Consumer<? super Connection> withConnection);

	/**
	 * URI parameter captured via {} "/test/{var}"
	 *
	 * @param key param var name
	 *
	 * @return the param captured value
	 */
	String param(CharSequence key);

	/**
	 * Return the param captured key/value map
	 *
	 * @return the param captured key/value map
	 */
	Map<String, String> params();

	/**
	 * @param headerResolver provide a params
	 *
	 * @return this request
	 */
	HttpServerRequest paramsResolver(Function<? super String, Map<String, String>> headerResolver);

	/**
	 * Return a {@link Flux} of {@link HttpContent} containing received chunks
	 *
	 * @return a {@link Flux} of {@link HttpContent} containing received chunks
	 */
	default Flux<HttpContent> receiveContent() {
		return receiveObject().ofType(HttpContent.class);
	}

	/**
	 * Return inbound {@link HttpHeaders}
	 *
	 * @return inbound {@link HttpHeaders}
	 */
	HttpHeaders requestHeaders();

}
