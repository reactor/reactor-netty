/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import reactor.core.publisher.Flux;
import reactor.netty.Connection;
import reactor.netty.NettyInbound;
import reactor.util.annotation.Nullable;

/**
 * An Http Reactive Channel with several accessors related to HTTP flow: headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 0.5
 */
public interface HttpServerRequest extends NettyInbound, HttpServerInfos {

	@Override
	HttpServerRequest withConnection(Consumer<? super Connection> withConnection);

	/**
	 * URI parameter captured via {@code {}} e.g. {@code /test/{param}}.
	 *
	 * @param key parameter name e.g. {@code "param"} in URI {@code /test/{param}}
	 * @return the parameter captured value
	 */
	@Nullable
	String param(CharSequence key);

	/**
	 * Returns all URI parameters captured via {@code {}} e.g. {@code /test/{param1}/{param2}} as key/value map.
	 *
	 * @return the parameters captured key/value map
	 */
	@Nullable
	Map<String, String> params();

	/**
	 * Specifies a params resolver.
	 *
	 * @param paramsResolver a params resolver
	 *
	 * @return this {@link HttpServerRequest}
	 */
	HttpServerRequest paramsResolver(Function<? super String, Map<String, String>> paramsResolver);

	/**
	 * Returns a {@link Flux} of {@link HttpContent} containing received chunks
	 *
	 * @return a {@link Flux} of {@link HttpContent} containing received chunks
	 */
	default Flux<HttpContent> receiveContent() {
		return receiveObject().ofType(HttpContent.class);
	}

	/**
	 * Returns the address of the host peer or {@code null} in case of Unix Domain Sockets.
	 *
	 * @return the host's address
	 */
	@Nullable
	InetSocketAddress hostAddress();

	/**
	 * Returns the address of the remote peer or {@code null} in case of Unix Domain Sockets.
	 *
	 * @return the peer's address
	 */
	@Nullable
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
