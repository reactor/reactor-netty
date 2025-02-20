/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import reactor.netty.resources.ConnectionPoolMetrics;
import reactor.netty.resources.ConnectionProvider;

import java.net.SocketAddress;


/**
 * An abstract adapter class for registering HTTP/2 specific metrics in a connection pool.
 * This class implements the {@link ConnectionProvider.MeterRegistrar} interface and provides
 * methods to register and deregister metrics specific to HTTP/2 connections.
 * <p>
 * This is useful for monitoring and managing the performance of HTTP/2 connections in a pool.
 *
 * @author raccoonback
 */
public abstract class HttpMeterRegistrarAdapter implements ConnectionProvider.MeterRegistrar {

	/**
	 * Registers metrics for a connection pool. If the provided metrics are an instance of
	 * {@link HttpConnectionPoolMetrics}, it delegates the call to the abstract method
	 * {@link #registerMetrics(String, String, SocketAddress, HttpConnectionPoolMetrics)}.
	 *
	 * @param poolName      the name of the connection pool
	 * @param id            the identifier of the connection pool
	 * @param remoteAddress the remote address of the connection pool
	 * @param metrics       the metrics to be registered
	 */
	@Override
	public void registerMetrics(String poolName, String id, SocketAddress remoteAddress, ConnectionPoolMetrics metrics) {
		if (metrics instanceof HttpConnectionPoolMetrics) {
			registerMetrics(poolName, id, remoteAddress, (HttpConnectionPoolMetrics) metrics);
		}
	}

	/**
	 * Registers HTTP/2 or HTTP/3 specific metrics for a connection pool.
	 *
	 * @param poolName      the name of the connection pool
	 * @param id            the identifier of the connection pool
	 * @param remoteAddress the remote address of the connection pool
	 * @param metrics       the HTTP/2 or HTTP/3 specific metrics to be registered
	 */
	protected abstract void registerMetrics(String poolName, String id, SocketAddress remoteAddress, HttpConnectionPoolMetrics metrics);
}
