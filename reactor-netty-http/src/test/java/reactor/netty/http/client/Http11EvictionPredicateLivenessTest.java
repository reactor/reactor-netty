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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.BaseHttpTest;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This class tests https://github.com/reactor/reactor-netty/issues/4251.
 *
 * <p>When a mixed {@code H2, HTTP11} pool is configured with {@code maxIdleTime} and
 * {@code pingAckTimeout}, Reactor Netty installs an {@code Http11EvictionPredicate} on the parent
 * provider. That predicate replaces the provider's built-in liveness check, so it must itself evict
 * a connection that is no longer active or persistent — otherwise a connection that died while idle
 * in the pool is handed out on acquire, and once the single built-in re-acquire retry is exhausted
 * by a second dead connection the acquire fails with {@code IOException("Error while acquiring")}.
 *
 * <p>The predicate only governs the HTTP/1.1 connections of the parent pool (HTTP/2 connections are
 * owned by {@code Http2Pool}), so the scenario is exercised for every way a mixed-protocol client
 * ends up selecting HTTP/1.1: ALPN negotiation over TLS ({@code H2, HTTP11}) and a declined cleartext
 * upgrade ({@code H2C, HTTP11}).
 *
 * @author Jooyoung Jung
 * @since 1.3.7
 */
class Http11EvictionPredicateLivenessTest extends BaseHttpTest {

	static X509Bundle ssc;
	static Http11SslContextSpec serverCtx11;
	static Http2SslContextSpec clientCtx2;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
		serverCtx11 = Http11SslContextSpec.forServer(ssc.toTempCertChainPem(), ssc.toTempPrivateKeyPem());
		clientCtx2 = Http2SslContextSpec.forClient()
		                                .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
	}

	static Stream<Arguments> http11SelectingClients() {
		return Stream.of(
				// client offers H2 + HTTP11 over TLS, server speaks HTTP/1.1 -> ALPN selects http/1.1
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, true),
				// client offers H2C + HTTP11 cleartext, server speaks HTTP/1.1 -> H2C upgrade declined -> http/1.1
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, false));
	}

	@ParameterizedTest
	@MethodSource("http11SelectingClients")
	void deadIdleHttp11ConnectionIsEvictedOnAcquire(HttpProtocol[] clientProtocols, boolean secure) throws Exception {
		// The server holds both responses until both client connections have been established, so two
		// distinct connections are pooled (one dead connection alone would be salvaged by the single
		// built-in re-acquire retry, hiding the bug).
		CountDownLatch twoConnected = new CountDownLatch(2);

		HttpServer server = createServer().protocol(HttpProtocol.HTTP11)
		                                  .handle((req, res) -> res.sendString(
		                                      Mono.fromCallable(() -> {
		                                          twoConnected.await(30, TimeUnit.SECONDS);
		                                          return "OK";
		                                      }).subscribeOn(Schedulers.boundedElastic())));
		if (secure) {
			server = server.secure(spec -> spec.sslContext((SslProvider.GenericSslContextSpec<?>) serverCtx11));
		}
		disposableServer = server.bindNow();

		// maxIdleTime is set large enough never to fire during the test (it only has to be != -1 so the
		// Http11EvictionPredicate is installed); the bug is about liveness, not idle time.
		ConnectionProvider provider =
				ConnectionProvider.builder("deadIdleHttp11ConnectionIsEvictedOnAcquire")
				                  .maxConnections(2)
				                  .maxIdleTime(Duration.ofSeconds(20))
				                  .build();
		try {
			HttpClient client = createClient(provider, disposableServer.port())
			                        .protocol(clientProtocols)
			                        .http2Settings(spec -> spec.pingAckTimeout(Duration.ofSeconds(10)));
			if (secure) {
				client = client.secure(spec -> spec.sslContext((SslProvider.GenericSslContextSpec<?>) clientCtx2));
			}

			List<Channel> pooledChannels = new CopyOnWriteArrayList<>();
			HttpClient warmUpClient = client.doOnConnected(conn -> {
				pooledChannels.add(conn.channel());
				twoConnected.countDown();
			});

			// establish two pooled connections concurrently
			Flux.range(0, 2)
			    .flatMap(i -> warmUpClient.get()
			                              .uri("/")
			                              .responseSingle((res, bytes) -> bytes.thenReturn(res.status().code())))
			    .collectList()
			    .block(Duration.ofSeconds(30));

			assertThat(pooledChannels).as("two distinct connections were pooled").hasSize(2);

			// kill both channels while they sit idle in the pool; their owner is NOOP, so the pool does
			// not proactively invalidate them — they remain as dead slots until the next acquire
			for (Channel channel : pooledChannels) {
				channel.close().sync();
				assertThat(channel.isActive()).isFalse();
			}

			// the next acquire must evict the dead connections and hand out a fresh, live one, instead
			// of failing with IOException("Error while acquiring")
			assertThat(status(client)).isEqualTo(200);
		}
		finally {
			provider.disposeLater().block(Duration.ofSeconds(5));
		}
	}

	static int status(HttpClient client) {
		Integer status =
				client.get()
				      .uri("/")
				      .responseSingle((res, bytes) -> bytes.thenReturn(res.status().code()))
				      .block(Duration.ofSeconds(30));
		assertThat(status).isNotNull();
		return status;
	}
}
