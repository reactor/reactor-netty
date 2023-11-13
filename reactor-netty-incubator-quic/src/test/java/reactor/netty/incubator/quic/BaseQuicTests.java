/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.incubator.quic;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import org.junit.jupiter.api.AfterEach;
import reactor.netty.Connection;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Provides utility methods for {@link QuicServer} and {@link QuicClient} creation.
 * At the end of each test guarantees that the server and the client are disposed.
 *
 * @author Violeta Georgieva
 */
class BaseQuicTests {

	static final String PROTOCOL = "http/0.9";

	static final QuicSslContext clientCtx;
	static final QuicSslContext serverCtx;
	static final SelfSignedCertificate ssc;
	static {
		SelfSignedCertificate cert;
		try {
			cert = new SelfSignedCertificate();
		}
		catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
		ssc = cert;

		clientCtx =
				QuicSslContextBuilder.forClient()
				                     .trustManager(InsecureTrustManagerFactory.INSTANCE)
				                     .applicationProtocols(PROTOCOL)
				                     .build();
		serverCtx =
				QuicSslContextBuilder.forServer(ssc.privateKey(), null, ssc.certificate())
				                     .applicationProtocols(PROTOCOL)
				                     .build();
	}

	protected QuicConnection client;
	protected Connection server;

	@AfterEach
	void dispose() {
		if (server != null) {
			server.disposeNow();
		}
		if (client != null) {
			client.disposeNow();
		}
	}

	/**
	 * Creates {@link QuicServer} bound on a random port with.
	 * <ul>
	 *     <li>InsecureQuicTokenHandler</li>
	 *     <li>wire logging enabled</li>
	 *     <li>self signed certificate</li>
	 *     <li>idle timeout 5s</li>
	 *     <li>initial settings:
	 *         <ul>
	 *             <li>maxData: 10000000</li>
	 *             <li>maxStreamDataBidirectionalLocal: 1000000</li>
	 *             <li>maxStreamDataBidirectionalRemote: 1000000</li>
	 *             <li>maxStreamsBidirectional: 100</li>
	 *             <li>maxStreamsUnidirectional: 100</li>
	 *         </ul>
	 *     </li>
	 * </ul>
	 *
	 * @return a new {@link QuicServer}
	 */
	static QuicServer createServer() {
		return createServer(0);
	}

	/**
	 * Creates {@link QuicServer} bound on the specified port with.
	 * <ul>
	 *     <li>InsecureQuicTokenHandler</li>
	 *     <li>wire logging enabled</li>
	 *     <li>self signed certificate</li>
	 *     <li>idle timeout 5s</li>
	 *     <li>initial settings:
	 *         <ul>
	 *             <li>maxData: 10000000</li>
	 *             <li>maxStreamDataBidirectionalLocal: 1000000</li>
	 *             <li>maxStreamDataBidirectionalRemote: 1000000</li>
	 *             <li>maxStreamDataUnidirectional: 1000000</li>
	 *             <li>maxStreamsBidirectional: 100</li>
	 *             <li>maxStreamsUnidirectional: 100</li>
	 *         </ul>
	 *     </li>
	 * </ul>
	 *
	 * @param port the port to bind to
	 * @return a new {@link QuicServer}
	 */
	public static QuicServer createServer(int port) {
		return createServer(port, spec ->
				spec.maxData(10000000)
				    .maxStreamDataBidirectionalLocal(1000000)
				    .maxStreamDataBidirectionalRemote(1000000)
				    .maxStreamDataUnidirectional(1000000)
				    .maxStreamsBidirectional(100)
				    .maxStreamsUnidirectional(100));
	}

	/**
	 * Creates {@link QuicServer} bound on the specified port with.
	 * <ul>
	 *     <li>InsecureQuicTokenHandler</li>
	 *     <li>wire logging enabled</li>
	 *     <li>self signed certificate</li>
	 *     <li>idle timeout 5s</li>
	 * </ul>
	 *
	 * @param port the port to bind to
	 * @return a new {@link QuicServer}
	 */
	public static QuicServer createServer(int port, Consumer<QuicInitialSettingsSpec.Builder> initialSettings) {
		return QuicServer.create()
		                 .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
		                 .port(port)
		                 .wiretap(true)
		                 .secure(serverCtx)
		                 .idleTimeout(Duration.ofSeconds(5))
		                 .initialSettings(initialSettings);
	}
	/**
	 * Creates {@link QuicClient} with a specified remote address to connect to and the following configuration.
	 * <ul>
	 *     <li>wire logging enabled</li>
	 *     <li>InsecureTrustManagerFactory</li>
	 *     <li>idle timeout 5s</li>
	 *     <li>initial settings:
	 *         <ul>
	 *             <li>maxData: 10000000</li>
	 *             <li>maxStreamDataBidirectionalLocal: 1000000</li>
	 *             <li>maxStreamDataBidirectionalRemote: 1000000</li>
	 *             <li>maxStreamDataUnidirectional: 1000000</li>
	 *             <li>maxStreamsBidirectional: 100</li>
	 *             <li>maxStreamsUnidirectional: 100</li>
	 *         </ul>
	 *     </li>
	 * </ul>
	 *
	 * @param remoteAddress a supplier of the address to connect to
	 * @return a new {@link QuicClient}
	 */
	public static QuicClient createClient(Supplier<SocketAddress> remoteAddress) {
		return createClient(remoteAddress, spec ->
				spec.maxData(10000000)
				    .maxStreamDataBidirectionalLocal(1000000)
				    .maxStreamDataBidirectionalRemote(1000000)
				    .maxStreamDataUnidirectional(1000000)
				    .maxStreamsBidirectional(100)
				    .maxStreamsUnidirectional(100));
	}

	/**
	 * Creates {@link QuicClient} with a specified remote address to connect to and the following configuration.
	 * <ul>
	 *     <li>wire logging enabled</li>
	 *     <li>InsecureTrustManagerFactory</li>
	 *     <li>idle timeout 5s</li>
	 * </ul>
	 *
	 * @param remoteAddress a supplier of the address to connect to
	 * @return a new {@link QuicClient}
	 */
	public static QuicClient createClient(Supplier<SocketAddress> remoteAddress,
			Consumer<QuicInitialSettingsSpec.Builder> initialSettings) {
		return QuicClient.create()
		                 .remoteAddress(remoteAddress)
		                 .bindAddress(() -> new InetSocketAddress(0))
		                 .wiretap(true)
		                 .secure(clientCtx)
		                 .idleTimeout(Duration.ofSeconds(5))
		                 .initialSettings(initialSettings);
	}
}
