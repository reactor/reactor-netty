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

package reactor.ipc.netty.http;

import java.util.Map;
import java.util.Set;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.Cookie;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.common.MonoChannelFuture;
import reactor.ipc.netty.common.NettyHandlerNames;

/**
 * @author Stephane Maldini
 */
final class HttpServerChannel extends NettyHttpChannel {

	private final Cookies cookies;

	HttpServerChannel(io.netty.channel.Channel ioChannel,
			Flux<Object> input, HttpRequest msg) {
		super(ioChannel, input, msg);
		this.cookies = Cookies.newServerRequestHolder(headers());
	}

	@Override
	protected void doSubscribeHeaders(Subscriber<? super Void> s) {
		MonoChannelFuture.from(delegate().writeAndFlush(getNettyResponse()))
		                 .subscribe(s);
	}

	@Override
	public Mono<Void> upgradeToWebsocket(String protocols, boolean textPlain) {
		ChannelPipeline pipeline = delegate().pipeline();
		NettyWebSocketServerHandler handler =
				pipeline.remove(NettyHttpServerHandler.class)
				        .withWebsocketSupport(uri(), protocols, textPlain);

		if (handler != null) {
			pipeline.addLast(NettyHandlerNames.ReactiveBridge, handler);
			return MonoChannelFuture.from(handler.handshakerResult);
		}
		return Mono.error(new IllegalStateException("Failed to upgrade to websocket"));
	}

	@Override
	public Map<CharSequence, Set<Cookie>> cookies() {
		return cookies.getCachedCookies();
	}
}
