/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.netty.http;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.TcpResources;

/**
 * Hold the default Http resources
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public final class HttpResources extends TcpResources {

	/**
	 * Return the global HTTP resources for event loops and pooling
	 *
	 * @return the global HTTP resources for event loops and pooling
	 */
	public static HttpResources get() {
		return getOrCreate(httpResources, null, null, ON_HTTP_NEW, "http");
	}

	/**
	 * Update event loops resources and return the global HTTP resources
	 *
	 * @return the global HTTP resources
	 */
	public static HttpResources set(ConnectionProvider provider) {
		return getOrCreate(httpResources, null, provider, ON_HTTP_NEW, "http");
	}

	/**
	 * Update pooling resources and return the global HTTP resources
	 *
	 * @return the global HTTP resources
	 */
	public static HttpResources set(LoopResources loops) {
		return getOrCreate(httpResources, loops, null, ON_HTTP_NEW, "http");
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
			resources._dispose();
		}
	}

	/**
	 * Prepare to shutdown the global {@link HttpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones. This only
	 * occurs when the returned {@link Mono} is subscribed to.
	 *
	 * @return a {@link Mono} triggering the {@link #disposeLoopsAndConnections()} when subscribed to.
	 */
	public static Mono<Void> disposeLoopsAndConnectionsLater() {
		return Mono.defer(() -> {
			HttpResources resources = httpResources.getAndSet(null);
			if (resources != null) {
				return resources._disposeLater();
			}
			return Mono.empty();
		});
	}

	HttpResources(LoopResources loops, ConnectionProvider provider) {
		super(loops, provider);
	}

	static final AtomicReference<HttpResources>                          httpResources;
	static final BiFunction<LoopResources, ConnectionProvider, HttpResources> ON_HTTP_NEW;

	static {
		ON_HTTP_NEW = HttpResources::new;
		httpResources = new AtomicReference<>();
	}

}
