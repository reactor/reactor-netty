/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
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
	public static final String HTTP_SERVER_NAME_PREFIX = "reactor.netty.http.server";

	public static final String HTTP_CLIENT_NAME_PREFIX = "reactor.netty.http.client";

	public static final String TCP_SERVER_NAME_PREFIX = "reactor.netty.tcp.server";

	public static final String TCP_CLIENT_NAME_PREFIX = "reactor.netty.tcp.client";

	public static final String UDP_SERVER_NAME_PREFIX = "reactor.netty.udp.server";

	public static final String UDP_CLIENT_NAME_PREFIX = "reactor.netty.udp.client";

	public static final String CONNECTION_PROVIDER_NAME_PREFIX = "reactor.netty.connection.provider";


	// Metrics
	public static final String DATA_RECEIVED = ".data.received";

	public static final String DATA_SENT = ".data.sent";

	public static final String ERRORS = ".errors";

	public static final String TLS_HANDSHAKE_TIME = ".tls.handshake.time";

	public static final String CONNECT_TIME = ".connect.time";

	public static final String DATA_RECEIVED_TIME = ".data.received.time";

	public static final String DATA_SENT_TIME = ".data.sent.time";

	public static final String RESPONSE_TIME = ".response.time";

	public static final String ADDRESS_RESOLVER = ".address.resolver";

	public static final String TOTAL_CONNECTIONS = ".total.connections";

	public static final String ACTIVE_CONNECTIONS = ".active.connections";

	public static final String IDLE_CONNECTIONS = ".idle.connections";

	public static final String PENDING_CONNECTIONS = ".pending.connections";


	// Tags
	public static final String REMOTE_ADDRESS = "remote.address";

	public static final String URI = "uri";

	public static final String STATUS = "status";

	public static final String METHOD = "method";

	public static final String ID = "id";

	public static final String POOL_NAME = "pool.name";

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