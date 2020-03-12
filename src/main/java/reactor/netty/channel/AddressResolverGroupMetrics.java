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
package reactor.netty.channel;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.SUCCESS;

/**
 * @author Violeta Georgieva
 */
final class AddressResolverGroupMetrics extends AddressResolverGroup<SocketAddress> {
	final AddressResolverGroup<SocketAddress> resolverGroup;

	final ChannelMetricsRecorder recorder;

	AddressResolverGroupMetrics(AddressResolverGroup<SocketAddress> resolverGroup,
			@Nullable ChannelMetricsRecorder recorder) {
		this.resolverGroup = resolverGroup;
		this.recorder = recorder;
	}

	@Override
	protected AddressResolver<SocketAddress> newResolver(EventExecutor executor) {
		AddressResolver<SocketAddress> resolver = resolverGroup.getResolver(executor);

		return new AddressResolver<SocketAddress>() {

			@Override
			public boolean isSupported(SocketAddress address) {
				return resolver.isSupported(address);
			}

			@Override
			public boolean isResolved(SocketAddress address) {
				return resolver.isResolved(address);
			}

			@Override
			public Future<SocketAddress> resolve(SocketAddress address) {
				return resolveInternal(address, () -> resolver.resolve(address));
			}

			@Override
			public Future<SocketAddress> resolve(SocketAddress address, Promise<SocketAddress> promise) {
				return resolveInternal(address, () -> resolver.resolve(address, promise));
			}

			@Override
			public Future<List<SocketAddress>> resolveAll(SocketAddress address) {
				return resolveAllInternal(address, () -> resolver.resolveAll(address));
			}

			@Override
			public Future<List<SocketAddress>> resolveAll(SocketAddress address, Promise<List<SocketAddress>> promise) {
				return resolveAllInternal(address, () -> resolver.resolveAll(address, promise));
			}

			@Override
			public void close() {
				resolver.close();
			}

			Future<SocketAddress> resolveInternal(SocketAddress address, Supplier<Future<SocketAddress>> resolver) {
				long resolveTimeStart = System.nanoTime();
				return resolver.get()
				               .addListener(
				                   future -> record(resolveTimeStart,
				                                    future.isSuccess() ? SUCCESS : ERROR,
				                                    address));
			}

			Future<List<SocketAddress>> resolveAllInternal(SocketAddress address, Supplier<Future<List<SocketAddress>>> resolver) {
				long resolveTimeStart = System.nanoTime();
				return resolver.get()
				               .addListener(
				                   future -> record(resolveTimeStart,
				                                    future.isSuccess() ? SUCCESS : ERROR,
				                                    address));
			}

			void record(long resolveTimeStart, String status, SocketAddress remoteAddress) {
				recorder.recordResolveAddressTime(
						remoteAddress,
						Duration.ofNanos(System.nanoTime() - resolveTimeStart),
						status);
			}
		};
	}
}
