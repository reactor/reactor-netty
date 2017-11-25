/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.http;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.ChannelPool;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.resources.PoolResources;

import static org.assertj.core.api.Assertions.assertThat;

public class HttpResourcesTest {

	private AtomicBoolean loopDisposed;
	private AtomicBoolean poolDisposed;
	private LoopResources loopResources;
	private PoolResources poolResources;
	private HttpResources testResources;

	@Before
	public void before() {
		loopDisposed = new AtomicBoolean();
		poolDisposed = new AtomicBoolean();

		loopResources = new LoopResources() {
			@Override
			public EventLoopGroup onServer(boolean useNative) {
				return null;
			}

			@Override
			public Mono<Void> disposeLater() {
				return Mono.<Void>empty().doOnSuccess(c -> loopDisposed.set(true));
			}

			@Override
			public boolean isDisposed() {
				return loopDisposed.get();
			}
		};

		poolResources = new PoolResources() {
			@Override
			public ChannelPool selectOrCreate(SocketAddress address,
											  Supplier<? extends Bootstrap> bootstrap,
											  ContextHandler ctx, EventLoopGroup group) {
				return null;
			}

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
	public void disposeLaterDefers() {
		assertThat(testResources.isDisposed()).isFalse();

		testResources.disposeLater();
		assertThat(testResources.isDisposed()).isFalse();

		testResources.disposeLater()
		             .doOnSuccess(c -> assertThat(testResources.isDisposed()).isTrue())
		             .subscribe();
		//not immediately disposed when subscribing
		assertThat(testResources.isDisposed()).as("immediate status on disposeLater subscribe").isFalse();
	}

	@Test
	public void shutdownLaterDefers() {
		HttpResources oldHttpResources = HttpResources.httpResources.getAndSet(testResources);
		HttpResources newHttpResources = HttpResources.httpResources.get();

		try {
			assertThat(newHttpResources).isSameAs(testResources);

			HttpResources.shutdownLater();
			assertThat(newHttpResources.isDisposed()).isFalse();

			HttpResources.shutdownLater().block();
			assertThat(newHttpResources.isDisposed()).as("shutdownLater completion").isTrue();

			assertThat(HttpResources.httpResources.get()).isNull();
		}
		finally {
			if (oldHttpResources != null && !HttpResources.httpResources.compareAndSet(null, oldHttpResources)) {
				oldHttpResources.dispose();
			}
		}
	}


}