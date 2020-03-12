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

package reactor.netty;

import java.util.function.BiConsumer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;

/**
 * Constant for names used when adding/removing {@link io.netty.channel.ChannelHandler}.
 *
 * Order of placement :
 * <p>
 * {@code
 * -> proxy ? [ProxyHandler]
 * -> ssl ? [SslHandler]
 * -> ssl & trace log ? [SslLoggingHandler]
 * -> ssl ? [SslReader]
 * -> log ? [LoggingHandler]
 * -> http ? [HttpCodecHandler]
 * -> http ws ? [HttpAggregator]
 * -> http server  ? [HttpTrafficHandler]
 * -> onWriteIdle ? [OnChannelWriteIdle]
 * -> onReadIdle ? [OnChannelReadIdle]
 * -> http form/multipart ? [ChunkedWriter]
 * => [ReactiveBridge]
 * }
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public interface NettyPipeline {

	String LEFT = "reactor.left.";
	String RIGHT = "reactor.right.";

	String SslHandler         = LEFT + "sslHandler";
	String SslReader          = LEFT + "sslReader";
	String SslLoggingHandler  = LEFT + "sslLoggingHandler";
	String ProxyHandler       = LEFT + "proxyHandler";
	String ProxyLoggingHandler= LEFT + "proxyLoggingHandler";
	String ReactiveBridge     = RIGHT + "reactiveBridge";
	String HttpCodec          = LEFT + "httpCodec";
	String HttpDecompressor   = LEFT + "decompressor";
	String HttpAggregator     = LEFT + "httpAggregator";
	String HttpTrafficHandler = LEFT + "httpTrafficHandler";
	String HttpInitializer    = LEFT + "httpInitializer";
	String AccessLogHandler   = LEFT + "accessLogHandler";
	String OnChannelWriteIdle = LEFT + "onChannelWriteIdle";
	String OnChannelReadIdle  = LEFT + "onChannelReadIdle";
	String ChunkedWriter      = LEFT + "chunkedWriter";
	String LoggingHandler     = LEFT + "loggingHandler";
	String CompressionHandler = LEFT + "compressionHandler";
	String HttpMetricsHandler = LEFT + "httpMetricsHandler";
	String SslMetricsHandler  = LEFT + "sslMetricsHandler";
	String ChannelMetricsHandler = LEFT + "channelMetricsHandler";
	String ConnectMetricsHandler = LEFT + "connectMetricsHandler";
	String WsCompressionHandler = LEFT + "wsCompressionHandler";
	String ProxyProtocolDecoder = LEFT + "proxyProtocolDecoder";
	String ProxyProtocolReader  = LEFT + "proxyProtocolReader";

	/**
	 * Create a new {@link ChannelInboundHandler} that will invoke
	 * {@link BiConsumer#accept} on
	 * {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)}.
	 *
	 * @param handler the channel-read callback
	 *
	 * @return a marking event used when a netty connector handler terminates
	 */
	static ChannelInboundHandler inboundHandler(BiConsumer<? super ChannelHandlerContext, Object> handler) {
		return new ReactorNetty.ExtractorHandler(handler);
	}
}
