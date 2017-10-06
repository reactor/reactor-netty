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

package reactor.ipc.netty.tcp;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Wrap a {@link Connection} obtained from a {@link Mono} and offer methods to manage
 * its lifecycle in a blocking fashion.
 *
 * @author Simon Baslé
 */
public class BlockingNettyContext {

	private static final Logger LOG = Loggers.getLogger(BlockingNettyContext.class);

	private final Connection context;
	private final String     description;

	private Duration lifecycleTimeout;
	private Thread shutdownHook;

	public BlockingNettyContext(Mono<? extends Connection> contextAsync,
			String description) {
		this(contextAsync, description, Duration.ofSeconds(45));
	}

	public BlockingNettyContext(Mono<? extends Connection> contextAsync,
			String description, Duration lifecycleTimeout) {
		this.description = description;
		this.lifecycleTimeout = lifecycleTimeout;
		this.context = contextAsync
				.timeout(lifecycleTimeout, Mono.error(new TimeoutException(description + " couldn't be started within " + lifecycleTimeout.toMillis() + "ms")))
				.doOnNext(ctx -> LOG.info("Started {} on {}", description, ctx.address()))
				.block();
	}

	/**
	 * Change the lifecycle timeout applied to the {@link #shutdown()} operation (as this can
	 * only be called AFTER the {@link Connection} has been "started").
	 *
	 * @param timeout the new timeout to apply on shutdown.
	 */
	public void setLifecycleTimeout(Duration timeout) {
		this.lifecycleTimeout = timeout;
	}

	/**
	 * Get the {@link Connection} wrapped by this facade.
	 * @return the original Connection.
	 */
	public Connection getContext() {
		return context;
	}

	/**
	 * Return this server's port.
	 * @return The port the server is bound to.
	 */
	public int getPort() {
		return context.address().getPort();
	}

	/**
	 * Return the server's host String. That is, the hostname or in case the server was bound
	 * to a literal IP adress, the IP string representation (rather than performing a reverse-DNS
	 * lookup).
	 *
	 * @return the host string, without reverse DNS lookup
	 * @see Connection#address()
	 * @see InetSocketAddress#getHostString()
	 */
	public String getHost() {
		return context.address().getHostString();
	}

	/**
	 * Install a {@link Runtime#addShutdownHook(Thread) JVM shutdown hook} that will
	 * shutdown this {@link BlockingNettyContext} if the JVM is terminated externally.
	 * <p>
	 * The hook is removed if shutdown manually, and subsequent calls to this method are
	 * no-op.
	 */
	public void installShutdownHook() {
		//don't return the hook to discourage uninstalling it externally
		if (this.shutdownHook != null) {
			return;
		}
		this.shutdownHook = new Thread(this::shutdownFromJVM);
		Runtime.getRuntime().addShutdownHook(this.shutdownHook);
	}

	/**
	 * Remove a {@link Runtime#removeShutdownHook(Thread) JVM shutdown hook} if one was
	 * {@link #installShutdownHook() installed} by this {@link BlockingNettyContext}.
	 *
	 * @return true if there was a hook and it was removed, false otherwise.
	 */
	public boolean removeShutdownHook() {
		if (this.shutdownHook != null && Thread.currentThread() != this.shutdownHook) {
			Thread sdh = this.shutdownHook;
			this.shutdownHook = null;
			return Runtime.getRuntime().removeShutdownHook(sdh);
		}
		return false;
	}

	/**
	 * @return the current JVM shutdown hook. Shouldn't be passed to users.
	 */
	protected Thread getShutdownHook() {
		return this.shutdownHook;
	}

	/**
	 * Shut down the {@link Connection} and wait for its termination, up to the
	 * {@link #setLifecycleTimeout(Duration) lifecycle timeout}.
	 */
	public void shutdown() {
		if (context.isDisposed()) {
			return;
		}

		removeShutdownHook(); //only applies if not called from the hook's thread

		context.dispose();
		context.onDispose()
		       .doOnError(e -> LOG.error("Stopped {} on {} with an error {}", description, context.address(), e))
		       .doOnTerminate(() -> LOG.info("Stopped {} on {}", description, context.address()))
		       .timeout(lifecycleTimeout, Mono.error(new TimeoutException(description + " couldn't be stopped within " + lifecycleTimeout.toMillis() + "ms")))
		       .block();
	}

	protected void shutdownFromJVM() {
		if (context.isDisposed()) {
			return;
		}

		final String hookDesc = Thread.currentThread().toString();

		context.dispose();
		context.onDispose()
		       .doOnError(e -> LOG.error("Stopped {} on {} with an error {} from JVM hook {}",
				       description, context.address(), e, hookDesc))
		       .doOnTerminate(() -> LOG.info("Stopped {} on {} from JVM hook {}",
				       description, context.address(), hookDesc))
		       .timeout(lifecycleTimeout, Mono.error(new TimeoutException(description +
				       " couldn't be stopped within " + lifecycleTimeout.toMillis() + "ms")))
		       .block();
	}
}
