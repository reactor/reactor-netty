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

	/**
	 * Start a Client or Server in a blocking fashion, and wait for it to finish initializing.
	 * The returned {@link BlockingNettyContext} class offers a simplified API around operating
	 * the client/server in a blocking fashion, including to {@link BlockingNettyContext#shutdown() shut it down}.
	 *
	 * @param handler the handler to start the client or server with.
	 * @param <T>
	 * @return a {@link BlockingNettyContext}
	 */
	default <T extends BiFunction<INBOUND, OUTBOUND, ? extends Publisher<Void>>>
	BlockingNettyContext start(T handler) {
		return new BlockingNettyContext(newHandler(handler), getClass().getSimpleName());
	}

	/**
	 * Start a Client or Server in a fully blocking fashion, not only waiting for it to
	 * initialize but also blocking during the full lifecycle of the client/server.
	 * Since most servers will be long-lived, this is more adapted to running a server
	 * out of a main method, only allowing shutdown of the servers through sigkill.
	 * <p>
	 * Note that a {@link Runtime#addShutdownHook(Thread) JVM shutdown hook} is added
	 * by this method in order to properly disconnect the client/server upon receiving
	 * a sigkill signal.
	 *
	 * @param handler the handler to execute.
	 * @param onStart an optional callback to be invoked once the client/server has finished
	 * initializing (see {@link #startAndAwait(BiFunction, Consumer)}).
	 */
	default <T extends BiFunction<INBOUND, OUTBOUND, ? extends Publisher<Void>>>
	void startAndAwait(T handler, @Nullable Consumer<BlockingNettyContext> onStart) {
		BlockingNettyContext facade = new BlockingNettyContext(newHandler(handler), getClass().getSimpleName());

		if (onStart != null) {
			onStart.accept(facade);
		}
		Runtime.getRuntime().addShutdownHook(new Thread(facade::shutdown));

		facade.getContext()
		      .onClose()
		      .block();
	}
}
