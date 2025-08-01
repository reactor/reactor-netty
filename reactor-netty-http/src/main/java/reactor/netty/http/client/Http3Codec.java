/*
 * Copyright (c) 2024-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3FrameToHttpObjectCodec;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.jspecify.annotations.Nullable;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.net.SocketAddress;
import java.util.function.Function;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.http.client.Http2ConnectionProvider.http2PooledRef;
import static reactor.netty.http.client.Http2ConnectionProvider.logStreamsState;
import static reactor.netty.http.client.Http3ConnectionProvider.OWNER;

final class Http3Codec extends ChannelInitializer<QuicStreamChannel> {

	static final Logger log = Loggers.getLogger(Http3Codec.class);

	final ConnectionObserver obs;
	final ChannelOperations.OnSetup opsFactory;
	final boolean acceptGzip;
	final @Nullable LoggingHandler loggingHandler;
	final @Nullable ChannelMetricsRecorder metricsRecorder;
	final SocketAddress remoteAddress;
	final @Nullable Function<String, String> uriTagValue;
	final boolean validate;

	Http3Codec(
			ConnectionObserver obs,
			ChannelOperations.OnSetup opsFactory,
			boolean acceptGzip,
			@Nullable LoggingHandler loggingHandler,
			@Nullable ChannelMetricsRecorder metricsRecorder,
			SocketAddress remoteAddress,
			@Nullable Function<String, String> uriTagValue,
			boolean validate) {
		this.obs = obs;
		this.opsFactory = opsFactory;
		this.acceptGzip = acceptGzip;
		this.loggingHandler = loggingHandler;
		this.metricsRecorder = metricsRecorder;
		this.remoteAddress = remoteAddress;
		this.uriTagValue = uriTagValue;
		this.validate = validate;
	}

	@Override
	protected void initChannel(QuicStreamChannel ch) {
		if (HttpClientOperations.log.isDebugEnabled()) {
			HttpClientOperations.log.debug(format(ch, "New HTTP/3 stream"));
		}

		ChannelPipeline pipeline = ch.pipeline();

		if (loggingHandler != null) {
			pipeline.addLast(NettyPipeline.LoggingHandler, loggingHandler);
		}

		pipeline.addLast(NettyPipeline.H3ToHttp11Codec, new Http3FrameToHttpObjectCodec(false, validate))
		        .addLast(NettyPipeline.HttpTrafficHandler, HTTP_3_STREAM_BRIDGE_CLIENT_HANDLER);

		if (acceptGzip) {
			pipeline.addLast(NettyPipeline.HttpDecompressor, new HttpContentDecompressor(false, 0));
		}

		ChannelOperations.addReactiveBridge(ch, opsFactory, obs);

		if (metricsRecorder != null) {
			if (metricsRecorder instanceof HttpClientMetricsRecorder) {
				ChannelHandler handler;
				if (metricsRecorder instanceof MicrometerHttpClientMetricsRecorder) {
					handler = new MicrometerHttpClientMetricsHandler((MicrometerHttpClientMetricsRecorder) metricsRecorder, remoteAddress, null, uriTagValue);
				}
				else if (metricsRecorder instanceof ContextAwareHttpClientMetricsRecorder) {
					handler = new ContextAwareHttpClientMetricsHandler((ContextAwareHttpClientMetricsRecorder) metricsRecorder, remoteAddress, null, uriTagValue);
				}
				else {
					handler = new HttpClientMetricsHandler((HttpClientMetricsRecorder) metricsRecorder, remoteAddress, null, uriTagValue);
				}
				pipeline.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpMetricsHandler, handler);
			}
		}

		if (log.isDebugEnabled()) {
			log.debug(format(ch, "Initialized HTTP/3 stream pipeline {}"), ch.pipeline());

			ConnectionObserver owner = ch.parent().attr(OWNER).get();
			if (owner instanceof Http3ConnectionProvider.DisposableAcquire) {
				Http3ConnectionProvider.DisposableAcquire da = (Http3ConnectionProvider.DisposableAcquire) owner;
				Http2Pool.Http2PooledRef http2PooledRef = http2PooledRef(da.pooledRef);
				logStreamsState(ch, http2PooledRef.slot, "Stream opened");
			}
		}
	}

	static ChannelHandler newHttp3ClientConnectionHandler() {
		return new Http3ClientConnectionHandler();
	}

	static final Http3StreamBridgeClientHandler HTTP_3_STREAM_BRIDGE_CLIENT_HANDLER =
			new Http3StreamBridgeClientHandler();
}
