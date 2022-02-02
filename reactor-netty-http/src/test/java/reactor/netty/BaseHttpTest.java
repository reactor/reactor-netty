/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.provider.Arguments;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Provides utility methods for {@link HttpServer} and {@link HttpClient} creation.
 * At the end of each test guarantees that the server is disposed.
 *
 * @author Violeta Georgieva
 * @since 1.0.3
 */
public class BaseHttpTest {

	protected DisposableServer disposableServer;

	@AfterEach
	void disposeServer() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
	}

	/**
	 * Creates {@link HttpServer} bound on a random port with wire logging enabled.
	 *
	 * @return a new {@link HttpServer}
	 */
	public static HttpServer createServer() {
		return createServer(0);
	}

	/**
	 * Creates {@link HttpServer} bound on the specified port with wire logging enabled.
	 *
	 * @param port the port to bind to
	 * @return a new {@link HttpServer}
	 */
	public static HttpServer createServer(int port) {
		return HttpServer.create()
		                 .port(port)
		                 .wiretap(true);
	}

	/**
	 * Creates {@link HttpClient} with a specified port to connect to and wire logging enabled.
	 * The default pool will be used.
	 *
	 * @param port the port to connect to
	 * @return a new {@link HttpClient}
	 */
	public static HttpClient createClient(int port) {
		return createClient(null, port);
	}

	/**
	 * Creates {@link HttpClient} with a specified port to connect to and wire logging enabled.
	 * If a {@code pool} is specified, it will be used instead of the default pool.
	 *
	 * @param pool the pool to be used when creating the client, if null the default pool will be used
	 * @param port the port to connect to
	 * @return a new {@link HttpClient}
	 */
	public static HttpClient createClient(@Nullable ConnectionProvider pool, int port) {
		HttpClient client = pool == null ? HttpClient.create() : HttpClient.create(pool);
		return client.port(port)
		             .wiretap(true);
	}

	/**
	 * Creates {@link HttpClient} with a specified remote address to connect to and wire logging enabled.
	 * The default pool will be used.
	 *
	 * @param remoteAddress a supplier of the address to connect to
	 * @return a new {@link HttpClient}
	 */
	public static HttpClient createClient(Supplier<SocketAddress> remoteAddress) {
		return createClient(null, remoteAddress);
	}

	/**
	 * Creates {@link HttpClient} with a specified remote address to connect to and wire logging enabled.
	 * If a {@code pool} is specified, it will be used instead of the default pool.
	 *
	 * @param pool the pool to be used when creating the client, if null the default pool will be used
	 * @param remoteAddress a supplier of the address to connect to
	 * @return a new {@link HttpClient}
	 */
	public static HttpClient createClient(@Nullable ConnectionProvider pool, Supplier<SocketAddress> remoteAddress) {
		HttpClient client = pool == null ? HttpClient.create() : HttpClient.create(pool);
		return client.remoteAddress(remoteAddress)
		             .wiretap(true);
	}

	/**
	 * Creates {@link HttpClient} with a specified port to connect to and wire logging enabled.
	 * The connection pool is disabled.
	 *
	 * @param port the port to connect to
	 * @return a new {@link HttpClient}
	 */
	public static HttpClient createClientNewConnection(int port) {
		return HttpClient.newConnection()
		                 .port(port)
		                 .wiretap(true);
	}

	protected static Stream<Arguments> h2CompatibleCombinations() {
		return Stream.of(
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2}),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2}),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11})
		);
	}

	protected static Stream<Arguments> h2cCompatibleCombinations() {
		return Stream.of(
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C}, new HttpProtocol[]{HttpProtocol.H2C}),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C}),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11})
		);
	}
}
