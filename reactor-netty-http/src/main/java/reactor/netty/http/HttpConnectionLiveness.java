/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.ChannelHandlerContext;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty.ReactorNetty.format;

/**
 * Interface for checking the liveness of an HTTP connection.
 * This interface provides methods to cancel started probes or any active work,
 * process messages received from the remote peer, and start probing the connection for the liveness.
 *
 * @author raccoonback
 * @author Violeta Georgieva
 * @since 1.2.12
 */
public interface HttpConnectionLiveness {

	Logger log = Loggers.getLogger(HttpConnectionLiveness.class);

	/**
	 * {@link HttpConnectionLiveness} that immediately closes the HTTP connection on read idle, without starting any probing.
	 */
	HttpConnectionLiveness CLOSE = new HttpConnectionLiveness() {

		@Override
		public void cancel() {
			// no op
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void check(ChannelHandlerContext ctx) {
			if (log.isDebugEnabled()) {
				log.debug(format(ctx.channel(), "Connection was idle, as per configuration the connection will be closed."));
			}
			// FutureReturnValueIgnored is deliberate
			ctx.close();
		}

		@Override
		public void receive(Object msg) {
			// no op
		}
	};

	/**
	 * Cancels started probes or any active work.
	 */
	void cancel();

	/**
	 * Closes or starts probing the connection for the liveness.
	 *
	 * @param ctx the {@link ChannelHandlerContext} of the connection
	 */
	void check(ChannelHandlerContext ctx);

	/**
	 * Processes messages received from the remote peer.
	 *
	 * @param msg the message received from the remote peer
	 */
	void receive(Object msg);
}
