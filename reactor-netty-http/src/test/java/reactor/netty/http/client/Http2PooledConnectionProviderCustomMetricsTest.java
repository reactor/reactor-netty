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

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.annotation.Nullable;

import javax.net.ssl.SSLException;
import java.net.SocketAddress;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.HttpProtocol.H2;

class Http2PooledConnectionProviderCustomMetricsTest {

	static SslContext sslServer;
	static SslContext sslClient;

	@BeforeEach
	void setUp() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
				.build();
		sslClient = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE)
				.build();
	}

	@Test
	void measureActiveStreamsSize() throws InterruptedException {
		AtomicBoolean isRegistered = new AtomicBoolean();
		AtomicBoolean isDeregistered = new AtomicBoolean();
		AtomicReference<HttpConnectionPoolMetrics> metrics = new AtomicReference<>();

		DisposableServer disposableServer = HttpServer.create()
				.port(5678)
				.protocol(H2)
				.wiretap(true)
				.secure(spec -> spec.sslContext(sslServer))
				.handle((req, resp) -> resp.sendString(
								Mono.delay(Duration.ofSeconds(10))
										.then(Mono.just("test"))
						)
				)
				.bindNow();

		CustomHttp2MeterRegistrar registrar = new CustomHttp2MeterRegistrar(isRegistered, isDeregistered, metrics);
		ConnectionProvider pool = ConnectionProvider.builder("custom-pool-2")
				.metrics(true, () -> registrar)
				.maxConnections(10)
				.build();

		CountDownLatch latch = new CountDownLatch(5);
		HttpClient httpClient = HttpClient.create(pool);
		IntStream.range(0, 5)
				.forEach(unUsed -> httpClient.remoteAddress(disposableServer::address)
						.protocol(H2)
						.secure(spec -> spec.sslContext(sslClient))
						.doOnConnected(connection -> latch.countDown())
						.get()
						.uri("/")
						.responseSingle((resp, bytes) -> bytes.asString())
						.subscribe()
				);
		latch.await();

		assertThat(isRegistered.get()).isTrue();
		assertThat(metrics.get().activeStreamSize()).isEqualTo(5);

		pool.dispose();

		assertThat(isDeregistered.get()).isTrue();

		disposableServer.disposeNow();
	}

	@Test
	void measurePendingStreamsSize() {
		AtomicBoolean isRegistered = new AtomicBoolean();
		AtomicBoolean isDeregistered = new AtomicBoolean();
		AtomicReference<HttpConnectionPoolMetrics> metrics = new AtomicReference<>();

		DisposableServer disposableServer = HttpServer.create()
				.port(1234)
				.protocol(H2)
				.wiretap(true)
				.secure(spec -> spec.sslContext(sslServer))
				.handle((req, resp) -> resp.sendString(
								Mono.delay(Duration.ofSeconds(1))
										.then(Mono.just("test"))
						)
				)
				.bindNow();

		CustomHttp2MeterRegistrar registrar = new CustomHttp2MeterRegistrar(isRegistered, isDeregistered, metrics);
		ConnectionProvider pool = ConnectionProvider.builder("custom-pool-1")
				.metrics(true, () -> registrar)
				.maxConnections(1)
				.pendingAcquireMaxCount(5)
				.pendingAcquireTimeout(Duration.ofSeconds(20))
				.build();

		HttpClient httpClient = HttpClient.create(pool);
		IntStream.range(0, 5)
				.forEach(unUsed -> {
					httpClient.remoteAddress(disposableServer::address)
							.protocol(H2)
							.secure(spec -> spec.sslContext(sslClient))
							.http2Settings(builder -> {
								builder.maxStreams(1);
								builder.maxConcurrentStreams(1);
							})
							.get()
							.uri("/")
							.responseSingle((resp, bytes) -> bytes.asString())
							.subscribe();
				});

		assertThat(isRegistered.get()).isTrue();
		assertThat(metrics.get().pendingStreamSize()).isEqualTo(4);

		pool.dispose();

		assertThat(isDeregistered.get()).isTrue();

		disposableServer.disposeNow();
	}

	static final class CustomHttp2MeterRegistrar extends Http2MeterRegistrarAdapter {
		AtomicBoolean isRegistered;
		AtomicBoolean isDeregistered;
		AtomicReference<HttpConnectionPoolMetrics> metrics;

		CustomHttp2MeterRegistrar(
				@Nullable AtomicBoolean isRegistered,
				@Nullable AtomicBoolean isDeregistered,
				AtomicReference<HttpConnectionPoolMetrics> metrics
		) {
			this.isRegistered = isRegistered;
			this.isDeregistered = isDeregistered;
			this.metrics = metrics;
		}

		@Override
		public void registerMetrics(String poolName, String id, SocketAddress remoteAddress, HttpConnectionPoolMetrics metrics) {
			if (isRegistered != null) {
				isRegistered.set(true);
			}

			if (this.metrics.get() == null) {
				this.metrics.set(metrics);
			}
		}

		@Override
		public void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
			if (isDeregistered != null) {
				isDeregistered.set(true);
			}
		}
	}
}
