/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SslHandler;
import reactor.netty.NettyPipeline;
import reactor.netty.tcp.SslProvider;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.List;

/**
 * The handler detects if it's an SSL record header or an HTTP request from
 * the first 5 bytes of the incoming message, and hands the message over
 * to an SSL handler if it's an SSL record header or {@link NonSslRedirectHandler}
 * if it's a potential HTTP request.
 *
 * @author James Chen
 * @since 1.0.5
 */
final class NonSslRedirectDetector extends ByteToMessageDecoder {

	private static final int SSL_RECORD_HEADER_LENGTH = 5;

	private final SslProvider sslProvider;
	private final SocketAddress remoteAddress;
	private final boolean sslDebug;

	public NonSslRedirectDetector(SslProvider sslProvider, @Nullable SocketAddress remoteAddress, boolean sslDebug) {
		this.sslProvider = sslProvider;
		this.remoteAddress = remoteAddress;
		this.sslDebug = sslDebug;
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		if (in.readableBytes() < SSL_RECORD_HEADER_LENGTH) {
			return;
		}
		ChannelPipeline pipeline = ctx.pipeline();
		if (SslHandler.isEncrypted(in)) {
			sslProvider.addSslHandler(ctx.channel(), remoteAddress, sslDebug);
		}
		else {
			pipeline.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.NonSslRedirectHandler, new NonSslRedirectHandler());
		}
		pipeline.remove(this);
	}

}