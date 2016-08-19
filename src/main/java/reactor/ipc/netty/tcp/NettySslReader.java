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

package reactor.ipc.netty.tcp;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import reactor.core.publisher.DirectProcessor;

/**
 * @author Stephane Maldini
 */
final class NettySslReader extends ChannelDuplexHandler {

	final DirectProcessor<Void> secureCallback;

	boolean handshakeDone;

	NettySslReader(DirectProcessor<Void> secureCallback) {
		this.secureCallback = secureCallback;
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
			SslHandshakeCompletionEvent handshake = (SslHandshakeCompletionEvent) evt;
			if(secureCallback != null) {
				if (handshake.isSuccess()) {
					ctx.fireChannelActive();
					secureCallback.onComplete();
				}
				else {
					secureCallback.onError(handshake.cause());
				}
			}
			else if(!handshake.isSuccess()){
				ctx.fireExceptionCaught(handshake.cause());
			}
			else{
				ctx.fireChannelActive();
			}
		}
		super.userEventTriggered(ctx, evt);
	}
}
