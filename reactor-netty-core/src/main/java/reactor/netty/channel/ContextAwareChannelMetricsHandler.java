/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;

import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.SUCCESS;

/**
 * @author Violeta Georgieva
 * @since 1.0.8
 */
final class ContextAwareChannelMetricsHandler extends AbstractChannelMetricsHandler {

	final ContextAwareChannelMetricsRecorder recorder;

	ContextAwareChannelMetricsHandler(ContextAwareChannelMetricsRecorder recorder,
			@Nullable SocketAddress remoteAddress, boolean onServer) {
		super(remoteAddress, onServer);
		this.recorder = recorder;
	}

	@Override
	public ChannelHandler connectMetricsHandler() {
		return new ContextAwareConnectMetricsHandler(recorder());
	}

	@Override
	public ContextAwareChannelMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	protected void recordException(ChannelHandlerContext ctx, SocketAddress address) {
		Connection connection = Connection.from(ctx.channel());
		ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
		if (ops != null) {
			recorder().incrementErrorsCount(ops.currentContext(), address);
		}
		else if (connection instanceof ConnectionObserver) {
			recorder().incrementErrorsCount(((ConnectionObserver) connection).currentContext(), address);
		}
		else {
			super.recordException(ctx, address);
		}
	}

	@Override
	protected void recordRead(ChannelHandlerContext ctx, SocketAddress address, long bytes) {
		ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
		if (ops != null) {
			recorder().recordDataReceived(ops.currentContext(), address, bytes);
		}
		else {
			super.recordRead(ctx, address, bytes);
		}
	}

	@Override
	protected void recordWrite(ChannelHandlerContext ctx, SocketAddress address, long bytes) {
		ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
		if (ops != null) {
			recorder().recordDataSent(ops.currentContext(), address, bytes);
		}
		else {
			super.recordWrite(ctx, address, bytes);
		}
	}

	static final class ContextAwareConnectMetricsHandler extends ChannelOutboundHandlerAdapter {

		final ContextAwareChannelMetricsRecorder recorder;

		ContextAwareConnectMetricsHandler(ContextAwareChannelMetricsRecorder recorder) {
			this.recorder = recorder;
		}

		@Override
		public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
				SocketAddress localAddress, ChannelPromise promise) throws Exception {
			long connectTimeStart = System.nanoTime();
			super.connect(ctx, remoteAddress, localAddress, promise);
			promise.addListener(future -> {
				ctx.pipeline().remove(this);
				recordConnectTime(ctx, remoteAddress, connectTimeStart, future.isSuccess() ? SUCCESS : ERROR);
			});
		}

		void recordConnectTime(ChannelHandlerContext ctx, SocketAddress address, long connectTimeStart, String status) {
			Connection connection = Connection.from(ctx.channel());
			if (connection instanceof ConnectionObserver) {
				recorder.recordConnectTime(
						((ConnectionObserver) connection).currentContext(),
						address,
						Duration.ofNanos(System.nanoTime() - connectTimeStart),
						status);
			}
			else {
				recorder.recordConnectTime(address, Duration.ofNanos(System.nanoTime() - connectTimeStart), status);
			}
		}
	}
}
