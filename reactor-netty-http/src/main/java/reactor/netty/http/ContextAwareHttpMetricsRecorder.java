/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import reactor.netty.channel.ContextAwareChannelMetricsRecorder;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.net.SocketAddress;

/**
 * {@link ContextView} aware class for recording metrics for HTTP protocol.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
public abstract class ContextAwareHttpMetricsRecorder extends ContextAwareChannelMetricsRecorder implements HttpMetricsRecorder {

	/**
	 * Increments the number of the errors that are occurred.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux
	 * @param remoteAddress The remote peer
	 * @param uri The requested URI
	 */
	public abstract void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress, String uri);

	/**
	 * Records the amount of the data that is received, in bytes.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux
	 * @param remoteAddress The remote peer
	 * @param uri The requested URI
	 * @param bytes The amount of the data that is received, in bytes
	 */
	public abstract void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes);

	/**
	 * Records the amount of the data that is sent, in bytes.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux
	 * @param remoteAddress The remote peer
	 * @param uri The requested URI
	 * @param bytes The amount of the data that is sent, in bytes
	 */
	public abstract void recordDataSent(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes);

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		recordDataReceived(Context.empty(), remoteAddress, uri, bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		recordDataSent(Context.empty(), remoteAddress, uri, bytes);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		incrementErrorsCount(Context.empty(), remoteAddress, uri);
	}
}
