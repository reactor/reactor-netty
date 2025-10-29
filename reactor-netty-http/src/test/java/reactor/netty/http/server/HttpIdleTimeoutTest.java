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
package reactor.netty.http.server;

import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.tcp.SslProvider;
import reactor.test.StepVerifier;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class HttpIdleTimeoutTest extends BaseHttpTest {

	static Http2SslContextSpec clientCtx2;
	static Http11SslContextSpec clientCtx11;
	static Http2SslContextSpec serverCtx2;
	static Http11SslContextSpec serverCtx11;
	static SelfSignedCertificate ssc;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new SelfSignedCertificate();
		clientCtx2 = Http2SslContextSpec.forClient()
		                                .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		clientCtx11 = Http11SslContextSpec.forClient()
		                                  .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		serverCtx2 = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		serverCtx11 = Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	@SuppressWarnings("deprecation")
	void closedAfterIdleTimeout(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx, @Nullable SslProvider.ProtocolSslContextSpec clientCtx) {
		AtomicReference<Channel> channel = new AtomicReference<>();
		disposableServer =
				(serverCtx == null ? createServer() : createServer().secure(spec -> spec.sslContext(serverCtx)))
				        .protocol(serverProtocols)
				        .idleTimeout(Duration.ofMillis(100))
				        .handle((req, resp) ->
				                resp.withConnection(conn ->
				                            channel.set(conn.channel() instanceof Http2StreamChannel ? conn.channel().parent() : conn.channel()))
				                    .sendString(Mono.just("closedAfterIdleTimeout")))
				        .bindNow();

		(clientCtx == null ? createClient(disposableServer::address) : createClient(disposableServer::address).secure(spec -> spec.sslContext(clientCtx)))
		        .protocol(clientProtocols)
		        .get()
		        .uri("/")
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext("closedAfterIdleTimeout")
		        .expectComplete()
		        .verify(Duration.ofSeconds(5));

		Mono.delay(Duration.ofMillis(200))
		    .block();

		assertThat(channel.get()).isNotNull();
		assertThat(channel.get().isOpen()).isFalse();
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	@SuppressWarnings("deprecation")
	void openedBeforeIdleTimeout(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx, @Nullable SslProvider.ProtocolSslContextSpec clientCtx) {
		AtomicReference<Channel> channel = new AtomicReference<>();
		disposableServer =
				(serverCtx == null ? createServer() : createServer().secure(spec -> spec.sslContext(serverCtx)))
				        .protocol(serverProtocols)
				        .idleTimeout(Duration.ofMillis(500))
				        .handle((req, resp) ->
				                resp.withConnection(conn ->
				                            channel.set(conn.channel() instanceof Http2StreamChannel ? conn.channel().parent() : conn.channel()))
				                    .sendString(Mono.just("openedBeforeIdleTimeout")))
				        .bindNow();

		(clientCtx == null ? createClient(disposableServer::address) : createClient(disposableServer::address).secure(spec -> spec.sslContext(clientCtx)))
		        .protocol(clientProtocols)
		        .get()
		        .uri("/")
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext("openedBeforeIdleTimeout")
		        .expectComplete()
		        .verify(Duration.ofSeconds(1));

		Mono.delay(Duration.ofMillis(100))
		    .block();

		assertThat(channel.get()).isNotNull();
		assertThat(channel.get().isOpen()).isTrue();
	}

	static Stream<Arguments> httpCompatibleProtocols() {
		return Stream.of(
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11},
						Named.of("Http11SslContextSpec", serverCtx11), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http11SslContextSpec", serverCtx11), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C}, new HttpProtocol[]{HttpProtocol.H2C}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, null, null)
		);
	}
}
