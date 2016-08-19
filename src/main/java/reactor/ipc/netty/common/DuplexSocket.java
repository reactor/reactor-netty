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

package reactor.ipc.netty.common;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.Channel;

/**
 * Abstract base class that implements common functionality shared by clients and servers.
 * <p> A DuplexSocket is network component with start and shutdown capabilities. On Start it will
 * require a {@link Function} to process the incoming {@link Channel},
 * regardless of being a server or a client.
 *
 * @author Stephane Maldini
 */
public abstract class DuplexSocket<IN, OUT, CONN extends Channel<IN, OUT>>  {

	public static final int    DEFAULT_PORT         =
			System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 12012;
	public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";

	protected final AtomicBoolean  started = new AtomicBoolean();

	/**
	 * Is shutdown
	 * @return is shutdown
	 */
	public boolean isShutdown() {
		return !started.get();
	}

	/**
	 * Shutdown this {@literal DuplexSocket} and complete the returned {@link Mono<Void>}
	 * when shut down.
	 *
	 * @return a {@link Mono<Void>} that will be complete when the {@link DuplexSocket} is shutdown
	 */
	public final Mono<Void> shutdown() {
		if (started.compareAndSet(true, false)) {
			return doShutdown();
		}
		return Mono.empty();
	}

	/* Implementation Contract */

	/**
	 * @see this#shutdown()
	 * @throws InterruptedException
	 */
	public final void shutdownAndAwait()
			throws InterruptedException {
		shutdown().block();
	}

	/**
	 * Start this {@literal DuplexSocket}.
	 *
	 * @param handler
	 *
	 * @return a {@link Mono<Void>} that will be complete when the {@link DuplexSocket} is started
	 */
	public final Mono<Void> start(
			final Function<? super CONN, ? extends Publisher<Void>> handler) {

		if (!started.compareAndSet(false, true) && shouldFailOnStarted()) {
			throw new IllegalStateException("DuplexSocket already started");
		}

		return doStart(handler);
	}

	/**
	 * @see this#start(Function)
	 *
	 * @param handler
	 *
	 * @throws InterruptedException
	 */
	public final void startAndAwait(final Function<? super CONN, ? extends Publisher<Void>> handler)
			throws InterruptedException {
		start(handler).block();
	}

	protected abstract Mono<Void> doStart(
			Function<? super CONN, ? extends Publisher<Void>> handler);

	protected abstract Mono<Void> doShutdown();

	protected boolean shouldFailOnStarted() {
		return true;
	}

}
