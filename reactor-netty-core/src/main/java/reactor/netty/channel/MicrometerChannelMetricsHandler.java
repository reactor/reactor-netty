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
package reactor.netty.channel;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;

import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.Metrics.formatSocketAddress;

/**
 * {@link ChannelHandler} for collecting metrics on protocol level.
 * This class is based on {@link ChannelMetricsHandler}, but it utilizes Micrometer's {@link Timer.Sample}.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
public class MicrometerChannelMetricsHandler extends AbstractChannelMetricsHandler {

	final MicrometerChannelMetricsRecorder recorder;

	MicrometerChannelMetricsHandler(MicrometerChannelMetricsRecorder recorder, @Nullable SocketAddress remoteAddress, boolean onServer) {
		super(remoteAddress, onServer);
		this.recorder = recorder;
	}

	@Override
	public ChannelHandler connectMetricsHandler() {
		return new ConnectMetricsHandler(recorder);
	}

	@Override
	public MicrometerChannelMetricsRecorder recorder() {
		return recorder;
	}

	// ConnectMetricsHandler is Timer.HandlerContext and ChannelOutboundHandler in order to reduce allocations,
	// this is invoked on every connection establishment
	static final class ConnectMetricsHandler extends Timer.HandlerContext implements ChannelOutboundHandler {

		final Timer.Builder connectTimeBuilder;

		// remote address and status are not known beforehand
		String remoteAddress;
		String status;

		ConnectMetricsHandler(MicrometerChannelMetricsRecorder recorder) {
			this.connectTimeBuilder =
					Timer.builder(recorder.name() + CONNECT_TIME)
					     .description("Time spent for connecting to the remote address");
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.bind(localAddress, promise);
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.close(promise);
		}

		@Override
		public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
				SocketAddress localAddress, ChannelPromise promise) throws Exception {
			// TODO
			// Cannot invoke the recorder any more:
			// 1. The recorder is one instance only, it is invoked for all connection establishments that can happen
			// 2. The recorder does not have knowledge about connection establishment lifecycle
			// 3. There is no connection so we cannot hold the context information in the Channel
			//
			// Move the implementation from the recorder here
			//
			// Important:
			// Cannot cache the Timer anymore - need to test the performance
			// Can we use sample.stop(Timer)
			this.remoteAddress = formatSocketAddress(remoteAddress);
			Timer.Sample sample = Timer.start(REGISTRY, this);
			ctx.connect(remoteAddress, localAddress, promise)
			   .addListener(future -> {
			       ctx.pipeline().remove(this);

			       status = future.isSuccess() ? SUCCESS : ERROR;

			       sample.stop(connectTimeBuilder);
			});
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.deregister(promise);
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.disconnect(promise);
		}

		@Override
		@SuppressWarnings("deprecation")
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			ctx.fireExceptionCaught(cause);
		}

		@Override
		public void flush(ChannelHandlerContext ctx) {
			ctx.flush();
		}

		@Override
		public Tags getLowCardinalityTags() {
			return Tags.of(REMOTE_ADDRESS, remoteAddress, STATUS, status);
		}

		@Override
		public void handlerAdded(ChannelHandlerContext ctx) {
			// noop
		}

		@Override
		public void handlerRemoved(ChannelHandlerContext ctx) {
			// noop
		}

		@Override
		public void read(ChannelHandlerContext ctx) {
			ctx.read();
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(msg, promise);
		}
	}
}
