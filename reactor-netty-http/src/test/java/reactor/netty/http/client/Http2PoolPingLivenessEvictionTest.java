/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This class tests https://github.com/reactor/reactor-netty/issues/4245.
 *
 * <p>When the HTTP/2 PING liveness check is enabled ({@code pingAckTimeout} + {@code maxIdleTime}),
 * a pooled connection whose remote peer silently went away is detected by the idle PING probe:
 * while the probe is in flight the connection is excluded from acquisition, and when no PING ACK
 * arrives the connection is closed and removed from the pool. A subsequent request therefore
 * recovers on a freshly established connection instead of being handed the broken one.
 *
 * <p>The remote-peer failure is simulated with a small TCP forwarder: severing its server-side
 * socket leaves the client-side TCP connection fully established, so the client channel keeps
 * reporting as active although nothing will ever be received on it again — and, being idle, it
 * is the exact case the idle PING probe is meant to catch.
 *
 * @author Jooyoung Jung
 * @since 1.3.7
 */
class Http2PoolPingLivenessEvictionTest extends BaseHttpTest {

	static X509Bundle ssc;
	static Http2SslContextSpec serverCtx;
	static Http2SslContextSpec clientCtx;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
		serverCtx = Http2SslContextSpec.forServer(ssc.toTempCertChainPem(), ssc.toTempPrivateKeyPem());
		clientCtx = Http2SslContextSpec.forClient()
		                               .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
	}

	static Stream<Arguments> protocols() {
		return Stream.of(
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, true),
				// H2C with prior knowledge, liveness is wired on acquire
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C}, false),
				// H2C via cleartext upgrade, liveness is wired on UPGRADE_SUCCESSFUL
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11, HttpProtocol.H2C}, false));
	}

	@ParameterizedTest
	@MethodSource("protocols")
	void brokenConnectionIsEvictedByPingLivenessProbe(HttpProtocol[] protocols, boolean secure) throws Exception {
		HttpServer server = createServer().protocol(protocols)
		                                  .handle((req, res) -> res.sendString(Mono.just("OK")));
		if (secure) {
			server = server.secure(spec -> spec.sslContext((SslProvider.GenericSslContextSpec<?>) serverCtx));
		}
		disposableServer = server.bindNow();
		TcpProxy proxy = new TcpProxy("localhost", disposableServer.port());
		// maxIdleTime enables the idle PING probe (and the eviction predicate that wires the liveness handler)
		ConnectionProvider provider = ConnectionProvider.builder("pingLivenessEviction")
		                                                .maxConnections(1)
		                                                .maxIdleTime(Duration.ofMillis(500))
		                                                .build();
		try {
			HttpClient client = createClient(provider, proxy.port())
					.protocol(protocols)
					// pingAckTimeout turns the HTTP/2 PING liveness check on
					.http2Settings(builder -> builder.pingAckTimeout(Duration.ofMillis(100)));
			if (secure) {
				client = client.secure(spec -> spec.sslContext((SslProvider.GenericSslContextSpec<?>) clientCtx));
			}

			AtomicReference<Channel> brokenParent = new AtomicReference<>();
			CountDownLatch brokenConnectionClosed = new CountDownLatch(1);
			HttpClient establishingClient = client.doOnResponse((res, conn) -> {
				Channel parent = conn.channel().parent();
				if (parent != null && brokenParent.compareAndSet(null, parent)) {
					parent.closeFuture().addListener(f -> brokenConnectionClosed.countDown());
				}
			});

			// establish the pooled connection with a successful request
			assertThat(status(establishingClient)).isEqualTo(200);
			assertThat(brokenParent.get()).as("captured the pooled HTTP/2 connection").isNotNull();

			// sever the proxy's server side: the pooled connection is now half-open and idle
			proxy.dropServerSide();

			// the idle PING liveness probe fires, receives no ACK, and evicts the broken connection
			assertThat(brokenConnectionClosed.await(20, TimeUnit.SECONDS))
					.as("broken connection is evicted by the PING liveness probe")
					.isTrue();

			// the pool hands out a freshly established connection for the next request
			assertThat(status(client)).isEqualTo(200);
		}
		finally {
			provider.disposeLater().block(Duration.ofSeconds(5));
			proxy.close();
		}
	}

	static int status(HttpClient client) {
		Integer status =
				client.get()
				      .uri("/")
				      .responseSingle((res, bytes) -> bytes.thenReturn(res.status().code()))
				      .block(Duration.ofSeconds(5));
		assertThat(status).isNotNull();
		return status;
	}

	/**
	 * Minimal TCP forwarder for simulating a half-open connection. After {@link #dropServerSide()}
	 * the accepted client socket is kept open while the forwarder no longer has a server side:
	 * the client is never sent a FIN or RST, whatever it writes is silently discarded, and a
	 * response can never arrive. Each accepted connection dials its own outbound leg, so the
	 * damage is confined to the connections that existed at drop time. The test relies on this
	 * to prove that recovery happens because the pool discarded the broken connection, not
	 * because the path healed.
	 */
	static final class TcpProxy implements AutoCloseable {

		final ServerSocket acceptor;
		final String targetHost;
		final int targetPort;
		final Queue<Socket> openSockets = new ConcurrentLinkedQueue<>();

		volatile @Nullable Socket serverSide;

		TcpProxy(String targetHost, int targetPort) throws IOException {
			this.targetHost = targetHost;
			this.targetPort = targetPort;
			this.acceptor = new ServerSocket(0);
			daemonThread("test-tcp-proxy-acceptor", this::listen);
		}

		int port() {
			return acceptor.getLocalPort();
		}

		void dropServerSide() throws IOException {
			Socket socket = serverSide;
			if (socket != null) {
				socket.close();
			}
		}

		@Override
		public void close() throws IOException {
			acceptor.close();
			Socket socket;
			while ((socket = openSockets.poll()) != null) {
				try {
					socket.close();
				}
				catch (IOException ignored) {
					// already closed
				}
			}
		}

		void listen() {
			try {
				while (!acceptor.isClosed()) {
					Socket clientSide = acceptor.accept();
					Socket upstreamSide = new Socket(targetHost, targetPort);
					openSockets.add(clientSide);
					openSockets.add(upstreamSide);
					serverSide = upstreamSide;
					relay("test-tcp-proxy-request", clientSide, upstreamSide);
					relay("test-tcp-proxy-response", upstreamSide, clientSide);
				}
			}
			catch (IOException ignored) {
				// the acceptor was closed, shut down
			}
		}

		static void relay(String name, Socket from, Socket to) {
			daemonThread(name, () -> {
				try {
					InputStream source = from.getInputStream();
					OutputStream sink = to.getOutputStream();
					byte[] buffer = new byte[4096];
					for (int read = source.read(buffer); read != -1; read = source.read(buffer)) {
						sink.write(buffer, 0, read);
					}
				}
				catch (IOException ignored) {
					// reading or writing hit a closed socket, nothing more to relay in this direction
				}
			});
		}

		static void daemonThread(String name, Runnable task) {
			Thread thread = new Thread(task, name);
			thread.setDaemon(true);
			thread.start();
		}
	}
}
