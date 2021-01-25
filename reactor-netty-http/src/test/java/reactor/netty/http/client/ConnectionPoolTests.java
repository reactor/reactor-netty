/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.DisposableServer;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.util.function.Tuple2;

import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This class tests https://github.com/reactor/reactor-netty/issues/1472
 */
class ConnectionPoolTests extends BaseHttpTest {

	static DisposableServer server1;
	static DisposableServer server2;
	static DisposableServer server3;
	static DisposableServer server4;
	static ConnectionProvider provider;
	static LoopResources loop;
	static ChannelMetricsRecorder metricsRecorder;

	HttpClient client;

	@BeforeAll
	static void prepare() throws CertificateException {
		HttpServer server = createServer();

		server1 = server.handle((req, res) -> res.sendString(Mono.just("server1-ConnectionPoolTests")))
		                .bindNow();

		server2 = server.handle((req, res) -> res.sendString(Mono.just("server2-ConnectionPoolTests")))
		                .bindNow();

		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder sslContextBuilder = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());

		server3 = server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
		                .secure(spec -> spec.sslContext(sslContextBuilder))
		                .handle((req, res) -> res.sendString(Mono.just("server3-ConnectionPoolTests")))
		                .bindNow();

		server4 = server.secure(spec -> spec.sslContext(sslContextBuilder))
		                .handle((req, res) -> res.sendString(Mono.just("server4-ConnectionPoolTests")))
		                .bindNow();

		provider = ConnectionProvider.create("ConnectionPoolTests", 1);

		loop = LoopResources.create("ConnectionPoolTests");

		metricsRecorder = Mockito.mock(ChannelMetricsRecorder.class);
	}

	@AfterAll
	static void tearDown() {
		server1.disposeNow();
		server2.disposeNow();
		server3.disposeNow();
		server4.disposeNow();
		provider.disposeLater()
		        .block(Duration.ofSeconds(5));
		loop.disposeLater()
		    .block(Duration.ofSeconds(5));
	}

	@BeforeEach
	void setUp() {
		client = HttpClient.create(provider);
	}

	@Test
	void testClientWithPort() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.port(server2.port());
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server2-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithAddress() {
		HttpClient localClient1 = client.remoteAddress(server1::address);
		HttpClient localClient2 = localClient1.remoteAddress(server2::address);
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server2-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithAttributes() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .attr(AttributeKey.valueOf("attr1-ConnectionPoolTests"), "");
		HttpClient localClient2 = localClient1.attr(AttributeKey.valueOf("attr2-ConnectionPoolTests"), "");
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithChannelGroup() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .channelGroup(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
		HttpClient localClient2 = localClient1.channelGroup(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithDoOnChannelInit() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .doOnChannelInit((observer, channel, address) -> {});
		HttpClient localClient2 = localClient1.doOnChannelInit((observer, channel, address) -> {});
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithWiretap() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .wiretap(true);
		HttpClient localClient2 = localClient1.wiretap("reactor.netty.ConnectionPoolTests");
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithRunOn() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.runOn(loop);
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithRunOnPreferNativeFalse() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.runOn(loop, false);
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithMetrics() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .metrics(true, Function.identity());
		HttpClient localClient2 = localClient1.metrics(true, () -> metricsRecorder);
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithObserver() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .observe((conn, state) -> {});
		HttpClient localClient2 = localClient1.observe((conn, state) -> {});
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithOptions() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
		HttpClient localClient2 = localClient1.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000);
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithResolver() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.resolver(DefaultAddressResolverGroup.INSTANCE);
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithCompress() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.compress(true);
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithDecoder() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.httpResponseDecoder(spec -> spec.maxInitialLineLength(256));
		checkExpectations(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithProtocols() {
		SslContextBuilder sslContextBuilder1 =
				SslContextBuilder.forClient()
				                 .trustManager(InsecureTrustManagerFactory.INSTANCE);

		HttpClient localClient1 =
				client.port(server3.port())
				      .secure(spec -> spec.sslContext(sslContextBuilder1));
		HttpClient localClient2 = localClient1.protocol(HttpProtocol.H2);
		checkExpectations(
				"server3-ConnectionPoolTests",
				"server3-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithSecurity_1() {
		SslContextBuilder sslContextBuilder1 =
				SslContextBuilder.forClient()
				                 .trustManager(InsecureTrustManagerFactory.INSTANCE);

		SslContextBuilder sslContextBuilder2 =
				SslContextBuilder.forClient()
				                 .trustManager(InsecureTrustManagerFactory.INSTANCE);

		HttpClient localClient1 =
				client.port(server4.port())
				      .secure(spec -> spec.sslContext(sslContextBuilder1));
		HttpClient localClient2 = localClient1.secure(spec -> spec.sslContext(sslContextBuilder2));
		checkExpectations(
				"server4-ConnectionPoolTests",
				"server4-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithSecurity_2() {
		HttpClient localClient1 =
				client.port(server4.port())
				      .secure(spec ->
				          spec.sslContext(SslContextBuilder.forClient()
				                                           .trustManager(InsecureTrustManagerFactory.INSTANCE)));
		HttpClient localClient2 =
				localClient1.secure(spec ->
				    spec.sslContext(SslContextBuilder.forClient()
				                                     .trustManager(InsecureTrustManagerFactory.INSTANCE)));
		checkExpectations(
				"server4-ConnectionPoolTests",
				"server4-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	private void checkExpectations(String client1Response, String client2Response, HttpClient client1, HttpClient client2) {
		List<Tuple2<String, Channel>> response1 =
		Flux.range(0, 2)
		    .concatMap(i ->
		        client1.get()
		               .uri("/")
		               .responseConnection((res, conn) ->
		                   conn.inbound()
		                       .receive()
		                       .aggregate()
		                       .asString()
		                       .zipWith(Mono.just(conn.channel()))))
		    .collectList()
		    .block(Duration.ofSeconds(10));

		assertThat(response1).isNotNull().hasSize(2);
		assertThat(response1.get(0).getT1()).isEqualTo(client1Response);
		assertThat(response1.get(1).getT1()).isEqualTo(client1Response);
		assertThat(response1.get(0).getT2()).isSameAs(response1.get(1).getT2());

		List<Tuple2<String, Channel>> response2 =
				client2.get()
				       .uri("/")
				       .responseConnection((res, conn) ->
				           conn.inbound()
				               .receive()
				               .aggregate()
				               .asString()
				               .zipWith(Mono.just(conn.channel())))
				       .collectList()
				       .block(Duration.ofSeconds(10));

		assertThat(response2).isNotNull().hasSize(1);
		assertThat(response2.get(0).getT1()).isEqualTo(client2Response);
		assertThat(response2.get(0).getT2()).isNotSameAs(response1.get(0).getT2());
	}
}
