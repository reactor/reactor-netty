/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
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
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuple2;

import java.nio.charset.Charset;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This class tests https://github.com/reactor/reactor-netty/issues/1472.
 */
class ConnectionPoolTests extends BaseHttpTest {

	static DisposableServer server1;
	static DisposableServer server2;
	static DisposableServer server3;
	static DisposableServer server4;
	static ConnectionProvider provider;
	static LoopResources loop;
	static Supplier<ChannelMetricsRecorder> metricsRecorderSupplier;
	static final EventExecutor executor = new DefaultEventExecutor();

	HttpClient client;

	@BeforeAll
	@SuppressWarnings("deprecation")
	static void prepare() throws CertificateException {
		HttpServer server = createServer();

		server1 = server.handle((req, res) -> res.sendString(Mono.just("server1-ConnectionPoolTests")))
		                .bindNow();

		server2 = server.handle((req, res) -> res.sendString(Mono.just("server2-ConnectionPoolTests")))
		                .bindNow();

		SelfSignedCertificate cert = new SelfSignedCertificate();
		Http11SslContextSpec http11SslContextSpec = Http11SslContextSpec.forServer(cert.certificate(), cert.privateKey());
		Http2SslContextSpec http2SslContextSpec = Http2SslContextSpec.forServer(cert.certificate(), cert.privateKey());

		server3 = server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
		                .secure(spec -> spec.sslContext(http2SslContextSpec))
		                .handle((req, res) -> res.sendString(Mono.just("server3-ConnectionPoolTests")))
		                .bindNow();

		server4 = server.secure(spec -> spec.sslContext(http11SslContextSpec))
		                .handle((req, res) -> res.sendString(Mono.just("server4-ConnectionPoolTests")))
		                .bindNow();

		provider = ConnectionProvider.create("ConnectionPoolTests", 1);

		loop = LoopResources.create("ConnectionPoolTests");

		metricsRecorderSupplier = () -> Mockito.mock(ChannelMetricsRecorder.class);
	}

	@AfterAll
	static void tearDown() throws ExecutionException, InterruptedException, TimeoutException {
		server1.disposeNow();
		server2.disposeNow();
		server3.disposeNow();
		server4.disposeNow();
		provider.disposeLater()
		        .block(Duration.ofSeconds(5));
		loop.disposeLater()
		    .block(Duration.ofSeconds(5));
		executor.shutdownGracefully()
				.get(30, TimeUnit.SECONDS);
	}

	@BeforeEach
	void setUp() {
		client = HttpClient.create(provider);
	}

