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
package reactor.netty.http.server;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.ProtocolDetectionResult;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import reactor.netty.NettyPipeline;

/**
 * Auto detect whether the first packet of a channel is proxy protocol,
 * if so, replace current handler with a {@link HAProxyMessageDecoder},
 * so each channel in the same {@link HttpServer} instance can choose to support
 * proxy protocol or not at runtime.
 *
 * @author aftersss
 */
final class HAProxyMessageDetector extends ByteToMessageDecoder {

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		ProtocolDetectionResult<HAProxyProtocolVersion> detectionResult = HAProxyMessageDecoder.detectProtocol(in);
		if (detectionResult.equals(ProtocolDetectionResult.needsMoreData())) {
			return;
		}
		else if(detectionResult.equals(ProtocolDetectionResult.invalid())) {
			ctx.pipeline()
			   .remove(this);
		}
		else {
			ctx.pipeline()
			   .addAfter(NettyPipeline.ProxyProtocolDecoder,
			             NettyPipeline.ProxyProtocolReader,
			             new HAProxyMessageReader());
			ctx.pipeline()
			   .replace(this, NettyPipeline.ProxyProtocolDecoder, new HAProxyMessageDecoder());
		}
	}
}
