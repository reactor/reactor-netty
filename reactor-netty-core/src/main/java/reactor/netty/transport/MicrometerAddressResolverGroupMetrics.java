/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.transport;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.observability.ReactorNettyHandlerContext;

import java.net.SocketAddress;
import java.util.List;
import java.util.function.Supplier;

import static reactor.netty.Metrics.ADDRESS_RESOLVER;
import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.Metrics.formatSocketAddress;

/**
 * @author Violeta Georgieva
 * @since 1.1.0
 */
final class MicrometerAddressResolverGroupMetrics<T extends SocketAddress> extends AddressResolverGroupMetrics<T> {

	MicrometerAddressResolverGroupMetrics(AddressResolverGroup<T> resolverGroup, MicrometerChannelMetricsRecorder recorder) {
		super(resolverGroup, recorder);
	}

	@Override
	protected AddressResolver<T> newResolver(EventExecutor executor) {
		return new MicrometerDelegatingAddressResolver<>((MicrometerChannelMetricsRecorder) recorder, resolverGroup.getResolver(executor));
	}

	static final class FutureHandlerContext extends Timer.HandlerContext implements ReactorNettyHandlerContext {
		final String remoteAddress;

		// status is not known beforehand
		String status = SUCCESS;

		FutureHandlerContext(String remoteAddress) {
			this.remoteAddress = remoteAddress;
		}

		@Override
		public Tags getHighCardinalityTags() {
			return Tags.of("reactor.netty.status", status);
		}

		@Override
		public Tags getLowCardinalityTags() {
			return Tags.of(REMOTE_ADDRESS, remoteAddress, STATUS, status);
		}

		// TODO: This is hostname resolution?
		@Override
		public String getSimpleName() {
			return "address resolution";
		}
	}

	static final class MicrometerDelegatingAddressResolver<T extends SocketAddress> extends DelegatingAddressResolver<T> {

		final Timer.Builder addressResolverTimeBuilder;

		MicrometerDelegatingAddressResolver(MicrometerChannelMetricsRecorder recorder, AddressResolver<T> resolver) {
			super(recorder, resolver);
			this.addressResolverTimeBuilder =
					Timer.builder(recorder.name() + ADDRESS_RESOLVER)
					     .description("Time spent for resolving the address");
		}

		@Override
		Future<T> resolveInternal(SocketAddress address, Supplier<Future<T>> resolver) {
			// TODO
			// Cannot invoke the recorder any more:
			// 1. The recorder is one instance only, it is invoked for all name resolutions that can happen
			// 2. The recorder does not have knowledge about name resolution lifecycle
			// 3. There is no connection so we cannot hold the context information in the Channel
			//
			// Move the implementation from the recorder here
			//
			// Important:
			// Cannot cache the Timer anymore - need to test the performance
			// Can we use sample.stop(Timer)
			String remoteAddress = formatSocketAddress(address);
			// TODO: Fix scopes
			FutureHandlerContext handlerContext = new FutureHandlerContext(remoteAddress);
			handlerContext.put(SocketAddress.class, address);
			Timer.Sample sample = Timer.start(REGISTRY, handlerContext);
			return resolver.get()
			               .addListener(future -> {
			                   handlerContext.status = future.isSuccess() ? SUCCESS : ERROR;
			                   sample.stop(addressResolverTimeBuilder);
			               });
		}

		@Override
		Future<List<T>> resolveAllInternal(SocketAddress address, Supplier<Future<List<T>>> resolver) {
			// TODO
			// Cannot invoke the recorder any more:
			// 1. The recorder is one instance only, it is invoked for all name resolutions that can happen
			// 2. The recorder does not have knowledge about name resolution lifecycle
			// 3. There is no connection so we cannot hold the context information in the Channel
			//
			// Move the implementation from the recorder here
			//
			// Important:
			// Cannot cache the Timer anymore - need to test the performance
			// Can we use sample.stop(Timer)
			String remoteAddress = formatSocketAddress(address);
			FutureHandlerContext handlerContext = new FutureHandlerContext(remoteAddress);
			Timer.Sample sample = Timer.start(REGISTRY, handlerContext);
			return resolver.get()
			               .addListener(future -> {
			                   handlerContext.status = future.isSuccess() ? SUCCESS : ERROR;
			                   sample.stop(addressResolverTimeBuilder);
			               });
		}
	}
}
