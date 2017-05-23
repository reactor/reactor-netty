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

import java.util.ArrayDeque;
import java.util.Queue;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpMessage;
import reactor.ipc.netty.NettyPipeline;

/**
 * @author mostroverkhov
 */
final class CompressionHandler extends ChannelDuplexHandler {

	final int minResponseSize;
	final Queue<Object> messages = new ArrayDeque<>();

	int bodyCompressThreshold;

	CompressionHandler(int minResponseSize) {
		this.minResponseSize = minResponseSize;
		this.bodyCompressThreshold = minResponseSize;
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
			throws Exception {
		if (msg instanceof ByteBuf) {
			offerByteBuf(ctx, msg, promise);
		}
		else if (msg instanceof HttpMessage) {
			offerHttpMessage(msg, promise);
		}
		else {
			super.write(ctx, msg, promise);
		}
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
			throws Exception {
		if (evt == NettyPipeline.responseWriteCompletedEvent()) {
			if (bodyCompressThreshold > 0 || !messages.isEmpty()) {
				while (!messages.isEmpty()) {
					Object msg = messages.poll();
					writeSkipCompress(ctx, msg);
				}
			}
		}
		super.userEventTriggered(ctx, evt);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
			throws Exception {
		releaseMsgs();
		super.exceptionCaught(ctx, cause);
	}

	@Override
	public void close(ChannelHandlerContext ctx, ChannelPromise promise)
			throws Exception {
		releaseMsgs();
		super.close(ctx, promise);
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		addCompressionHandlerOnce(ctx, ctx.pipeline());
	}

	void offerHttpMessage(Object msg, ChannelPromise p) {
		messages.offer(msg);
		p.setSuccess();
	}

	void offerByteBuf(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
			throws Exception {
		ByteBuf byteBuf = (ByteBuf) msg;
		messages.offer(byteBuf);
		if (bodyCompressThreshold > 0) {
			bodyCompressThreshold -= byteBuf.readableBytes();
		}
		drain(ctx, promise);
	}

	void drain(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
		if (bodyCompressThreshold <= 0) {
			while (!messages.isEmpty()) {
				Object message = messages.poll();
				writeCompress(ctx, message, promise);
			}
		}
	}

	void writeCompress(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
			throws Exception {
		ctx.write(msg, promise);
	}

	void writeSkipCompress(ChannelHandlerContext ctx, Object msg) throws Exception {
		ctx.write(FilteringHttpContentCompressor.FilterMessage.wrap(msg));
	}

	void releaseMsgs() {
		while (!(messages.isEmpty())) {
			Object msg = messages.poll();
			if (msg instanceof ByteBuf) {
				((ByteBuf) msg).release();
			}
		}
	}

	void addCompressionHandlerOnce(ChannelHandlerContext ctx, ChannelPipeline cp) {
		if (cp.get(FilteringHttpContentCompressor.class) == null) {
			ctx.pipeline()
			   .addBefore(NettyPipeline.CompressionHandler,
					   NettyPipeline.HttpCompressor,
					   new FilteringHttpContentCompressor());
		}
	}
}


