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
 * Interface for checking the liveness of an HTTP connection.
 * This interface provides methods to cancel the HTTP connection,
 * process messages received from the peer, and start checking the liveness of the connection.
 *
 * @author raccoonback
 * @since 1.2.5
 */
public interface HttpConnectionLiveness {

	/**
	 * Cancel Http Connection by protocol.
	 *
	 */
	void cancel();

	/**
	 * Receive the message received from peer.
	 *
	 */
	void receive(Object msg);

	/**
	 * Start checking liveness.
	 *
	 */
	void check(ChannelHandlerContext ctx);
}
