/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.resources.PoolResources;
import reactor.ipc.netty.tcp.TcpResources;

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
	public static HttpResources set(PoolResources pools) {
		return getOrCreate(httpResources, null, pools, ON_HTTP_NEW, "http");
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
		HttpResources resources = httpResources.getAndSet(null);
		if (resources != null) {
			resources._dispose();
		}
		return getOrCreate(httpResources, null, null, ON_HTTP_NEW, "http");
	}

	HttpResources(LoopResources loops, PoolResources pools) {
		super(loops, pools);
	}

	static final AtomicReference<HttpResources>                          httpResources;
	static final BiFunction<LoopResources, PoolResources, HttpResources> ON_HTTP_NEW;

	static {
		ON_HTTP_NEW = HttpResources::new;
		httpResources = new AtomicReference<>();
	}

}
