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

package reactor.netty;

import java.util.function.Consumer;

import io.netty.channel.Channel;
import reactor.core.publisher.Flux;

/**
 * An inbound-traffic API delegating to an underlying {@link Channel}
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public interface NettyInbound {


	/**
	 * A {@link Flux} extension that allows for extra decoding operators
	 *
	 * @return a new {@link ByteBufFlux}
	 */
	ByteBufFlux receive();


	/**
	 * a {@literal Object} inbound {@link Flux}
	 *
	 * @return a {@literal Object} inbound {@link Flux}
	 */
	Flux<?> receiveObject();

	/**
	 * Calls the passed callback with a {@link Connection} to operate on the
	 * underlying {@link Channel} state. This allows for chaining inbound API.
	 *
	 * @param withConnection connection callback
	 *
	 * @return the {@link Connection}
	 */
	NettyInbound withConnection(Consumer<? super Connection> withConnection);
}
