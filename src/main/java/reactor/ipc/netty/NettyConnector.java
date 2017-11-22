/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.tcp.BlockingNettyContext;

/**
 * A Netty connector is an inbound/outbound factory sharing configuration but usually no
 * runtime
 * (connection...) state at the exception of shared connection pool setups. Subscribing
 * to the returned {@link Mono} will effectively
 * create a new stateful "client" or "server" socket depending on the implementation.
 * It might also be working on top of a socket pool or connection pool as well, but the
 * state should be safely handled by the pool itself.
 * <p>
 * <p>Clients or Receivers will onSubscribe when their connection is established. They
 * will complete when the unique returned closing {@link Publisher} completes itself or if
 * the connection is remotely terminated. Calling the returned {@link
 * Disposable#dispose()} from {@link Mono#subscribe()} will terminate the subscription
 * and underlying connection from the local peer.
 * <p>
 * <p>Servers or Producers will onSubscribe when their socket is bound locally. They will
 * never complete as many {@link Publisher} close selectors will be expected. Disposing
 * the returned {@link Mono} will safely call shutdown.
 *
 * @param <INBOUND> incoming traffic API such as server request or client response
 * @param <OUTBOUND> outgoing traffic API such as server response or client request
 * @author Stephane Maldini
 * @since 0.6
 */
public interface NettyConnector<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound> {


	/**
	 * Prepare a {@link BiFunction} IO handler that will react on a new connected state
	 * each
	 * time
	 * the returned  {@link Mono} is subscribed. This {@link NettyConnector} shouldn't assume
	 * any state related to the individual created/cleaned resources.
	 * <p>
	 * The IO handler will return {@link Publisher} to signal when to terminate the
	 * underlying resource channel.
	 *
	 * @param ioHandler the in/out callback returning a closing publisher
	 *
	 * @return a {@link Mono} completing with a {@link Disposable} token to dispose
	 * the active handler (server, client connection...) or failing with the connection
	 * error.
	 */
	Mono<? extends Connection> newHandler(BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> ioHandler);

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
	 * Start a Client or Server in a blocking fashion, and wait for it to finish initializing.
	 * The returned {@link BlockingNettyContext} class offers a simplified API around operating
	 * the client/server in a blocking fashion, including to {@link BlockingNettyContext#shutdown() shut it down}.
	 *
	 * @param handler the handler to start the client or server with.
	 * @param timeout wait for Client/Server to start for the specified timeout.
	 * @param <T>
	 * @return a {@link BlockingNettyContext}
	 */
	default <T extends BiFunction<INBOUND, OUTBOUND, ? extends Publisher<Void>>>
	BlockingNettyContext start(T handler, Duration timeout) {
		return new BlockingNettyContext(newHandler(handler), getClass().getSimpleName(), timeout);
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
	 */
	default <T extends BiFunction<INBOUND, OUTBOUND, ? extends Publisher<Void>>>
	void startAndAwait(T handler) {
		startAndAwait(handler, null);
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
	 * initializing.
	 */
	default <T extends BiFunction<INBOUND, OUTBOUND, ? extends Publisher<Void>>>
	void startAndAwait(T handler, @Nullable Consumer<BlockingNettyContext> onStart) {
		BlockingNettyContext facade = new BlockingNettyContext(newHandler(handler), getClass().getSimpleName());

		facade.installShutdownHook();

		if (onStart != null) {
			onStart.accept(facade);
		}

		facade.getContext()
		      .onDispose()
		      .block();
	}
}
