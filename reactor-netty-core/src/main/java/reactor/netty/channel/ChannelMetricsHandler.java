/*
 * Copyright (c) 2019-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.channel;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.SniCompletionEvent;
import io.netty.handler.ssl.SslHandler;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;

import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.SUCCESS;

/**
 * {@link ChannelHandler} for collecting metrics on protocol level.
 *
 * @author Violeta Georgieva
 */
public class ChannelMetricsHandler extends AbstractChannelMetricsHandler {

	final ChannelMetricsRecorder recorder;

	ChannelMetricsHandler(ChannelMetricsRecorder recorder, @Nullable SocketAddress remoteAddress, boolean onServer) {
		super(remoteAddress, onServer);
		this.recorder = recorder;
	}

	@Override
	public ChannelHandler connectMetricsHandler() {
		return new ConnectMetricsHandler(recorder(), proxyAddress);
	}

	@Override
	public ChannelHandler tlsMetricsHandler() {
		return new TlsMetricsHandler(recorder, remoteAddress, proxyAddress);
	}

	@Override
	public ChannelMetricsRecorder recorder() {
		return recorder;
	}

	static final class ConnectMetricsHandler extends ChannelOutboundHandlerAdapter {

		final SocketAddress proxyAddress;
		final ChannelMetricsRecorder recorder;

		ConnectMetricsHandler(ChannelMetricsRecorder recorder, @Nullable SocketAddress proxyAddress) {
			this.proxyAddress = proxyAddress;
			this.recorder = recorder;
		}

		@Override
		public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
				SocketAddress localAddress, ChannelPromise promise) throws Exception {
			long connectTimeStart = System.nanoTime();
			super.connect(ctx, remoteAddress, localAddress, promise);
			promise.addListener(future -> {
				ctx.pipeline().remove(this);

				if (proxyAddress == null) {
					recorder.recordConnectTime(
							remoteAddress,
							Duration.ofNanos(System.nanoTime() - connectTimeStart),
							future.isSuccess() ? SUCCESS : ERROR);
				}
				else {
					recorder.recordConnectTime(
							remoteAddress,
							proxyAddress,
							Duration.ofNanos(System.nanoTime() - connectTimeStart),
							future.isSuccess() ? SUCCESS : ERROR);
				}
			});
		}
	}

	static class TlsMetricsHandler extends ChannelInboundHandlerAdapter {

		protected final SocketAddress proxyAddress;
		protected final ChannelMetricsRecorder recorder;
		protected final SocketAddress remoteAddress;

		boolean listenerAdded;

		TlsMetricsHandler(ChannelMetricsRecorder recorder, @Nullable SocketAddress remoteAddress,
				@Nullable SocketAddress proxyAddress) {
			this.proxyAddress = proxyAddress;
			this.recorder = recorder;
			this.remoteAddress = remoteAddress;
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			addListener(ctx);
			ctx.fireChannelActive();
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
			if (evt instanceof SniCompletionEvent) {
				addListener(ctx);
			}
			ctx.fireUserEventTriggered(evt);
		}

		protected void recordTlsHandshakeTime(ChannelHandlerContext ctx, long tlsHandshakeTimeStart, String status) {
			if (proxyAddress == null) {
				recorder.recordTlsHandshakeTime(
						remoteAddress != null ? remoteAddress : ctx.channel().remoteAddress(),
						Duration.ofNanos(System.nanoTime() - tlsHandshakeTimeStart),
						status);
			}
			else {
				recorder.recordTlsHandshakeTime(
						remoteAddress != null ? remoteAddress : ctx.channel().remoteAddress(),
						proxyAddress,
						Duration.ofNanos(System.nanoTime() - tlsHandshakeTimeStart),
						status);
			}
		}

		private void addListener(ChannelHandlerContext ctx) {
			if (!listenerAdded) {
				SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
				if (sslHandler != null) {
					listenerAdded = true;
					long tlsHandshakeTimeStart = System.nanoTime();
					sslHandler.handshakeFuture()
					          .addListener(f -> {
					              ctx.pipeline().remove(this);
					              recordTlsHandshakeTime(ctx, tlsHandshakeTimeStart, f.isSuccess() ? SUCCESS : ERROR);
					          });
				}
			}
		}
	}
}
