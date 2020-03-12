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
package reactor.netty.http;

import reactor.netty.channel.ChannelMetricsRecorder;

import java.net.SocketAddress;

/**
 * @author Violeta Georgieva
 */
public interface HttpMetricsRecorder extends ChannelMetricsRecorder {

	/**
	 * Records the amount of the data that is received, in bytes
	 *
	 * @param remoteAddress The remote peer
	 * @param uri the requested URI
	 * @param bytes The amount of the data that is received, in bytes
	 */
	void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes);

	/**
	 * Records the amount of the data that is sent, in bytes
	 *
	 * @param remoteAddress The remote peer
	 * @param uri the requested URI
	 * @param bytes The amount of the data that is sent, in bytes
	 */
	void recordDataSent(SocketAddress remoteAddress, String uri, long bytes);

	/**
	 * Increments the number of the errors that are occurred
	 *
	 * @param remoteAddress The remote peer
	 * @param uri the requested URI
	 */
	void incrementErrorsCount(SocketAddress remoteAddress, String uri);
}
