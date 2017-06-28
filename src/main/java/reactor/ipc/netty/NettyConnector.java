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

import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.connector.Connector;
import reactor.ipc.netty.tcp.BlockingNettyContext;

/**
 * A Netty {@link Connector}
 * @param <INBOUND> incoming traffic API such as server request or client response
 * @param <OUTBOUND> outgoing traffic API such as server response or client request
 * @author Stephane Maldini
 * @since 0.6
 */
public interface NettyConnector<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
		extends Connector<ByteBuf, ByteBuf, INBOUND, OUTBOUND> {

	@Override
	Mono<? extends NettyContext> newHandler(BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> ioHandler);

	default <T extends BiFunction<INBOUND, OUTBOUND, ? extends Publisher<Void>>>
	BlockingNettyContext start(T handler) {
		return new BlockingNettyContext(newHandler(handler), getClass().getSimpleName());
	}

	default <T extends BiFunction<INBOUND, OUTBOUND, ? extends Publisher<Void>>>
	void startAndAwait(T handler, @Nullable Consumer<BlockingNettyContext> onStart) {
		BlockingNettyContext facade = new BlockingNettyContext(newHandler(handler), getClass().getSimpleName());

		if (onStart != null) {
			onStart.accept(facade);
		}
		Runtime.getRuntime().addShutdownHook(new Thread(facade::stop));

		facade.getContext()
		      .onClose()
		      .block();
	}
}
