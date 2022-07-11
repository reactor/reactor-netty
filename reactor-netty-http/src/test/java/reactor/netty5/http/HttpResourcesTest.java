/*
 * Copyright (c) 2017-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.netty5.channel.EventLoopGroup;
import io.netty5.resolver.AddressResolverGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.resources.ConnectionProvider;
import reactor.netty5.resources.LoopResources;
import reactor.netty5.transport.TransportConfig;
import reactor.util.annotation.Nullable;

import static org.assertj.core.api.Assertions.assertThat;

class HttpResourcesTest {

	private AtomicBoolean      loopDisposed;
	private AtomicBoolean      poolDisposed;
	private HttpResources      testResources;

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
					@Nullable Supplier<? extends SocketAddress> remoteAddress,
					@Nullable AddressResolverGroup<?> resolverGroup) {
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

		testResources = new HttpResources(loopResources, poolResources);
	}

	@Test
	void shutdownLaterDefers() {
		HttpResources oldHttpResources = HttpResources.httpResources.getAndSet(testResources);
		HttpResources newHttpResources = HttpResources.httpResources.get();

		try {
			assertThat(newHttpResources).isSameAs(testResources);

			HttpResources.disposeLoopsAndConnectionsLater();
			assertThat(newHttpResources.isDisposed()).isFalse();

			HttpResources.disposeLoopsAndConnectionsLater().block();
			assertThat(newHttpResources.isDisposed()).as("disposeLoopsAndConnectionsLater completion").isTrue();

			assertThat(HttpResources.httpResources.get()).isNull();
		}
		finally {
			if (oldHttpResources != null && !HttpResources.httpResources.compareAndSet(null, oldHttpResources)) {
				oldHttpResources.dispose();
			}
		}
	}

	@Test
	void testIssue1227() {
		HttpResources.get();

		HttpResources old = HttpResources.httpResources.get();

		LoopResources loops = LoopResources.create("testIssue1227");
		HttpResources.set(loops);
		ConnectionProvider provider = ConnectionProvider.create("testIssue1227");
		HttpResources.set(provider);
		assertThat(old.isDisposed()).isTrue();

		HttpResources current = HttpResources.httpResources.get();
		HttpResources.disposeLoopsAndConnectionsLater()
		             .block();
		assertThat(current.isDisposed()).isTrue();
	}
}