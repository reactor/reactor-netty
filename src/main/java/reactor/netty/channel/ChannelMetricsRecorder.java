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

import java.net.SocketAddress;
import java.time.Duration;

/**
 * Interface for collecting metrics on protocol level
 *
 * @author Violeta Georgieva
 */
public interface ChannelMetricsRecorder {

	/**
	 * Records the amount of the data that is received, in bytes
	 *
	 * @param remoteAddress The remote peer
	 * @param bytes The amount of the data that is received, in bytes
	 */
	void recordDataReceived(SocketAddress remoteAddress, long bytes);

	/**
	 * Records the amount of the data that is sent, in bytes
	 *
	 * @param remoteAddress The remote peer
	 * @param bytes The amount of the data that is sent, in bytes
	 */
	void recordDataSent(SocketAddress remoteAddress, long bytes);

	/**
	 * Increments the number of the errors that are occurred
	 *
	 * @param remoteAddress The remote peer
	 */
	void incrementErrorsCount(SocketAddress remoteAddress);

	/**
	 * Records the time that is spent for TLS handshake
	 *
	 * @param remoteAddress The remote peer
	 * @param time the time in nanoseconds that is spent for TLS handshake
	 * @param status the status of the operation
	 */
	void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status);

	/**
	 * Records the time that is spent for connecting to the remote address
	 * Relevant only when on the client
	 *
	 * @param remoteAddress The remote peer
	 * @param time the time in nanoseconds that is spent for connecting to the remote address
	 * @param status the status of the operation
	 */
	void recordConnectTime(SocketAddress remoteAddress, Duration time, String status);

	/**
	 * Records the time that is spent for resolving the remote address
	 * Relevant only when on the client
	 *
	 * @param remoteAddress The remote peer
	 * @param time the time in nanoseconds that is spent for resolving to the remote address
	 * @param status the status of the operation
	 */
	void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status);
}
