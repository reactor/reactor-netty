/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractCoalescingBufferQueue;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.CoalescingBufferQueue;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.LastHttpContent;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.AbortedException;

/**
 * @author mostroverkhov
 * @author smaldini
 */
final class CompressionHandler extends HttpContentCompressor {

	final int minResponseSize;

	AbstractCoalescingBufferQueue messages;

	HttpMessage first;

	int bodyCompressThreshold;

	CompressionHandler(int minResponseSize) {
		this.minResponseSize = minResponseSize;
		this.bodyCompressThreshold = minResponseSize;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		super.handlerAdded(ctx);
		messages = new CoalescingBufferQueue(ctx.channel());
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
			throws Exception {
		if (msg instanceof HttpMessage) {
			first = (HttpMessage) msg;
			promise.setSuccess();
		}
		else if (msg instanceof ByteBuf) {
			offerByteBuf(ctx, msg, promise);
		}
		else if (bodyCompressThreshold > 0 && msg instanceof LastHttpContent) {
			ctx.write(msg, promise);
		}
		else{
			super.write(ctx, msg, promise);
		}
		if (msg instanceof LastHttpContent) {
			bodyCompressThreshold = minResponseSize;
		}
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
			throws Exception {
		if (evt == NettyPipeline.responseCompressionEvent() || evt == NettyPipeline.handlerTerminatedEvent()) {
			if (!messages.isEmpty()) {
				if (bodyCompressThreshold <= 0) {
					writeMessage(ctx);
					pollAndWrite(ctx);
				}
				else {
					writeMessageWithoutCompression(ctx);
					messages.writeAndRemoveAll(ctx);
				}
			}
			else {
				writeMessageWithoutCompression(ctx);
			}
		}
		super.userEventTriggered(ctx, evt);
	}

	@Override
	public void flush(ChannelHandlerContext ctx) throws Exception {
		drain(ctx);
		super.flush(ctx);
	}

	void writeMessageWithoutCompression(ChannelHandlerContext ctx){
		if (first != null) {
			HttpMessage first = this.first;
			this.first = null;

			bodyCompressThreshold = Integer.MAX_VALUE;

			ctx.write(first, ctx.voidPromise());
		}
	}

	void writeMessage(ChannelHandlerContext ctx) throws Exception{
		if (first != null) {
			HttpMessage first = this.first;
			this.first = null;

			super.write(ctx, first, ctx.voidPromise());
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
			throws Exception {
		messages.releaseAndFailAll(ctx, cause);
		super.exceptionCaught(ctx, cause);
	}

	static final AbortedException aborted = new AbortedException();

	@Override
	public void close(ChannelHandlerContext ctx, ChannelPromise promise)
			throws Exception {
		messages.releaseAndFailAll(ctx, aborted);
		super.close(ctx, promise);
	}

	void offerByteBuf(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
			throws Exception {
		ByteBuf byteBuf = (ByteBuf) msg;
		messages.add(byteBuf, promise);
		int threshold = bodyCompressThreshold;
		if (threshold > 0 && threshold != Integer.MAX_VALUE) {
			bodyCompressThreshold -= byteBuf.readableBytes();
		}
		drain(ctx);
	}

	void pollAndWrite(ChannelHandlerContext ctx) throws Exception {
		ByteBuf b;
		for (; ; ) {
			ChannelPromise p = ctx.newPromise();
			b = messages.removeFirst(p);

			if (b == null) {
				break;
			}

			super.write(ctx, new DefaultHttpContent(b), p);
		}
	}

	void drain(ChannelHandlerContext ctx) throws Exception {
		if (bodyCompressThreshold <= 0 && !messages.isEmpty()) {
			writeMessage(ctx);
			pollAndWrite(ctx);
		}
	}
}


