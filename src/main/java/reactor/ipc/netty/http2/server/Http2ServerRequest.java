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

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http2.Http2Headers;
import reactor.core.publisher.Flux;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.http.HttpInfos;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An Http Reactive Channel with several accessor related to HTTP flow : headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 0.5
 */
public interface Http2ServerRequest extends NettyInbound {

	@Override
	Http2ServerRequest withConnection(Consumer<? super Connection> withConnection);

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
	 * Return the param captured key/value map
	 *
	 * @return the param captured key/value map
	 */
	@Nullable
	Map<String, String> params();

	/**
	 * @param headerResolver provide a params
	 *
	 * @return this request
	 */
	Http2ServerRequest paramsResolver(Function<? super String, Map<String, String>> headerResolver);

	/**
	 * Return a {@link Flux} of {@link HttpContent} containing received chunks
	 *
	 * @return a {@link Flux} of {@link HttpContent} containing received chunks
	 */
	default Flux<HttpContent> receiveContent() {
		return receiveObject().ofType(HttpContent.class);
	}

	/**
	 * Return the address of the host peer.
	 *
	 * @return the host's address
	 */
	InetSocketAddress hostAddress();

	/**
	 * Return the address of the remote peer.
	 *
	 * @return the peer's address
	 */
	InetSocketAddress remoteAddress();

	/**
	 * Return inbound {@link Http2Headers}
	 *
	 * @return inbound {@link Http2Headers}
	 */
	Http2Headers requestHeaders();

	/**
	 * Return the current scheme
	 *
	 * @return the protocol scheme
	 */
	String scheme();

}
