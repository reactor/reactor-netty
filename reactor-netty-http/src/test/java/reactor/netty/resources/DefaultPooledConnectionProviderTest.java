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
package reactor.netty.resources;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.ConnectionObserver;
import reactor.netty.http.client.HttpClient;
import reactor.netty.internal.shaded.reactor.pool.InstrumentedPool;
import reactor.test.StepVerifier;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPooledConnectionProviderTest extends BaseHttpTest {

	@Test
	void testIssue903() throws CertificateException {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverCtx = SslContextBuilder.forServer(cert.key(), cert.cert());
		disposableServer =
				createServer()
				          .secure(s -> s.sslContext(serverCtx))
				          .handle((req, resp) -> resp.sendHeaders())
				          .bindNow();

		DefaultPooledConnectionProvider provider = (DefaultPooledConnectionProvider) ConnectionProvider.create("testIssue903", 1);
		createClient(provider, disposableServer.port())
		          .get()
		          .uri("/")
		          .response()
		          .onErrorResume(e -> Mono.empty())
		          .block(Duration.ofSeconds(30));

		provider.channelPools.forEach((k, v) -> assertThat(v.metrics().acquiredSize()).isEqualTo(0));

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	void testIssue973() {
		disposableServer =
				createServer()
				          .handle((req, resp) -> resp.sendHeaders())
				          .bindNow();

		DefaultPooledConnectionProvider provider =
				(DefaultPooledConnectionProvider) ConnectionProvider.builder("testIssue973")
				                                                    .maxConnections(2)
				                                                    .forRemoteHost(InetSocketAddress.createUnresolved("localhost", disposableServer.port()),
				                                                            spec -> spec.maxConnections(1))
				                                                    .build();
		AtomicReference<InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection>> pool1 = new AtomicReference<>();
		HttpClient.create(provider)
		          .doOnConnected(conn -> {
		              ConcurrentMap<PooledConnectionProvider.PoolKey, InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection>> pools =
		                      provider.channelPools;
		              for (InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection> pool : pools.values()) {
		                  if (pool.metrics().acquiredSize() == 1) {
		                      pool1.set(pool);
		                      return;
		                  }
		              }
		          })
		          .wiretap(true)
		          .get()
		          .uri("http://localhost:" + disposableServer.port() + "/")
		          .responseContent()
		          .aggregate()
		          .block(Duration.ofSeconds(30));

		assertThat(pool1.get()).isNotNull();

		AtomicReference<InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection>> pool2 = new AtomicReference<>();
		HttpClient.create(provider)
		          .doOnConnected(conn -> {
		              ConcurrentMap<PooledConnectionProvider.PoolKey, InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection>> pools =
		                      provider.channelPools;
		              for (InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection> pool : pools.values()) {
		                  if (pool.metrics().acquiredSize() == 1) {
		                      pool2.set(pool);
		                      return;
		                  }
		              }
		          })
		          .wiretap(true)
		          .get()
		          .uri("https://example.com/")
		          .responseContent()
		          .aggregate()
		          .block(Duration.ofSeconds(30));

		assertThat(pool2.get()).isNotNull();
		assertThat(pool1.get()).as(pool1.get() + " " + pool2.get()).isNotSameAs(pool2.get());

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	void testIssue1012() throws Exception {
		disposableServer =
				createServer()
				          .route(r -> r.get("/1", (req, resp) -> resp.sendString(Mono.just("testIssue1012")))
				                       .get("/2", (req, res) -> Mono.error(new RuntimeException("testIssue1012"))))
				          .bindNow();

		DefaultPooledConnectionProvider provider = (DefaultPooledConnectionProvider) ConnectionProvider.create("testIssue1012", 1);
		CountDownLatch latch = new CountDownLatch(1);
		HttpClient client =
				createClient(provider, disposableServer.port())
				          .doOnConnected(conn -> conn.channel().closeFuture().addListener(f -> latch.countDown()));

		client.get()
		      .uri("/1")
		      .responseContent()
		      .aggregate()
		      .block(Duration.ofSeconds(30));

		client.get()
		      .uri("/2")
		      .responseContent()
		      .aggregate()
		      .onErrorResume(e -> Mono.empty())
		      .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		provider.channelPools.forEach((k, v) -> assertThat(v.metrics().acquiredSize()).isEqualTo(0));

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	void connectionReleasedOnRedirect() throws Exception {
		String redirectedContent = StringUtils.repeat("a", 10000);
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.get("/1", (req, res) -> res.status(HttpResponseStatus.FOUND)
				                                                   .header(HttpHeaderNames.LOCATION, "/2")
				                                                   .sendString(Flux.just(redirectedContent, redirectedContent)))
				                       .get("/2", (req, res) -> res.status(200)
				                                                   .sendString(Mono.just("OK"))))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(2);
		DefaultPooledConnectionProvider provider =
				(DefaultPooledConnectionProvider) ConnectionProvider.create("connectionReleasedOnRedirect", 1);
		String response =
				createClient(provider, disposableServer::address)
				          .followRedirect(true)
				          .observe((conn, state) -> {
				              if (ConnectionObserver.State.RELEASED == state) {
				                  latch.countDown();
				              }
				          })
				          .get()
				          .uri("/1")
				          .responseContent()
				          .aggregate()
				          .asString()
				          .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("OK");

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		provider.channelPools.forEach((k, v) -> assertThat(v.metrics().acquiredSize()).isEqualTo(0));

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	@Disabled
	void testSslEngineClosed() throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.sendString(Mono.just("test")))
				          .bindNow();
		SslContext ctx = SslContextBuilder.forClient()
		                                  .sslProvider(SslProvider.JDK)
		                                  .build();
		HttpClient client =
				createClient(disposableServer.port())
				          .secure(spec -> spec.sslContext(ctx));

		// Connection close happens after `Channel connected`
		// Re-acquiring is not possible
		// The SSLException will be propagated
		doTestSslEngineClosed(client, new AtomicInteger(0), SSLException.class, "SSLEngine is closing/closed");

		// Connection close happens between `Initialized pipeline` and `Channel connected`
		// Re-acquiring
		// Connection close happens after `Channel connected`
		// The SSLException will be propagated, Reactor Netty re-acquire only once
		doTestSslEngineClosed(client, new AtomicInteger(1), SSLException.class, "SSLEngine is closing/closed");

		// Connection close happens between `Initialized pipeline` and `Channel connected`
		// Re-acquiring
		// Connection close happens between `Initialized pipeline` and `Channel connected`
		// The IOException will be propagated, Reactor Netty re-acquire only once
		doTestSslEngineClosed(client, new AtomicInteger(2), IOException.class, "Error while acquiring from");
	}

	private void doTestSslEngineClosed(HttpClient client, AtomicInteger closeCount, Class<? extends Throwable> expectedExc, String expectedMsg) {
		Mono<String> response =
				client.doOnChannelInit(
				        (o, c, address) ->
				            c.pipeline()
				             .addFirst(new ChannelOutboundHandlerAdapter() {

				                 @Override
				                 public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
				                         SocketAddress localAddress, ChannelPromise promise) throws Exception {
				                     super.connect(ctx, remoteAddress, localAddress,
				                             new TestPromise(ctx.channel(), promise, closeCount));
				                 }
				             }))
				      .get()
				      .uri("/")
				      .responseContent()
				      .aggregate()
				      .asString();

		StepVerifier.create(response)
		            .expectErrorMatches(t -> t.getClass().isAssignableFrom(expectedExc) && t.getMessage().startsWith(expectedMsg))
		            .verify(Duration.ofSeconds(30));
	}

	static final class TestPromise extends DefaultChannelPromise {

		final ChannelPromise parent;
		final AtomicInteger closeCount;

		public TestPromise(Channel channel, ChannelPromise parent, AtomicInteger closeCount) {
			super(channel);
			this.parent = parent;
			this.closeCount = closeCount;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public boolean trySuccess(Void result) {
			boolean r;
			if (closeCount.getAndDecrement() > 0) {
				//"FutureReturnValueIgnored" this is deliberate
				channel().close();
				r = parent.trySuccess(result);
			}
			else {
				r = parent.trySuccess(result);
				//"FutureReturnValueIgnored" this is deliberate
				channel().close();
			}
			return r;
		}
	}
}
