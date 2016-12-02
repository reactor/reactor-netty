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

import java.util.Map;
import java.util.function.Function;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpHeaders;
import reactor.ipc.netty.http.HttpInbound;

/**
 *
 * An Http Reactive Channel with several accessor related to HTTP flow : headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 0.5
 */
public interface HttpServerRequest extends HttpInbound {

	@Override
	HttpServerRequest addChannelHandler(ChannelHandler handler);

	@Override
	HttpServerRequest addChannelHandler(String name, ChannelHandler handler);

	@Override
	HttpServerRequest onClose(Runnable onClose);

	@Override
	HttpServerRequest onReadIdle(long idleTimeout, Runnable onReadIdle);

	/**
	 * URI parameter captured via {} "/test/{var}"
	 * @param key param var name
	 * @return the param captured value
	 */
	Object param(CharSequence key);

	/**
	 * Return the param captured key/value map
	 * @return the param captured key/value map
	 */
	Map<String, Object> params();

	/**
	 *
	 * @param headerResolver
	 * @return
	 */
	HttpServerRequest paramsResolver(Function<? super String, Map<String, Object>> headerResolver);

	/**
	 * Return inbound {@link HttpHeaders}
	 * @return inbound {@link HttpHeaders}
	 */
	HttpHeaders requestHeaders();

}
