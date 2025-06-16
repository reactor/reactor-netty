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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.NettyPipeline;
import reactor.netty.http.HttpProtocol;
import reactor.netty.resources.ConnectionProvider;

import javax.net.ssl.SSLException;
import java.net.SocketAddress;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static reactor.netty.http.HttpProtocol.H2;

class Http2PooledConnectionProviderCustomMetricsTest extends BaseHttpTest {

	static SslContext sslServer;
	static SslContext sslClient;

	@BeforeAll
	static void setUp() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		sslClient = SslContextBuilder.forClient()
		                             .trustManager(InsecureTrustManagerFactory.INSTANCE)
		                             .build();
	}

	@Test
	void measureActiveStreamsSize() throws InterruptedException {
		AtomicBoolean isRegistered = new AtomicBoolean();
		AtomicBoolean isDeregistered = new AtomicBoolean();
		AtomicReference<HttpConnectionPoolMetrics> metrics = new AtomicReference<>();

		disposableServer =
				createServer()
				        .protocol(H2)
				        .secure(spec -> spec.sslContext(sslServer))
				        .handle((req, resp) -> resp.sendString(Mono.delay(Duration.ofSeconds(10)).then(Mono.just("test"))))
				        .bindNow();

		CustomHttp2MeterRegistrar registrar = new CustomHttp2MeterRegistrar(isRegistered, isDeregistered, metrics);
		ConnectionProvider pool =
				ConnectionProvider.builder("custom-pool")
				                  .metrics(true, () -> registrar)
				                  .maxConnections(10)
				                  .build();

		CountDownLatch latch = new CountDownLatch(5);
		HttpClient httpClient =
				createClient(pool, disposableServer::address)
				        .protocol(H2)
				        .secure(spec -> spec.sslContext(sslClient))
				        .doOnConnected(connection -> latch.countDown());

		try {
			IntStream.range(0, 5)
			         .forEach(unUsed ->
			                 httpClient.get()
			                           .uri("/")
			                           .responseSingle((resp, bytes) -> bytes.asString())
			                           .subscribe());

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(isRegistered.get()).isTrue();
			assertThat(metrics.get().activeStreamSize()).isEqualTo(5);
		}
		finally {
			pool.disposeLater().block(Duration.ofSeconds(5));

			assertThat(isDeregistered.get()).isTrue();
		}
	}

	@Test
	void measurePendingStreamsSize() {
		AtomicBoolean isRegistered = new AtomicBoolean();
		AtomicBoolean isDeregistered = new AtomicBoolean();
		AtomicReference<HttpConnectionPoolMetrics> metrics = new AtomicReference<>();

		disposableServer =
				createServer()
				        .protocol(H2)
				        .secure(spec -> spec.sslContext(sslServer))
				        .handle((req, resp) -> resp.sendString(Mono.delay(Duration.ofSeconds(1)).then(Mono.just("test"))))
				        .bindNow();

		CustomHttp2MeterRegistrar registrar = new CustomHttp2MeterRegistrar(isRegistered, isDeregistered, metrics);
		ConnectionProvider pool =
				ConnectionProvider.builder("custom-pool")
				                  .metrics(true, () -> registrar)
				                  .maxConnections(1)
				                  .pendingAcquireMaxCount(5)
				                  .pendingAcquireTimeout(Duration.ofSeconds(20))
				                  .build();

		HttpClient httpClient =
				createClient(pool, disposableServer::address)
				        .protocol(H2)
				        .secure(spec -> spec.sslContext(sslClient))
				        .http2Settings(builder -> {
				            builder.maxStreams(1);
				            builder.maxConcurrentStreams(1);
				        });

		try {
			IntStream.range(0, 5)
			         .forEach(unUsed ->
			                 httpClient.get()
			                           .uri("/")
			                           .responseSingle((resp, bytes) -> bytes.asString())
			                           .subscribe());

			assertThat(isRegistered.get()).isTrue();
			assertThat(metrics.get().pendingAcquireSize()).isEqualTo(4);
		}
		finally {
			pool.disposeLater().block(Duration.ofSeconds(5));

			assertThat(isDeregistered.get()).isTrue();
		}
	}

	@Test
	void testIssue3804() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.send().delaySubscription(Duration.ofSeconds(1)))
				        .bindNow();

		AtomicBoolean isRegistered = new AtomicBoolean();
		AtomicBoolean isDeregistered = new AtomicBoolean();
		AtomicReference<HttpConnectionPoolMetrics> metrics = new AtomicReference<>();
		CustomHttp2MeterRegistrar registrar = new CustomHttp2MeterRegistrar(isRegistered, isDeregistered, metrics);
		ConnectionProvider provider =
				ConnectionProvider.builder("testIssue3804")
				                  .metrics(true, () -> registrar)
				                  .build();

		try {
			CountDownLatch latch = new CountDownLatch(1);
			assertThatExceptionOfType(RuntimeException.class)
					.isThrownBy(() ->
							createClient(provider, disposableServer.port())
							        .doOnChannelInit((obs, ch, addr) ->
							            ch.pipeline().addAfter(NettyPipeline.HttpTrafficHandler, "testIssue3804",
							                new ChannelInboundHandlerAdapter() {
							                    @Override
							                    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
							                        latch.countDown();
							                        super.handlerRemoved(ctx);
							                    }
							                }))
							        .protocol(HttpProtocol.HTTP11, HttpProtocol.H2C)
							        .get()
							        .uri("/")
							        .response()
							        .block(Duration.ofMillis(200)))
					.withCauseInstanceOf(TimeoutException.class);

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(metrics.get()).isNotNull();
			assertThat(metrics.get().activeStreamSize()).isEqualTo(0);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	static final class CustomHttp2MeterRegistrar extends HttpMeterRegistrarAdapter {
		AtomicBoolean isRegistered;
		AtomicBoolean isDeregistered;
		AtomicReference<HttpConnectionPoolMetrics> metrics;

		CustomHttp2MeterRegistrar(
				AtomicBoolean isRegistered,
				AtomicBoolean isDeregistered,
				AtomicReference<HttpConnectionPoolMetrics> metrics) {
			this.isRegistered = isRegistered;
			this.isDeregistered = isDeregistered;
			this.metrics = metrics;
		}

		@Override
		public void registerMetrics(String poolName, String id, SocketAddress remoteAddress, HttpConnectionPoolMetrics metrics) {
			isRegistered.set(true);

			this.metrics.compareAndSet(null, metrics);
		}

		@Override
		public void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
			isDeregistered.set(true);
		}
	}
}
