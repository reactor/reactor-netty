/*
 * Copyright (c) 2019-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import reactor.netty.observability.ReactorNettyTimerObservationHandler;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Constants and utilities around metrics.
 *
 * @author Violeta Georgieva
 * @since 0.9
 */
public class Metrics {
	public static final MeterRegistry REGISTRY = io.micrometer.core.instrument.Metrics.globalRegistry;
	public static final String OBSERVATION_KEY = "micrometer.observation";
	public static ObservationRegistry OBSERVATION_REGISTRY = ObservationRegistry.create();
	static {
		OBSERVATION_REGISTRY.observationConfig().observationHandler(
				new ObservationHandler.FirstMatchingCompositeObservationHandler(
						new ReactorNettyTimerObservationHandler(REGISTRY),
						new DefaultMeterObservationHandler(REGISTRY)));
	}


	// Names
	/**
	 * Name prefix that will be used for the HTTP server's metrics
	 * registered in Micrometer's global registry.
	 */
	public static final String HTTP_SERVER_PREFIX = "reactor.netty.http.server";

	/**
	 * Name prefix that will be used for the HTTP client's metrics
	 * registered in Micrometer's global registry.
	 */
	public static final String HTTP_CLIENT_PREFIX = "reactor.netty.http.client";

	/**
	 * Name prefix that will be used for the TCP server's metrics
	 * registered in Micrometer's global registry.
	 */
	public static final String TCP_SERVER_PREFIX = "reactor.netty.tcp.server";

	/**
	 * Name prefix that will be used for the TCP client's metrics
	 * registered in Micrometer's global registry.
	 */
	public static final String TCP_CLIENT_PREFIX = "reactor.netty.tcp.client";

	/**
	 * Name prefix that will be used for the UDP server's metrics
	 * registered in Micrometer's global registry.
	 */
	public static final String UDP_SERVER_PREFIX = "reactor.netty.udp.server";

	/**
	 * Name prefix that will be used for the UDP client's metrics
	 * registered in Micrometer's global registry.
	 */
	public static final String UDP_CLIENT_PREFIX = "reactor.netty.udp.client";

	/**
	 * Name prefix that will be used for Event Loop Group metrics
	 * registered in Micrometer's global registry.
	 */
	public static final String EVENT_LOOP_PREFIX = "reactor.netty.eventloop";

	/**
	 * Name prefix that will be used for the PooledConnectionProvider's metrics
	 * registered in Micrometer's global registry.
	 */
	public static final String CONNECTION_PROVIDER_PREFIX = "reactor.netty.connection.provider";

	/**
	 * Name prefix that will be used for the ByteBufAllocator's metrics
	 * registered in Micrometer's global registry.
	 */
	public static final String BYTE_BUF_ALLOCATOR_PREFIX = "reactor.netty.bytebuf.allocator";


	// Metrics
	/**
	 * Amount of the data received, in bytes.
	 */
	public static final String DATA_RECEIVED = ".data.received";

	/**
	 * Amount of the data sent, in bytes.
	 */
	public static final String DATA_SENT = ".data.sent";

	/**
	 * Number of errors that occurred.
	 */
	public static final String ERRORS = ".errors";

	/**
	 * Time spent for TLS handshake.
	 */
	public static final String TLS_HANDSHAKE_TIME = ".tls.handshake.time";

	/**
	 * Time spent for connecting to the remote address.
	 */
	public static final String CONNECT_TIME = ".connect.time";

	/**
	 * Time spent in consuming incoming data.
	 */
	public static final String DATA_RECEIVED_TIME = ".data.received.time";

	/**
	 * Time spent in sending outgoing data.
	 */
	public static final String DATA_SENT_TIME = ".data.sent.time";

	/**
	 * Total time for the request/response.
	 */
	public static final String RESPONSE_TIME = ".response.time";

	/**
	 * The number of all connections, whether they are active or idle.
	 */
	public static final String CONNECTIONS_TOTAL = ".connections.total";

	/**
	 * The number of connections that are currently in use.
	 */
	public static final String CONNECTIONS_ACTIVE = ".connections.active";


	// AddressResolverGroup Metrics
	/**
	 * Time spent for resolving the address.
	 */
	public static final String ADDRESS_RESOLVER = ".address.resolver";


	// PooledConnectionProvider Metrics
	/**
	 * The number of all connections, active or idle.
	 */
	public static final String TOTAL_CONNECTIONS = ".total.connections";

	/**
	 * The number of the connections that have been successfully acquired and are in active use.
	 */
	public static final String ACTIVE_CONNECTIONS = ".active.connections";

	/**
	 * The maximum number of active connections that are allowed.
	 */
	public static final String MAX_CONNECTIONS = ".max.connections";

	/**
	 * The number of the idle connections.
	 */
	public static final String IDLE_CONNECTIONS = ".idle.connections";

	/**
	 * The number of requests that are waiting for a connection.
	 */
	public static final String PENDING_CONNECTIONS = ".pending.connections";

