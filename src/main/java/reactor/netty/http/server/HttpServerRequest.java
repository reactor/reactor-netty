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

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import reactor.core.publisher.Flux;
import reactor.netty.Connection;
import reactor.netty.NettyInbound;
import reactor.netty.http.HttpInfos;

/**
 * An Http Reactive Channel with several accessors related to HTTP flow: headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 0.5
 */
public interface HttpServerRequest extends NettyInbound, HttpInfos {

	@Override
	HttpServerRequest withConnection(Consumer<? super Connection> withConnection);

	/**
	 * URI parameter captured via {} "/test/{var}"
	 *
	 * @param key param var name
	 *
	 * @return the param captured value
	 */
	@Nullable
	String param(CharSequence key);

	/**
	 * Returns the param captured key/value map
	 *
	 * @return the param captured key/value map
	 */
	@Nullable
	Map<String, String> params();

	/**
	 * @param headerResolver provide a params
	 *
	 * @return this {@link HttpServerRequest}
	 */
	HttpServerRequest paramsResolver(Function<? super String, Map<String, String>> headerResolver);

	/**
	 * Returns a {@link Flux} of {@link HttpContent} containing received chunks
	 *
	 * @return a {@link Flux} of {@link HttpContent} containing received chunks
	 */
	default Flux<HttpContent> receiveContent() {
		return receiveObject().ofType(HttpContent.class);
	}

	/**
	 * Returns the address of the host peer.
	 *
	 * @return the host's address
	 */
	InetSocketAddress hostAddress();

	/**
	 * Returns the address of the remote peer.
	 *
	 * @return the peer's address
	 */
	InetSocketAddress remoteAddress();

	/**
	 * Returns inbound {@link HttpHeaders}
	 *
	 * @return inbound {@link HttpHeaders}
	 */
	HttpHeaders requestHeaders();

	/**
	 * Returns the current protocol scheme
	 *
	 * @return the protocol scheme
	 */
	String scheme();

}