	@Test
	void testClientWithPort() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.port(server2.port());
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server2-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithAddress() {
		HttpClient localClient1 = client.remoteAddress(server1::address);
		HttpClient localClient2 = localClient1.remoteAddress(server2::address);
		checkResponsesAndChannelsStates(
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
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithChannelGroup() throws ExecutionException, InterruptedException, TimeoutException {
		ChannelGroup group1 = new DefaultChannelGroup(executor);
		ChannelGroup group2 = new DefaultChannelGroup(executor);

		try {
			HttpClient localClient1 =
					client.port(server1.port())
							.channelGroup(group1);
			HttpClient localClient2 = localClient1.channelGroup(group2);
			checkResponsesAndChannelsStates(
					"server1-ConnectionPoolTests",
					"server1-ConnectionPoolTests",
					localClient1,
					localClient2);
		}
		finally {
			group1.close()
					.get(30, TimeUnit.SECONDS);
			group2.close()
					.get(30, TimeUnit.SECONDS);
		}
	}

	@Test
	void testClientWithDoOnChannelInit() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .doOnChannelInit((observer, channel, address) -> {});
		HttpClient localClient2 = localClient1.doOnChannelInit((observer, channel, address) -> {});
		checkResponsesAndChannelsStates(
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
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithWiretapSameConfiguration() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .wiretap("testClientWithWiretapSameConfiguration", LogLevel.DEBUG,
				              AdvancedByteBufFormat.TEXTUAL, Charset.defaultCharset());
		HttpClient localClient2 = localClient1.wiretap("testClientWithWiretapSameConfiguration", LogLevel.DEBUG,
				AdvancedByteBufFormat.TEXTUAL, Charset.defaultCharset());
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2,
				true);
	}

	@Test
	void testDifferentClientWithWiretapSameConfiguration() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .wiretap("testClientWithWiretapSameConfiguration", LogLevel.DEBUG, AdvancedByteBufFormat.HEX_DUMP);
		HttpClient localClient2 =
				client.port(server1.port())
				      .wiretap("testClientWithWiretapSameConfiguration", LogLevel.DEBUG, AdvancedByteBufFormat.HEX_DUMP);
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2,
				true);
	}

	@Test
	void testClientWithRunOn() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.runOn(loop);
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithRunOnPreferNativeFalse() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.runOn(loop, false);
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithMetricsDifferentRecorders() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .metrics(true, Function.identity());
		HttpClient localClient2 = localClient1.metrics(true, metricsRecorderSupplier);
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testSameClientWithMetricsSameSupplierDifferentRecorders() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .metrics(true, metricsRecorderSupplier);
		// Resolver that provides information for the resolution time, works with the metrics recorder
		assertThat(localClient1.configuration().resolverInternal())
				.isSameAs(localClient1.configuration().resolverInternal());
		checkResponsesAndChannelsStates("server1-ConnectionPoolTests", localClient1);
	}

	@Test
	void testClientWithMetricsDifferentUriTagValueMappers() {
		HttpClient localClient1 =
				client.port(server1.port())
				      .metrics(true, Function.identity());
		HttpClient localClient2 = localClient1.metrics(true, s -> "testClientWithMetricsDifferentUriTagValueMappers");
		checkResponsesAndChannelsStates(
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
		checkResponsesAndChannelsStates(
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
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithResolver() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.resolver(DefaultAddressResolverGroup.INSTANCE);
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithCompress() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.compress(true);
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	void testClientWithDecoder() {
		HttpClient localClient1 = client.port(server1.port());
		HttpClient localClient2 = localClient1.httpResponseDecoder(spec -> spec.maxInitialLineLength(256));
		checkResponsesAndChannelsStates(
				"server1-ConnectionPoolTests",
				"server1-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testClientWithProtocols() {
		Http11SslContextSpec http11SslContextSpec =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		Http2SslContextSpec http2SslContextSpec =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		HttpClient localClient1 =
				client.port(server3.port())
				      .secure(spec -> spec.sslContext(http11SslContextSpec));
		HttpClient localClient2 =
				localClient1.protocol(HttpProtocol.H2)
				            .secure(spec -> spec.sslContext(http2SslContextSpec));
		checkResponsesAndChannelsStates(
				"server3-ConnectionPoolTests",
				"server3-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testClientWithSecurity_1() {
		Http11SslContextSpec http11SslContextSpec1 =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		Http11SslContextSpec http11SslContextSpec2 =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		HttpClient localClient1 =
				client.port(server4.port())
				      .secure(spec -> spec.sslContext(http11SslContextSpec1));
		HttpClient localClient2 = localClient1.secure(spec -> spec.sslContext(http11SslContextSpec2));
		checkResponsesAndChannelsStates(
				"server4-ConnectionPoolTests",
				"server4-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testClientWithSecurity_2() {
		HttpClient localClient1 =
				client.port(server4.port())
				      .secure(spec ->
				          spec.sslContext(
				              Http11SslContextSpec.forClient()
				                                  .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE))));
		HttpClient localClient2 =
				localClient1.secure(spec ->
				    spec.sslContext(
				        Http11SslContextSpec.forClient()
				                            .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE))));
		checkResponsesAndChannelsStates(
				"server4-ConnectionPoolTests",
				"server4-ConnectionPoolTests",
				localClient1,
				localClient2);
	}

	private void checkResponsesAndChannelsStates(String expectedClient1Response, HttpClient client1) {
		checkResponsesAndChannelsStates(expectedClient1Response, null, client1, null);
	}

	private void checkResponsesAndChannelsStates(
			String expectedClient1Response,
			@Nullable String expectedClient2Response,
			HttpClient client1,
			@Nullable HttpClient client2) {
		checkResponsesAndChannelsStates(expectedClient1Response, expectedClient2Response, client1, client2, false);
	}

	private void checkResponsesAndChannelsStates(
				String expectedClient1Response,
				@Nullable String expectedClient2Response,
				HttpClient client1,
				@Nullable HttpClient client2,
				boolean isSame) {

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
		assertThat(response1.get(0).getT1()).isEqualTo(expectedClient1Response);
		assertThat(response1.get(1).getT1()).isEqualTo(expectedClient1Response);

		assertThat(response1.get(0).getT2()).isSameAs(response1.get(1).getT2());

		if (client2 != null) {
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
			assertThat(response2.get(0).getT1()).isEqualTo(expectedClient2Response);

			if (isSame) {
				assertThat(response2.get(0).getT2()).isSameAs(response1.get(0).getT2());
			}
			else {
				assertThat(response2.get(0).getT2()).isNotSameAs(response1.get(0).getT2());
			}
		}
	}
}
