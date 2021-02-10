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
package reactor.netty.tcp;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.TransportConfig;

import static org.assertj.core.api.Assertions.assertThat;

class TcpResourcesTest {

	private AtomicBoolean      loopDisposed;
	private AtomicBoolean      poolDisposed;
	private TcpResources       tcpResources;

	@BeforeEach
	void before() {
		loopDisposed = new AtomicBoolean();
		poolDisposed = new AtomicBoolean();

		LoopResources loopResources = new LoopResources() {
			@Override
			public EventLoopGroup onServer(boolean useNative) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Mono<Void> disposeLater(Duration quietPeriod, Duration timeout) {
				return Mono.<Void>empty().doOnSuccess(c -> loopDisposed.set(true));
			}

			@Override
			public boolean isDisposed() {
				return loopDisposed.get();
			}
		};

		ConnectionProvider poolResources = new ConnectionProvider() {

			@Override
			public Mono<? extends Connection> acquire(TransportConfig config,
					ConnectionObserver observer,
					Supplier<? extends SocketAddress> remoteAddress,
					AddressResolverGroup<?> resolverGroup) {
				return Mono.never();
			}

			@Override
			public Mono<Void> disposeLater() {
				return Mono.<Void>empty().doOnSuccess(c -> poolDisposed.set(true));
			}

			@Override
			public boolean isDisposed() {
				return poolDisposed.get();
			}
		};

		tcpResources = new TcpResources(loopResources, poolResources);
	}

	@Test
	void shutdownLaterDefers() {
		TcpResources oldTcpResources = TcpResources.tcpResources.getAndSet(tcpResources);
		TcpResources newTcpResources = TcpResources.tcpResources.get();

		try {
			assertThat(newTcpResources).isSameAs(tcpResources);

			TcpResources.disposeLoopsAndConnectionsLater();
			assertThat(newTcpResources.isDisposed()).isFalse();

			TcpResources.disposeLoopsAndConnectionsLater().block();
			assertThat(newTcpResources.isDisposed()).as("disposeLoopsAndConnectionsLater completion").isTrue();

			assertThat(TcpResources.tcpResources.get()).isNull();
		}
		finally {
			if (oldTcpResources != null && !TcpResources.tcpResources.compareAndSet(null, oldTcpResources)) {
				oldTcpResources.dispose();
			}
		}
	}

	@Test
	void blockShouldFail() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(2);

		DisposableServer server = TcpServer.create()
		                                   .port(port)
		                                   .handle((in, out) -> {
		                               	try {
			                                in.receive()
			                                  .blockFirst();
		                                }
		                                catch (RuntimeException e) {
		                               		latch.countDown();
		                               		throw e;
		                                }

			                               return Flux.never();
		                               })
		                                   .bindNow();

		Connection client = TcpClient.newConnection()
		                             .port(port)
		                             .handle((in, out) -> {
		                               	try {
			                                out.sendString(Flux.just("Hello World!"))
			                                   .then()
			                                   .block();
		                                }
		                                catch (RuntimeException e) {
			                                latch.countDown();
			                                throw e;
		                                }
		                               	    return Mono.never();
		                               })
		                             .connectNow();

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch was counted down").isTrue();

		client.dispose();
		server.disposeNow();
	}

	@Test
	void testIssue1227() {
		TcpResources.get();

		TcpResources old = TcpResources.tcpResources.get();

		LoopResources loops = LoopResources.create("testIssue1227");
		TcpResources.set(loops);
		ConnectionProvider provider = ConnectionProvider.create("testIssue1227");
		TcpResources.set(provider);
		assertThat(old.isDisposed()).isTrue();

		TcpResources current = TcpResources.tcpResources.get();
		TcpResources.disposeLoopsAndConnectionsLater()
		            .block();
		assertThat(current.isDisposed()).isTrue();
	}
}