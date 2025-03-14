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

/**
 * This class implements the {@link HttpConnectionLiveness} interface and provides
 * a mechanism to immediately close the HTTP connection without checking its liveness.
 *
 * <p>
 * The methods in this class are no-ops except for the {@code check} method, which
 * closes the connection.
 * </p>
 *
 * @author raccoonback
 * @since 1.2.5
 */
public final class HttpConnectionImmediateClose implements HttpConnectionLiveness {

	/**
	 * Constructs a new {@code HttpConnectionImmediateClose} instance.
	 */
	public HttpConnectionImmediateClose() {
	}

	/**
	 * Cancels the HTTP connection.
	 * This method is a no-op.
	 */
	@Override
	public void cancel() {
		// no op
	}

	/**
	 * Receives a message from the peer.
	 * This method is a no-op.
	 *
	 * @param msg the message received from the peer
	 */
	@Override
	public void receive(Object msg) {
		// no op
	}

	/**
	 * Checks the liveness of the connection and closes it immediately.
	 *
	 * @param ctx the {@link ChannelHandlerContext} of the connection
	 */
	@Override
	public void check(ChannelHandlerContext ctx) {
		ctx.close();
	}
}