	/**
	 * The maximum number of requests that will be queued while waiting for a ready connection.
	 */
	public static final String MAX_PENDING_CONNECTIONS = ".max.pending.connections";

	/**
	 * The number of the active HTTP/2 streams.
	 */
	public static final String ACTIVE_STREAMS = ".active.streams";

	/**
	 * The number of requests that are waiting for opening HTTP/2 stream.
	 */
	public static final String PENDING_STREAMS = ".pending.streams";


	// ByteBufAllocator Metrics
	/**
	 * The number of bytes reserved by heap buffer allocator.
	 */
	public static final String USED_HEAP_MEMORY = ".used.heap.memory";

	/**
	 * The number of bytes reserved by direct buffer allocator.
	 */
	public static final String USED_DIRECT_MEMORY = ".used.direct.memory";

	/**
	 * The actual bytes consumed by in-use buffers allocated from heap buffer pools.
	 */
	public static final String ACTIVE_HEAP_MEMORY = ".active.heap.memory";

	/**
	 * The actual bytes consumed by in-use buffers allocated from direct buffer pools.
	 */
	public static final String ACTIVE_DIRECT_MEMORY = ".active.direct.memory";

	/**
	 * The number of heap arenas.
	 */
	public static final String HEAP_ARENAS = ".heap.arenas";

	/**
	 * The number of direct arenas.
	 */
	public static final String DIRECT_ARENAS = ".direct.arenas";

	/**
	 * The number of thread local caches.
	 */
	public static final String THREAD_LOCAL_CACHES = ".threadlocal.caches";

	/**
	 * The size of the small cache.
	 */
	public static final String SMALL_CACHE_SIZE = ".small.cache.size";

	/**
	 * The size of the normal cache.
	 */
	public static final String NORMAL_CACHE_SIZE = ".normal.cache.size";

	/**
	 * The chunk size for an arena.
	 */
	public static final String CHUNK_SIZE = ".chunk.size";

	// EventLoop Metrics
	/**
	 * The number of tasks that are pending for processing on an event loop.
	 */
	public static final String PENDING_TASKS = ".pending.tasks";

	// HttpServer Metrics
	/**
	 * The number of active HTTP/2 streams.
	 */
	public static final String STREAMS_ACTIVE = ".streams.active";

	// Tags
	public static final String LOCAL_ADDRESS = "local.address";

	public static final String PROXY_ADDRESS = "proxy.address";

	public static final String REMOTE_ADDRESS = "remote.address";

	public static final String URI = "uri";

	public static final String STATUS = "status";

	public static final String METHOD = "method";

	public static final String ID = "id";

	public static final String NAME = "name";

	public static final String TYPE = "type";

	public static final String SUCCESS = "SUCCESS";

	public static final String ERROR = "ERROR";

	public static final String UNKNOWN = "UNKNOWN";

	public static final String NA = "na";

	@Nullable
	public static Observation currentObservation(ContextView contextView) {
		if (contextView.hasKey(OBSERVATION_KEY)) {
			return contextView.get(OBSERVATION_KEY);
		}
		return OBSERVATION_REGISTRY.getCurrentObservation();
	}

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

	/**
	 * Set the {@link ObservationRegistry} to use in Reactor Netty for tracing related purposes.
	 *
	 * @return the previously configured registry.
	 * @since 1.1.6
	 */
	public static ObservationRegistry observationRegistry(ObservationRegistry observationRegistry) {
		ObservationRegistry previous = OBSERVATION_REGISTRY;
		OBSERVATION_REGISTRY = observationRegistry;
		return previous;
	}

	public static Context updateContext(Context context, Object observation) {
		return context.hasKey(OBSERVATION_KEY) ? context : context.put(OBSERVATION_KEY, observation);
	}

	static final AttributeKey<ContextView> CONTEXT_VIEW = AttributeKey.valueOf("$CONTEXT_VIEW");

	/**
	 * Updates the {@link ContextView} in the channel attributes with this {@link Observation}.
	 * When there is already {@link Observation} in the {@link ContextView}, then is will be set as a parent
	 * to this {@link Observation}.
	 *
	 * @param channel the channel
	 * @param observation the {@link Observation}
	 * @return the previous {@link Observation} when exists otherwise {@code null}
	 * @since 1.1.1
	 */
	@Nullable
	public static ContextView updateChannelContext(Channel channel, Observation observation) {
		ContextView parentContextView = channel.attr(CONTEXT_VIEW).get();
		if (parentContextView != null) {
			Observation parentObservation = parentContextView.getOrDefault(OBSERVATION_KEY, null);
			if (parentObservation != null) {
				observation.parentObservation(parentObservation);
			}
			channel.attr(CONTEXT_VIEW).set(Context.of(parentContextView).put(OBSERVATION_KEY, observation));
		}
		else {
			channel.attr(CONTEXT_VIEW).set(Context.of(OBSERVATION_KEY, observation));
		}
		return parentContextView;
	}
}
