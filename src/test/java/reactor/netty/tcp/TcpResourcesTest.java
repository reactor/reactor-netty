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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;

public class TcpResourcesTest {

	private AtomicBoolean      loopDisposed;
	private AtomicBoolean      poolDisposed;
	private TcpResources       tcpResources;

	@Before
	public void before() {
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
			public Mono<? extends Connection> acquire(Bootstrap bootstrap) {
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
	@Ignore
	public void disposeLaterDefers() {
		assertThat(tcpResources.isDisposed()).isFalse();

		tcpResources.disposeLater();
		assertThat(tcpResources.isDisposed()).isFalse();

		tcpResources.disposeLater()
		            .doOnSuccess(c -> assertThat(tcpResources.isDisposed()).isTrue())
		            .subscribe();
		//not immediately disposed when subscribing
		assertThat(tcpResources.isDisposed()).as("immediate status on disposeLater subscribe").isFalse();
	}

	@Test
	public void shutdownLaterDefers() {
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
	public void blockShouldFail() throws InterruptedException {
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

		assertTrue("latch was counted down", latch.await(5, TimeUnit.SECONDS));

		client.dispose();
		server.disposeNow();
	}
}