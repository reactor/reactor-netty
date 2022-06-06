/*
 * Copyright (c) 2011-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.netty.resolver.AddressResolverGroup;
import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.TcpResources;

import static java.util.Objects.requireNonNull;

/**
 * Hold the default HTTP/1.x resources
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public final class HttpResources extends TcpResources {

	/**
	 * Shutdown the global {@link HttpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones.
	 * This method is NOT blocking. It is implemented as fire-and-forget.
	 * Use {@link #disposeLoopsAndConnectionsLater()} when you need to observe
	 * the final status of the operation, combined with {@link Mono#block()}
	 * if you need to synchronously wait for the underlying resources to be disposed.
	 */
	public static void disposeLoopsAndConnections() {
		HttpResources resources = httpResources.getAndSet(null);
		if (resources != null) {
			ConnectionProvider provider = resources.http2ConnectionProvider.get();
			if (provider != null) {
				provider.dispose();
			}
			resources._dispose();
		}
	}

	/**
	 * Prepare to shutdown the global {@link HttpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones. This only
	 * occurs when the returned {@link Mono} is subscribed to.
	 * The quiet period will be {@code 2s} and the timeout will be {@code 15s}
	 *
	 * @return a {@link Mono} triggering the {@link #disposeLoopsAndConnections()} when subscribed to.
	 */
	public static Mono<Void> disposeLoopsAndConnectionsLater() {
		return disposeLoopsAndConnectionsLater(Duration.ofSeconds(LoopResources.DEFAULT_SHUTDOWN_QUIET_PERIOD),
				Duration.ofSeconds(LoopResources.DEFAULT_SHUTDOWN_TIMEOUT));
	}

	/**
	 * Prepare to shutdown the global {@link HttpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones. This only
	 * occurs when the returned {@link Mono} is subscribed to.
	 * It is guaranteed that the disposal of the underlying LoopResources will not happen before
	 * {@code quietPeriod} is over. If a task is submitted during the {@code quietPeriod},
	 * it is guaranteed to be accepted and the {@code quietPeriod} will start over.
	 *
	 * @param quietPeriod the quiet period as described above
	 * @param timeout the maximum amount of time to wait until the disposal of the underlying
	 * LoopResources regardless if a task was submitted during the quiet period
	 * @return a {@link Mono} triggering the {@link #disposeLoopsAndConnections()} when subscribed to.
	 * @since 0.9.3
	 */
	public static Mono<Void> disposeLoopsAndConnectionsLater(Duration quietPeriod, Duration timeout) {
		requireNonNull(quietPeriod, "quietPeriod");
		requireNonNull(timeout, "timeout");
		return Mono.defer(() -> {
			HttpResources resources = httpResources.getAndSet(null);
			if (resources != null) {
				ConnectionProvider provider = resources.http2ConnectionProvider.get();
				Mono<Void> disposeProvider = Mono.empty();
				if (provider != null) {
					disposeProvider = provider.disposeLater();
				}
				return Mono.when(disposeProvider, resources._disposeLater(quietPeriod, timeout));
			}
			return Mono.empty();
		});
	}

	/**
	 * Return the global HTTP resources for event loops and pooling
	 *
	 * @return the global HTTP resources for event loops and pooling
	 */
	public static HttpResources get() {
		return getOrCreate(httpResources, null, null, ON_HTTP_NEW, "http");
	}

	/**
	 * Reset http resources to default and return its instance
	 *
	 * @return the global HTTP resources
	 */
	public static HttpResources reset() {
		disposeLoopsAndConnections();
		return getOrCreate(httpResources, null, null, ON_HTTP_NEW, "http");
	}

	/**
	 * Update pooling resources and return the global HTTP/1.x resources.
	 * Note: The previous {@link ConnectionProvider} will be disposed.
	 *
	 * @param provider a new {@link ConnectionProvider} to replace the current
	 * @return the global HTTP/1.x resources
	 */
	public static HttpResources set(ConnectionProvider provider) {
		return getOrCreate(httpResources, null, provider, ON_HTTP_NEW, "http");
	}

	/**
	 * Update event loops resources and return the global HTTP/1.x resources.
	 * Note: The previous {@link LoopResources} will be disposed.
	 *
	 * @param loops a new {@link LoopResources} to replace the current
	 * @return the global HTTP/1.x resources
	 */
	public static HttpResources set(LoopResources loops) {
		return getOrCreate(httpResources, loops, null, ON_HTTP_NEW, "http");
	}

	final AtomicReference<ConnectionProvider> http2ConnectionProvider;

	HttpResources(LoopResources loops, ConnectionProvider provider) {
		super(loops, provider);
		http2ConnectionProvider = new AtomicReference<>();
	}

	@Override
	public void disposeWhen(SocketAddress remoteAddress) {
		ConnectionProvider provider = http2ConnectionProvider.get();
		if (provider != null) {
			provider.disposeWhen(remoteAddress);
		}
		super.disposeWhen(remoteAddress);
	}

	@Override
	public AddressResolverGroup<?> getOrCreateDefaultResolver() {
		return super.getOrCreateDefaultResolver();
	}

	/**
	 * Safely checks whether a {@link ConnectionProvider} for HTTP/2 traffic exists
	 * and proceed with a creation if it does not exist.
	 *
	 * @param create the create function provides the current {@link ConnectionProvider} for HTTP/1.1 traffic
	 * in case some {@link ConnectionProvider} configuration is needed.
	 * @return an existing or new {@link ConnectionProvider} for HTTP/2 traffic
	 * @since 1.0.16
	 */
	public ConnectionProvider getOrCreateHttp2ConnectionProvider(Function<ConnectionProvider, ConnectionProvider> create) {
		ConnectionProvider provider = http2ConnectionProvider.get();
		if (provider == null) {
			ConnectionProvider newProvider = create.apply(this);
			if (!http2ConnectionProvider.compareAndSet(null, newProvider)) {
				newProvider.dispose();
			}
			provider = getOrCreateHttp2ConnectionProvider(create);
		}
		return provider;
	}

	static final BiFunction<LoopResources, ConnectionProvider, HttpResources> ON_HTTP_NEW;

	static final AtomicReference<HttpResources>                          httpResources;

	static {
		ON_HTTP_NEW = HttpResources::new;
		httpResources = new AtomicReference<>();
	}

}
