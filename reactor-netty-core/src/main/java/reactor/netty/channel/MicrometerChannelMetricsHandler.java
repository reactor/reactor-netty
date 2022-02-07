/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.api.instrument.Tags;
import io.micrometer.api.instrument.observation.Observation;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import reactor.netty.observability.ReactorNettyHandlerContext;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;

import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.ERROR;
//import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.Metrics.formatSocketAddress;

/**
 * {@link ChannelHandler} for collecting metrics on protocol level.
 * This class is based on {@link ChannelMetricsHandler}, but it utilizes Micrometer's {@link Observation}.
 *
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
public class MicrometerChannelMetricsHandler extends AbstractChannelMetricsHandler {
	static final io.micrometer.api.instrument.MeterRegistry REGISTRY = io.micrometer.api.instrument.Metrics.globalRegistry;
	// TODO
	static {
		REGISTRY.withTimerObservationHandler();
	}

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

	// ConnectMetricsHandler is Observation.Context and ChannelOutboundHandler in order to reduce allocations,
	// this is invoked on every connection establishment
	// This handler is not shared and as such it is different object per connection.
	static final class ConnectMetricsHandler extends Observation.Context implements ReactorNettyHandlerContext, ChannelOutboundHandler {
		static final String CONTEXTUAL_NAME = "connect";
		static final String TYPE = "client";

		final MicrometerChannelMetricsRecorder recorder;

		// remote address and status are not known beforehand
		String remoteAddress;
		String status;

		ConnectMetricsHandler(MicrometerChannelMetricsRecorder recorder) {
			this.recorder = recorder;
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
			put(SocketAddress.class, remoteAddress);
			this.remoteAddress = formatSocketAddress(remoteAddress);
			Observation observation = Observation.start(recorder.name() + CONNECT_TIME, this, REGISTRY);
			ctx.connect(remoteAddress, localAddress, promise)
			   .addListener(future -> {
			       ctx.pipeline().remove(this);

			       status = future.isSuccess() ? SUCCESS : ERROR;

			       observation.stop();
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
		public String getContextualName() {
			return CONTEXTUAL_NAME;
		}

		@Override
		public Tags getHighCardinalityTags() {
			// TODO cache
			return Tags.of(ConnectObservations.ConnectTimeHighCardinalityTags.REACTOR_NETTY_STATUS.of(status),
			               ConnectObservations.ConnectTimeHighCardinalityTags.REACTOR_NETTY_TYPE.of(TYPE),
			               ConnectObservations.ConnectTimeHighCardinalityTags.REACTOR_NETTY_PROTOCOL.of(recorder.protocol()));
		}

		@Override
		public Tags getLowCardinalityTags() {
			// TODO cache
			return Tags.of(ConnectObservations.ConnectTimeLowCardinalityTags.REMOTE_ADDRESS.of(remoteAddress),
			               ConnectObservations.ConnectTimeLowCardinalityTags.STATUS.of(status));
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
