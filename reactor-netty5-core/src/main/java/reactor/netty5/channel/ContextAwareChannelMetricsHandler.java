/*
 * Copyright (c) 2021-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.channel;

import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.util.concurrent.Future;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import org.jspecify.annotations.Nullable;
import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;

import static reactor.netty5.Metrics.ERROR;
import static reactor.netty5.Metrics.SUCCESS;

/**
 * {@link AbstractChannelMetricsHandler} that propagates {@link ContextView}.
 *
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
		return new ContextAwareConnectMetricsHandler(recorder(), proxyAddress);
	}

	@Override
	public ChannelHandler tlsMetricsHandler() {
		return new TlsMetricsHandler(recorder, remoteAddress, proxyAddress);
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
			if (proxyAddress == null) {
				recorder().incrementErrorsCount(ops.currentContext(), address);
			}
			else {
				recorder().incrementErrorsCount(ops.currentContext(), address, proxyAddress);
			}
		}
		else if (connection instanceof ConnectionObserver connectionObserver) {
			if (proxyAddress == null) {
				recorder().incrementErrorsCount(connectionObserver.currentContext(), address);
			}
			else {
				recorder().incrementErrorsCount(connectionObserver.currentContext(), address, proxyAddress);
			}
		}
		else {
			super.recordException(ctx, address);
		}
	}

	@Override
	protected void recordRead(ChannelHandlerContext ctx, SocketAddress address, long bytes) {
		ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
		if (ops != null) {
			if (proxyAddress == null) {
				recorder().recordDataReceived(ops.currentContext(), address, bytes);
			}
			else {
				recorder().recordDataReceived(ops.currentContext(), address, proxyAddress, bytes);
			}
		}
		else {
			super.recordRead(ctx, address, bytes);
		}
	}

	@Override
	protected void recordWrite(ChannelHandlerContext ctx, SocketAddress address, long bytes) {
		ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
		if (ops != null) {
			if (proxyAddress == null) {
				recorder().recordDataSent(ops.currentContext(), address, bytes);
			}
			else {
				recorder().recordDataSent(ops.currentContext(), address, proxyAddress, bytes);
			}
		}
		else {
			super.recordWrite(ctx, address, bytes);
		}
	}

	static final class ContextAwareConnectMetricsHandler extends ChannelHandlerAdapter {

		final SocketAddress proxyAddress;
		final ContextAwareChannelMetricsRecorder recorder;

		ContextAwareConnectMetricsHandler(ContextAwareChannelMetricsRecorder recorder, @Nullable SocketAddress proxyAddress) {
			this.proxyAddress = proxyAddress;
			this.recorder = recorder;
		}

		@Override
		public Future<Void> connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress) {
			long connectTimeStart = System.nanoTime();
			return ctx.connect(remoteAddress, localAddress)
			          .addListener(future -> {
			              ctx.pipeline().remove(this);
			              recordConnectTime(ctx, remoteAddress, connectTimeStart, future.isSuccess() ? SUCCESS : ERROR);
			          });
		}

		void recordConnectTime(ChannelHandlerContext ctx, SocketAddress address, long connectTimeStart, String status) {
			Connection connection = Connection.from(ctx.channel());
			if (connection instanceof ConnectionObserver connectionObserver) {
				if (proxyAddress == null) {
					recorder.recordConnectTime(
							connectionObserver.currentContext(),
							address,
							Duration.ofNanos(System.nanoTime() - connectTimeStart),
							status);
				}
				else {
					recorder.recordConnectTime(
							connectionObserver.currentContext(),
							address,
							proxyAddress,
							Duration.ofNanos(System.nanoTime() - connectTimeStart),
							status);
				}
			}
			else {
				if (proxyAddress == null) {
					recorder.recordConnectTime(address, Duration.ofNanos(System.nanoTime() - connectTimeStart), status);
				}
				else {
					recorder.recordConnectTime(address, proxyAddress, Duration.ofNanos(System.nanoTime() - connectTimeStart), status);
				}
			}
		}
	}

	static final class TlsMetricsHandler extends ChannelMetricsHandler.TlsMetricsHandler {

		TlsMetricsHandler(ContextAwareChannelMetricsRecorder recorder, @Nullable SocketAddress remoteAddress,
				@Nullable SocketAddress proxyAddress) {
			super(recorder, remoteAddress, proxyAddress);
		}

		@Override
		protected void recordTlsHandshakeTime(ChannelHandlerContext ctx, long tlsHandshakeTimeStart, String status) {
			Connection connection = Connection.from(ctx.channel());
			if (connection instanceof ConnectionObserver connectionObserver) {
				if (proxyAddress == null) {
					((ContextAwareChannelMetricsRecorder) recorder).recordTlsHandshakeTime(
							connectionObserver.currentContext(),
							remoteAddress != null ? remoteAddress : ctx.channel().remoteAddress(),
							Duration.ofNanos(System.nanoTime() - tlsHandshakeTimeStart),
							status);
				}
				else {
					((ContextAwareChannelMetricsRecorder) recorder).recordTlsHandshakeTime(
							connectionObserver.currentContext(),
							remoteAddress != null ? remoteAddress : ctx.channel().remoteAddress(),
							proxyAddress,
							Duration.ofNanos(System.nanoTime() - tlsHandshakeTimeStart),
							status);
				}
			}
		}
	}
}
