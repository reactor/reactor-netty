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

import io.micrometer.core.instrument.MeterRegistry;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * @author Violeta Georgieva
 * @since 0.9
 */
public class Metrics {
	public static final MeterRegistry REGISTRY = io.micrometer.core.instrument.Metrics.globalRegistry;


	// Names
	/**
	 * Name prefix that will be used for the HTTP server's metrics
	 * registered in Micrometer's global registry
	 */
	public static final String HTTP_SERVER_PREFIX = "reactor.netty.http.server";

	/**
	 * Name prefix that will be used for the HTTP client's metrics
	 * registered in Micrometer's global registry
	 */
	public static final String HTTP_CLIENT_PREFIX = "reactor.netty.http.client";

	/**
	 * Name prefix that will be used for the TCP server's metrics
	 * registered in Micrometer's global registry
	 */
	public static final String TCP_SERVER_PREFIX = "reactor.netty.tcp.server";

	/**
	 * Name prefix that will be used for the TCP client's metrics
	 * registered in Micrometer's global registry
	 */
	public static final String TCP_CLIENT_PREFIX = "reactor.netty.tcp.client";

	/**
	 * Name prefix that will be used for the UDP server's metrics
	 * registered in Micrometer's global registry
	 */
	public static final String UDP_SERVER_PREFIX = "reactor.netty.udp.server";

	/**
	 * Name prefix that will be used for the UDP client's metrics
	 * registered in Micrometer's global registry
	 */
	public static final String UDP_CLIENT_PREFIX = "reactor.netty.udp.client";

	/**
	 * Name prefix that will be used for the PooledConnectionProvider's metrics
	 * registered in Micrometer's global registry
	 */
	public static final String CONNECTION_PROVIDER_PREFIX = "reactor.netty.connection.provider";

	/**
	 * Name prefix that will be used for the ByteBufAllocator's metrics
	 * registered in Micrometer's global registry
	 */
	public static final String BYTE_BUF_ALLOCATOR_PREFIX = "reactor.netty.bytebuf.allocator";


	// Metrics
	/**
	 * Amount of the data that is received, in bytes
	 */
	public static final String DATA_RECEIVED = ".data.received";

	/**
	 * Amount of the data that is sent, in bytes
	 */
	public static final String DATA_SENT = ".data.sent";

	/**
	 * Number of the errors that are occurred
	 */
	public static final String ERRORS = ".errors";

	/**
	 * Time that is spent for TLS handshake
	 */
	public static final String TLS_HANDSHAKE_TIME = ".tls.handshake.time";

	/**
	 * Time that is spent for connecting to the remote address
	 */
	public static final String CONNECT_TIME = ".connect.time";

	/**
	 * Time that is spent in consuming incoming data
	 */
	public static final String DATA_RECEIVED_TIME = ".data.received.time";

	/**
	 * Time that is spent in sending outgoing data
	 */
	public static final String DATA_SENT_TIME = ".data.sent.time";

	/**
	 * Total time for the request/response
	 */
	public static final String RESPONSE_TIME = ".response.time";


	// AddressResolverGroup Metrics
	/**
	 * Time that is spent for resolving the address
	 */
	public static final String ADDRESS_RESOLVER = ".address.resolver";


	// PooledConnectionProvider Metrics
	/**
	 * The number of all connections, active or idle
	 */
	public static final String TOTAL_CONNECTIONS = ".total.connections";

	/**
	 * The number of the connections that have been successfully acquired and are in active use
	 */
	public static final String ACTIVE_CONNECTIONS = ".active.connections";

	/**
	 * The number of the idle connections
	 */
	public static final String IDLE_CONNECTIONS = ".idle.connections";

	/**
	 * The number of the request, that are pending acquire a connection
	 */
	public static final String PENDING_CONNECTIONS = ".pending.connections";


	// ByteBufAllocator Metrics
	/**
	 * The number of the bytes of the heap memory
	 */
	public static final String USED_HEAP_MEMORY = ".used.heap.memory";

	/**
	 * The number of the bytes of the direct memory
	 */
	public static final String USED_DIRECT_MEMORY = ".used.direct.memory";

	/**
	 * The number of heap arenas
	 */
	public static final String HEAP_ARENAS = ".heap.arenas";

	/**
	 * The number of direct arenas
	 */
	public static final String DIRECT_ARENAS = ".direct.arenas";

	/**
	 * The number of thread local caches
	 */
	public static final String THREAD_LOCAL_CACHES = ".threadlocal.caches";

	/**
	 * The size of the tiny cache
	 */
	public static final String TINY_CACHE_SIZE = ".tiny.cache.size";

	/**
	 * The size of the small cache
	 */
	public static final String SMALL_CACHE_SIZE = ".small.cache.size";

	/**
	 * The size of the normal cache
	 */
	public static final String NORMAL_CACHE_SIZE = ".normal.cache.size";

	/**
	 * The chunk size for an arena
	 */
	public static final String CHUNK_SIZE = ".chunk.size";


	// Tags
	public static final String REMOTE_ADDRESS = "remote.address";

	public static final String URI = "uri";

	public static final String STATUS = "status";

	public static final String METHOD = "method";

	public static final String ID = "id";

	public static final String NAME = "name";

	public static final String TYPE = "type";

	public static final String SUCCESS = "SUCCESS";

	public static final String ERROR = "ERROR";


	@Nullable
	public static String formatSocketAddress(@Nullable SocketAddress socketAddress) {
		if (socketAddress != null) {
			if (socketAddress instanceof InetSocketAddress) {
				InetSocketAddress address = (InetSocketAddress) socketAddress;
				return address.getHostString() + ":" + address.getPort();
			}
			else {
				return socketAddress.toString();
			}
		}
		return null;
	}
}