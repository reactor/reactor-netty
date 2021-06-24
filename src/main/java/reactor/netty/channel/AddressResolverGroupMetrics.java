/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
final class AddressResolverGroupMetrics<T extends SocketAddress> extends AddressResolverGroup<T> {
	final AddressResolverGroup<T> resolverGroup;

	final ChannelMetricsRecorder recorder;

	AddressResolverGroupMetrics(AddressResolverGroup<T> resolverGroup,
			@Nullable ChannelMetricsRecorder recorder) {
		this.resolverGroup = resolverGroup;
		this.recorder = recorder;
	}

	@Override
	protected AddressResolver<T> newResolver(EventExecutor executor) {
		AddressResolver<T> resolver = resolverGroup.getResolver(executor);

		return new AddressResolver<T>() {

			@Override
			public boolean isSupported(SocketAddress address) {
				return resolver.isSupported(address);
			}

			@Override
			public boolean isResolved(SocketAddress address) {
				return resolver.isResolved(address);
			}

			@Override
			public Future<T> resolve(SocketAddress address) {
				return resolveInternal(address, () -> resolver.resolve(address));
			}

			@Override
			public Future<T> resolve(SocketAddress address, Promise<T> promise) {
				return resolveInternal(address, () -> resolver.resolve(address, promise));
			}

			@Override
			public Future<List<T>> resolveAll(SocketAddress address) {
				return resolveAllInternal(address, () -> resolver.resolveAll(address));
			}

			@Override
			public Future<List<T>> resolveAll(SocketAddress address, Promise<List<T>> promise) {
				return resolveAllInternal(address, () -> resolver.resolveAll(address, promise));
			}

			@Override
			public void close() {
				resolver.close();
			}

			Future<T> resolveInternal(SocketAddress address, Supplier<Future<T>> resolver) {
				long resolveTimeStart = System.nanoTime();
				return resolver.get()
				               .addListener(
				                   future -> record(resolveTimeStart,
				                                    future.isSuccess() ? SUCCESS : ERROR,
				                                    address));
			}

			Future<List<T>> resolveAllInternal(SocketAddress address, Supplier<Future<List<T>>> resolver) {
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
