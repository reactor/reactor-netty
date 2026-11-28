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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A TLS handshake failure is delivered while {@code ChannelOperations} is still absent.
 * The HTTP response {@link Mono} must still terminate, including when the cause is an
 * {@link OutOfMemoryError} that Reactor would otherwise rethrow as fatal.
 */
class HttpClientTlsHandshakeErrorTest extends BaseHttpTest {

	static X509Bundle ssc;
	static Http11SslContextSpec serverCtx;
	static Http11SslContextSpec clientCtx;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
		serverCtx = Http11SslContextSpec.forServer(ssc.toTempCertChainPem(), ssc.toTempPrivateKeyPem());
		clientCtx = Http11SslContextSpec.forClient()
		                                .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("handshakeFailures")
	@SuppressWarnings("deprecation")
	void tlsHandshakeFailureTerminatesTheResponseMono(String providerKind, Throwable handshakeCause) throws Exception {
		disposableServer =
				createServer()
				          .secure(spec -> spec.sslContext(serverCtx))
				          .handle((req, res) -> res.sendString(Mono.just("ok")))
				          .bindNow();

		ConnectionProvider provider = "pooled".equals(providerKind) ?
				ConnectionProvider.create("testIssue4504", 1) :
				ConnectionProvider.newConnection();

		AtomicReference<Throwable> observed = new AtomicReference<>();
		CountDownLatch observedLatch = new CountDownLatch(1);

		try {
			createClient(provider, disposableServer.port())
			          .secure(spec -> spec.sslContext(clientCtx))
			          .observe(new ConnectionObserver() {
			              @Override
			              public void onStateChange(Connection connection, State newState) {
			                  // handshake fails before HTTP operations exist
			              }

			              @Override
			              public void onUncaughtException(Connection connection, Throwable error) {
			                  observed.compareAndSet(null, error);
			                  observedLatch.countDown();
			              }
			          })
			          .doOnChannelInit((observer, channel, remoteAddress) ->
			                  channel.pipeline().addFirst("testHandshakeFailure", new ChannelInboundHandlerAdapter() {
			                      @Override
			                      public void channelActive(ChannelHandlerContext ctx) {
			                          ctx.fireUserEventTriggered(new SslHandshakeCompletionEvent(handshakeCause));
			                          ctx.close();
			                      }
			                  }))
			          .get()
			          .uri("/")
			          .responseContent()
			          .aggregate()
			          .asString()
			          .as(StepVerifier::create)
			          .expectErrorSatisfies(t -> {
			              if (handshakeCause instanceof OutOfMemoryError) {
			                  assertThat(t).isNotInstanceOf(OutOfMemoryError.class)
			                               .hasCauseInstanceOf(OutOfMemoryError.class);
			              }
			              else {
			                  assertThat(t).isSameAs(handshakeCause);
			              }
			          })
			          .verify(Duration.ofSeconds(5));

			assertThat(observedLatch.await(5, TimeUnit.SECONDS)).as("observer notified").isTrue();
			assertThat(observed.get()).isSameAs(handshakeCause);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(30));
		}
	}

	static Stream<Arguments> handshakeFailures() {
		return Stream.of(
				Arguments.of("pooled", new IllegalStateException("simulated TLS handshake failure")),
				Arguments.of("pooled", new OutOfMemoryError("simulated direct buffer reservation")),
				Arguments.of("newConnection", new IllegalStateException("simulated TLS handshake failure")),
				Arguments.of("newConnection", new OutOfMemoryError("simulated direct buffer reservation"))
		);
	}
}
