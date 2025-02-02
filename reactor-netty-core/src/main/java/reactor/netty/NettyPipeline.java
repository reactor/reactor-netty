/*
 * Copyright (c) 2011-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
 * Clients:
 * -> proxy log ? [ProxyLoggingHandler]
 * -> proxy handler ? [ProxyHandler]
 * -> ssl log ? [SslLoggingHandler]
 * -> ssl handler ? [SslHandler]
 * -> log ? [LoggingHandler]
 * -> ssl reader ? [SslReader]
 * -> channel metrics ? [ChannelMetricsHandler]
 * -> connect metrics ? [ConnectMetricsHandler]
 * -> h2 or http/1.1 codec? [H2OrHttp11Codec]
 * -> http/1.1 codec ? [HttpCodec]
 * -> h2 multiplex handler ? [H2MultiplexHandler]
 * -> http/1.1 decompressor ? [HttpDecompressor]
 * -> h2 to http/1.1 codec ? [H2ToHttp11Codec]
 * -> http traffic handler ? [HttpTrafficHandler]
 * -> http metrics ? [HttpMetricsHandler]
 * -> http form/multipart/send file ? [ChunkedWriter]
 * -> request timeout handler ? [RequestTimeoutHandler]
 * -> http aggregator (websocket) ? [HttpAggregator]
 * -> websocket compression ? [WsCompressionHandler]
 * -> websocket frame aggregator ? [WsFrameAggregator]
 * -> onWriteIdle ? [OnChannelWriteIdle]
 * -> onReadIdle ? [OnChannelReadIdle]
 * => [ReactiveBridge]
 *
 * Servers:
 * -> proxy protocol decoder ? [ProxyProtocolDecoder]
 * -> proxy protocol reader ? [ProxyProtocolReader]
 * -> non ssl redirect detector ? [NonSslRedirectDetector]
 * -> ssl log ? [SslLoggingHandler]
 * -> ssl handler ? [SslHandler]
 * -> log ? [LoggingHandler]
 * -> ssl reader ? [SslReader]
 * -> channel metrics ? [ChannelMetricsHandler]
 * -> h2c upgrade handler ? [H2CUpgradeHandler]
 * -> h2 or http/1.1 codec? [H2OrHttp11Codec]
 * -> http codec ? [HttpCodec]
 * -> h2 multiplex handler ? [H2MultiplexHandler]
 * -> http access log ? [AccessLogHandler]
 * -> http/1.1 compression ? [CompressionHandler]
 * -> h2 to http/1.1 codec ? [H2ToHttp11Codec]
 * -> h3 to http/1.1 codec ? [H3ToHttp11Codec]
 * -> http traffic handler ? [HttpTrafficHandler]
 * -> http metrics ? [HttpMetricsHandler]
 * -> http send file ? [ChunkedWriter]
 * -> websocket compression ? [WsCompressionHandler]
 * -> websocket frame aggregator ? [WsFrameAggregator]
 * -> onWriteIdle ? [OnChannelWriteIdle]
 * -> onReadIdle ? [OnChannelReadIdle]
 * -> non ssl redirect handler ? [NonSslRedirectHandler]
 * => [ReactiveBridge]
 * }
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 * @since 0.6
 */
public interface NettyPipeline {

	String LEFT                  = "reactor.left.";
	String RIGHT                 = "reactor.right.";

	String AccessLogHandler      = LEFT + "accessLogHandler";
	String ChannelMetricsHandler = LEFT + "channelMetricsHandler";
	String ChunkedWriter         = LEFT + "chunkedWriter";
	String CompressionHandler    = LEFT + "compressionHandler";
	String ConnectMetricsHandler = LEFT + "connectMetricsHandler";
	String H2CUpgradeHandler     = LEFT + "h2cUpgradeHandler";
	String H2Flush               = LEFT + "h2Flush";
	String H2MultiplexHandler    = LEFT + "h2MultiplexHandler";
	String H2OrHttp11Codec       = LEFT + "h2OrHttp11Codec";
	String H2ToHttp11Codec       = LEFT + "h2ToHttp11Codec";
	String H3ToHttp11Codec       = LEFT + "h3ToHttp11Codec";
	String HttpAggregator        = LEFT + "httpAggregator";
	String HttpCodec             = LEFT + "httpCodec";
	String HttpDecompressor      = LEFT + "httpDecompressor";
	String HttpMetricsHandler    = LEFT + "httpMetricsHandler";
	String HttpTrafficHandler    = LEFT + "httpTrafficHandler";
	String IdleTimeoutHandler    = LEFT + "idleTimeoutHandler";
	String LoggingHandler        = LEFT + "loggingHandler";
	String NonSslRedirectDetector = LEFT + "nonSslRedirectDetector";
	String NonSslRedirectHandler = LEFT + "nonSslRedirectHandler";
	String OnChannelReadIdle     = LEFT + "onChannelReadIdle";
	String OnChannelWriteIdle    = LEFT + "onChannelWriteIdle";
	String ProxyHandler          = LEFT + "proxyHandler";
	String H2LivenessHandler          = LEFT + "h2LivenessHandler";
	/**
	 * Use to register a special handler which ensures that any {@link io.netty.channel.VoidChannelPromise}
	 * will be converted to "unvoided" promises.
	 *
	 * @deprecated as of 1.1.0. This will be removed in 2.0.0 as Netty 5 does not support
	 * {@link io.netty.channel.VoidChannelPromise}.
	 */
	@Deprecated
	String UnvoidHandler         = LEFT + "unvoidHandler";
	String ProxyLoggingHandler   = LEFT + "proxyLoggingHandler";
	String ProxyProtocolDecoder  = LEFT + "proxyProtocolDecoder";
	String ProxyProtocolReader   = LEFT + "proxyProtocolReader";
	String ReadTimeoutHandler    = LEFT + "readTimeoutHandler";
	String ResponseTimeoutHandler = LEFT + "responseTimeoutHandler";
	String SslHandler            = LEFT + "sslHandler";
	String SslLoggingHandler     = LEFT + "sslLoggingHandler";
	String SslReader             = LEFT + "sslReader";
	String TlsMetricsHandler     = LEFT + "tlsMetricsHandler";
	String WsCompressionHandler  = LEFT + "wsCompressionHandler";
	String WsFrameAggregator     = LEFT + "wsFrameAggregator";

	String ReactiveBridge        = RIGHT + "reactiveBridge";

	/**
	 * Create a new {@link ChannelInboundHandler} that will invoke
	 * {@link BiConsumer#accept} on
	 * {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)}.
	 *
	 * @param handler the channel-read callback
	 * @return a marking event used when a netty connector handler terminates
	 */
	static ChannelInboundHandler inboundHandler(BiConsumer<? super ChannelHandlerContext, Object> handler) {
		return new ReactorNetty.ExtractorHandler(handler);
	}
}
