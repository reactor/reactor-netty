/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import reactor.netty.http.HttpResources;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.TcpResources;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * Hold the default HTTP/2 resources
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
final class Http2Resources extends TcpResources {

	/**
	 * Return the global HTTP/2 resources for event loops and pooling
	 *
	 * @return the global HTTP/2 resources for event loops and pooling
	 */
	public static Http2Resources get() {
		return getOrCreate(http2Resources, null, null, ON_HTTP2_NEW, "http2");
	}

	/**
	 * Update pooling resources and return the global HTTP/2 resources
	 *
	 * @return the global HTTP/2 resources
	 */
	public static Http2Resources set(ConnectionProvider provider) {
		return getOrCreate(http2Resources, null, newConnectionProvider(provider), ON_HTTP2_NEW, "http2");
	}

	Http2Resources(LoopResources loops, ConnectionProvider provider) {
		super(loops, provider);
	}

	static ConnectionProvider newConnectionProvider(ConnectionProvider parent) {
		Builder builder =
				ConnectionProvider.builder("http2")
				                  .maxConnections(parent.maxConnections())
				                  .pendingAcquireMaxCount(-1);
		if (parent.maxConnectionsPerHost() != null) {
			parent.maxConnectionsPerHost()
			      .forEach((address, maxConn) -> builder.forRemoteHost(address, spec -> spec.maxConnections(maxConn)));
		}

		return new Http2ConnectionProvider(parent, builder);
	}

	static final AtomicReference<Http2Resources> http2Resources;

	static final BiFunction<LoopResources, ConnectionProvider, Http2Resources> ON_HTTP2_NEW;

	static {
		ON_HTTP2_NEW = Http2Resources::new;
		http2Resources = new AtomicReference<>(new Http2Resources(HttpResources.get(), newConnectionProvider(HttpResources.get())));
	}
}
