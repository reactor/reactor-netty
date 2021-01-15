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
package reactor.netty.quic;

import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelOperations;

/**
 * An inbound QUIC stream ready {@link ChannelOperations} with state management for FIN.
 * After sending FIN it's not possible anymore to write data, the stream will be terminated.
 *
 * @author Violeta Georgieva
 */
final class QuicInboundStreamOperations extends QuicStreamOperations {

	QuicInboundStreamOperations(Connection connection, ConnectionObserver listener) {
		super(connection, listener);
	}

	@Override
	protected void onOutboundComplete() {
		if (log.isDebugEnabled()) {
			log.debug("Outbound completed. Sending WRITE_FIN.");
		}

		sendFinNow(f -> terminate());
	}
}
