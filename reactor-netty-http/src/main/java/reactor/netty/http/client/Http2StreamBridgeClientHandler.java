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
package reactor.netty.http.client;

import io.netty.channel.ChannelHandlerContext;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.Http2StreamBridgeHandler;

/**
 * Client specific {@link Http2StreamBridgeHandler}.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
final class Http2StreamBridgeClientHandler extends Http2StreamBridgeHandler {

	final ConnectionObserver observer;
	final ChannelOperations.OnSetup opsFactory;

	Http2StreamBridgeClientHandler(ConnectionObserver listener, ChannelOperations.OnSetup opsFactory) {
		this.observer = listener;
		this.opsFactory = opsFactory;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		Http2ConnectionProvider.registerClose(ctx.channel());
		ctx.read();
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		super.handlerAdded(ctx);

		ChannelOperations<?, ?> ops = opsFactory.create(Connection.from(ctx.channel()), observer, null);
		if (ops != null) {
			ops.bind();
		}
	}
}
