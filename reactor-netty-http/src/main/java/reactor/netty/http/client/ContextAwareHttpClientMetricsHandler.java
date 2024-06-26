/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.function.Function;

/**
 * {@link AbstractHttpClientMetricsHandler} that propagates {@link ContextView}.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
final class ContextAwareHttpClientMetricsHandler extends AbstractHttpClientMetricsHandler {

	final ContextAwareHttpClientMetricsRecorder recorder;

	ContextAwareHttpClientMetricsHandler(ContextAwareHttpClientMetricsRecorder recorder,
			SocketAddress remoteAddress,
			@Nullable SocketAddress proxyAddress,
			@Nullable Function<String, String> uriTagValue) {
		super(remoteAddress, proxyAddress, uriTagValue);
		this.recorder = recorder;
	}

	ContextAwareHttpClientMetricsHandler(ContextAwareHttpClientMetricsHandler copy) {
		super(copy);
		this.recorder = copy.recorder;
	}

	@Override
	protected ContextAwareHttpClientMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	protected void recordException(ChannelHandlerContext ctx) {
		if (contextView != null) {
			if (proxyAddress == null) {
				recorder().incrementErrorsCount(contextView, remoteAddress, path);
			}
			else {
				recorder().incrementErrorsCount(contextView, remoteAddress, proxyAddress, path);
			}
		}
		else {
			super.recordException(ctx);
		}
	}

	@Override
	protected void recordWrite(SocketAddress address) {
		if (contextView != null) {
			if (proxyAddress == null) {
				recorder.recordDataSentTime(contextView, address, path, method,
						Duration.ofNanos(System.nanoTime() - dataSentTime));

				recorder.recordDataSent(contextView, address, path, dataSent);
			}
			else {
				recorder.recordDataSentTime(contextView, address, proxyAddress, path, method,
						Duration.ofNanos(System.nanoTime() - dataSentTime));

				recorder.recordDataSent(contextView, address, proxyAddress, path, dataSent);
			}
		}
		else {
			super.recordWrite(address);
		}
	}

	@Override
	protected void recordRead(Channel channel, SocketAddress address) {
		if (contextView != null) {
			if (proxyAddress == null) {
				recorder.recordDataReceivedTime(contextView, address, path, method, status,
						Duration.ofNanos(System.nanoTime() - dataReceivedTime));

				recorder.recordResponseTime(contextView, address, path, method, status,
						Duration.ofNanos(System.nanoTime() - dataSentTime));

				recorder.recordDataReceived(contextView, address, path, dataReceived);
			}
			else {
				recorder.recordDataReceivedTime(contextView, address, proxyAddress, path, method, status,
						Duration.ofNanos(System.nanoTime() - dataReceivedTime));

				recorder.recordResponseTime(contextView, address, proxyAddress, path, method, status,
						Duration.ofNanos(System.nanoTime() - dataSentTime));

				recorder.recordDataReceived(contextView, address, proxyAddress, path, dataReceived);
			}
		}
		else {
			super.recordRead(channel, address);
		}
	}
}
