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
package reactor.netty.channel;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
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
		return new ConnectMetricsHandler(recorder());
	}

	@Override
	public ChannelMetricsRecorder recorder() {
		return recorder;
	}

	static final class ConnectMetricsHandler extends ChannelOutboundHandlerAdapter {

		final ChannelMetricsRecorder recorder;

		ConnectMetricsHandler(ChannelMetricsRecorder recorder) {
			this.recorder = recorder;
		}

		@Override
		public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
				SocketAddress localAddress, ChannelPromise promise) throws Exception {
			long connectTimeStart = System.nanoTime();
			super.connect(ctx, remoteAddress, localAddress, promise);
			promise.addListener(future -> {
				ctx.pipeline().remove(this);

				recorder.recordConnectTime(
						remoteAddress,
						Duration.ofNanos(System.nanoTime() - connectTimeStart),
						future.isSuccess() ? SUCCESS :  ERROR);
			});
		}
	}
}
