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
 * if so, replace current handler with a `HAProxyMessageDecoder`,
 * so each channel in the same `HttpServer` instance can choose to support
 * proxy protocol or not at runtime.
 *
 * @author aftersss
 */
final class HAProxyMessageDetector extends ByteToMessageDecoder {

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		ProtocolDetectionResult<HAProxyProtocolVersion> detectionResult = HAProxyMessageDecoder.detectProtocol(in);
		if (detectionResult.equals(ProtocolDetectionResult.needsMoreData())) {
			return;
		} else if(detectionResult.equals(ProtocolDetectionResult.invalid())) {
			ctx.pipeline().remove(this);
		} else {
			ctx.pipeline()
					.addAfter(NettyPipeline.ProxyProtocolDecoder,
							NettyPipeline.ProxyProtocolReader, new HAProxyMessageReader());
			ctx.pipeline().replace(this, NettyPipeline.ProxyProtocolDecoder, new HAProxyMessageDecoder());
		}
	}

}
