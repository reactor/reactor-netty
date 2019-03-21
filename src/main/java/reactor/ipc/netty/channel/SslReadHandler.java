/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.channel;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

/**
 * @author Stephane Maldini
 */
final class SslReadHandler extends ChannelInboundHandlerAdapter {

	final ContextHandler<?> sink;

	boolean handshakeDone;

	SslReadHandler(ContextHandler<?> sink) {
		this.sink = sink;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		ctx.read(); //consume handshake
		//super.channelActive(ctx);
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		if (!handshakeDone) {
			ctx.read(); /* continue consuming. */
		}
		super.channelReadComplete(ctx);
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
			throws Exception {
		if (evt instanceof SslHandshakeCompletionEvent) {
			handshakeDone = true;
			if(ctx.pipeline().context(this) != null){
				ctx.pipeline().remove(this);
			}
			SslHandshakeCompletionEvent handshake = (SslHandshakeCompletionEvent) evt;
			if (handshake.isSuccess()) {
				ctx.fireChannelActive();
			}
			else {
				sink.fireContextError(handshake.cause());
			}
		}
		super.userEventTriggered(ctx, evt);
	}
}
