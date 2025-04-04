/*
 * Copyright (c) 2018-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.compression.CompressionOptions;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;
import reactor.netty.http.server.compression.HttpCompressionOptionsSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link HttpContentCompressor} to enable on-demand compression.
 *
 * @author Stephane Maldini
 */
final class SimpleCompressionHandler extends HttpContentCompressor {

	boolean decoded;
	@Nullable HttpRequest request;

	private SimpleCompressionHandler() {
		super((CompressionOptions[]) null);
	}

	private SimpleCompressionHandler(CompressionOptions... options) {
		super(options);
	}

	static SimpleCompressionHandler create(@Nullable HttpCompressionOptionsSpec compressionOptions) {
		return compressionOptions == null ?
				new SimpleCompressionHandler() :
				new SimpleCompressionHandler(compressionOptions.adapt());
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, HttpRequest msg, List<Object> out) throws Exception {
		decoded = true;
		super.decode(ctx, msg, out);
	}

	@Override
	@SuppressWarnings("NullAway")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
			throws Exception {

		if (msg instanceof ByteBuf) {
			super.write(ctx, new DefaultHttpContent((ByteBuf) msg), promise);
		}
		else {
			if (!decoded && msg instanceof HttpResponse) {
				// Deliberately suppress "NullAway"
				// This is a lazy initialization
				decode(ctx, request);
			}
			if (decoded && request != null && msg instanceof LastHttpContent) {
				decoded = false;
				request = null;
			}
			super.write(ctx, msg, promise);
		}
	}

	void decode(ChannelHandlerContext ctx, HttpRequest msg) {
		List<Object> out = new ArrayList<>();
		HttpRequest request = msg;
		try {
			if (msg instanceof FullHttpRequest && ((FullHttpRequest) msg).content().refCnt() == 0) {
				// This can happen only in HTTP2 use case and delayed response
				// When the incoming FullHttpRequest content is with 0 readableBytes it is released immediately
				// decode(...) will observe a freed content
				request = new DefaultHttpRequest(msg.protocolVersion(), msg.method(), msg.uri(), msg.headers());
			}
			decode(ctx, request, out);
		}
		catch (DecoderException e) {
			throw e;
		}
		catch (Exception e) {
			throw new DecoderException(e);
		}
		finally {
			// ReferenceCountUtil.retain(...) is invoked in decode(...) so release(...) here
			ReferenceCountUtil.release(request);
			out.clear();
		}
	}
}
