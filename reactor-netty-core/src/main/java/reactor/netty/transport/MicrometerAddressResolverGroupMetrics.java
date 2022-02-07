/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.api.instrument.Tags;
import io.micrometer.api.instrument.observation.Observation;
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
//import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.Metrics.formatSocketAddress;

/**
 * @author Marcin Grzejszczak
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

	static final class FutureHandlerContext extends Observation.Context implements ReactorNettyHandlerContext {
		static final String CONTEXTUAL_NAME = "hostname resolution";
		static final String TYPE = "client";

		final String protocol;
		final String remoteAddress;

		// status is not known beforehand
		String status = SUCCESS;

		FutureHandlerContext(String protocol, String remoteAddress) {
			this.protocol = protocol;
			this.remoteAddress = remoteAddress;
		}

		@Override
		public String getContextualName() {
			return CONTEXTUAL_NAME;
		}

		@Override
		public Tags getHighCardinalityTags() {
			// TODO cache
			return Tags.of(HostnameResolutionObservations.HostnameResolutionTimeHighCardinalityTags.REACTOR_NETTY_STATUS.of(status),
			               HostnameResolutionObservations.HostnameResolutionTimeHighCardinalityTags.REACTOR_NETTY_TYPE.of(TYPE),
			               HostnameResolutionObservations.HostnameResolutionTimeHighCardinalityTags.REACTOR_NETTY_PROTOCOL.of(protocol));
		}

		@Override
		public Tags getLowCardinalityTags() {
			// TODO cache
			return Tags.of(HostnameResolutionObservations.HostnameResolutionTimeLowCardinalityTags.REMOTE_ADDRESS.of(remoteAddress),
			               HostnameResolutionObservations.HostnameResolutionTimeLowCardinalityTags.STATUS.of(status));
		}
	}

	static final class MicrometerDelegatingAddressResolver<T extends SocketAddress> extends DelegatingAddressResolver<T> {
		static final io.micrometer.api.instrument.MeterRegistry REGISTRY = io.micrometer.api.instrument.Metrics.globalRegistry;

		final MicrometerChannelMetricsRecorder localRecorder;

		MicrometerDelegatingAddressResolver(MicrometerChannelMetricsRecorder recorder, AddressResolver<T> resolver) {
			super(recorder, resolver);
			this.localRecorder = recorder;
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
			String remoteAddress = formatSocketAddress(address);
			FutureHandlerContext handlerContext = new FutureHandlerContext(localRecorder.protocol(), remoteAddress);
			handlerContext.put(SocketAddress.class, address);
			Observation observation = Observation.start(localRecorder.name() + ADDRESS_RESOLVER, handlerContext, REGISTRY);
			return resolver.get()
			               .addListener(future -> {
			                   handlerContext.status = future.isSuccess() ? SUCCESS : ERROR;
			                   observation.stop();
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
			String remoteAddress = formatSocketAddress(address);
			FutureHandlerContext handlerContext = new FutureHandlerContext(localRecorder.protocol(), remoteAddress);
			Observation sample = Observation.start(localRecorder.name() + ADDRESS_RESOLVER, handlerContext, REGISTRY);
			return resolver.get()
			               .addListener(future -> {
			                   handlerContext.status = future.isSuccess() ? SUCCESS : ERROR;
			                   sample.stop();
			               });
		}
	}
}
