/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty;

import java.net.InetSocketAddress;
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import reactor.core.Cancellation;
import reactor.core.publisher.Mono;

/**
 * Hold contextual information for each {@link NettyConnector#newHandler(BiFunction)}
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public interface NettyContext extends Cancellation {

	/**
	 * Return remote address if client {@link NettyContext} otherwise local address if
	 * server.
	 *
	 * @return remote or local {@link InetSocketAddress}
	 */
	InetSocketAddress address();

	/**
	 * Return the underlying {@link Channel}
	 * @return the underlying {@link Channel}
	 */
	Channel channel();


	/**
	 * Return an observing {@link Mono} terminating with success when shutdown
	 * successfully
	 * or error.
	 *
	 * @return a {@link Mono} terminating with success if shutdown successfully or error
	 */
	Mono<Void> onClose();
}
