/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
package reactor.netty;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
public final class SystemPropertiesNames {
	/**
	 * Default worker thread count, fallback to available processor
	 * (but with a minimum value of 4)
	 */
	public static final String IO_WORKER_COUNT = "reactor.netty.ioWorkerCount";
	/**
	 * Default selector thread count, fallback to -1 (no selector thread)
	 */
	public static final String IO_SELECT_COUNT = "reactor.netty.ioSelectCount";
	/**
	 * Default worker thread count for UDP, fallback to available processor
	 * (but with a minimum value of 4)
	 */
	public static final String UDP_IO_THREAD_COUNT = "reactor.netty.udp.ioThreadCount";


	/**
	 * Default value whether the native transport (epoll, kqueue) will be preferred,
	 * fallback it will be preferred when available
	 */
	public static final String NATIVE = "reactor.netty.native";


	/**
	 * Default max connections, if -1 will never wait to acquire before opening a new
	 * connection in an unbounded fashion. Fallback to
	 * available number of processors (but with a minimum value of 16)
	 */
	public static final String POOL_MAX_CONNECTIONS = "reactor.netty.pool.maxConnections";
	/**
	 * Default acquisition timeout (milliseconds) before error. If -1 will never wait to
	 * acquire before opening a new
	 * connection in an unbounded fashion. Fallback 45 seconds
	 */
	public static final String POOL_ACQUIRE_TIMEOUT = "reactor.netty.pool.acquireTimeout";


	/**
	 * Default SSL handshake timeout (milliseconds), fallback to 10 seconds
	 */
	public static final String SSL_HANDSHAKE_TIMEOUT = "reactor.netty.tcp.sslHandshakeTimeout";
	/**
	 * Default value whether the SSL debugging on the client side will be enabled/disabled,
	 * fallback to SSL debugging disabled
	 */
	public static final String SSL_CLIENT_DEBUG = "reactor.netty.tcp.ssl.client.debug";
	/**
	 * Default value whether the SSL debugging on the server side will be enabled/disabled,
	 * fallback to SSL debugging disabled
	 */
	public static final String SSL_SERVER_DEBUG = "reactor.netty.tcp.ssl.server.debug";


	private SystemPropertiesNames() {}
}
