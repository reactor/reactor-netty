/*
 * Copyright (c) 2022-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.internal.util.MapUtils;
import reactor.netty.observability.ReactorNettyHandlerContext;
import reactor.util.context.ContextView;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import static reactor.netty.Metrics.ADDRESS_RESOLVER;
import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.OBSERVATION_KEY;
import static reactor.netty.Metrics.OBSERVATION_REGISTRY;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.Metrics.UNKNOWN;
import static reactor.netty.transport.HostnameResolutionObservations.HostnameResolutionTimeHighCardinalityTags.NET_PEER_NAME;
import static reactor.netty.transport.HostnameResolutionObservations.HostnameResolutionTimeHighCardinalityTags.NET_PEER_PORT;
import static reactor.netty.transport.HostnameResolutionObservations.HostnameResolutionTimeHighCardinalityTags.REACTOR_NETTY_PROTOCOL;
import static reactor.netty.transport.HostnameResolutionObservations.HostnameResolutionTimeHighCardinalityTags.REACTOR_NETTY_STATUS;
import static reactor.netty.transport.HostnameResolutionObservations.HostnameResolutionTimeHighCardinalityTags.REACTOR_NETTY_TYPE;
import static reactor.netty.transport.HostnameResolutionObservations.HostnameResolutionTimeLowCardinalityTags.REMOTE_ADDRESS;
import static reactor.netty.transport.HostnameResolutionObservations.HostnameResolutionTimeLowCardinalityTags.STATUS;

/**
 * Metrics related to name resolution.
 *
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
final class MicrometerAddressResolverGroupMetrics<T extends SocketAddress> extends AddressResolverGroupMetrics<T> {

	static final ConcurrentMap<Integer, MicrometerAddressResolverGroupMetrics<?>> cache = new ConcurrentHashMap<>();

	static MicrometerAddressResolverGroupMetrics<?> getOrCreate(
			AddressResolverGroup<?> resolverGroup, MicrometerChannelMetricsRecorder recorder) {
		return MapUtils.computeIfAbsent(cache, Objects.hash(resolverGroup, recorder),
				key -> new MicrometerAddressResolverGroupMetrics<>(resolverGroup, recorder));
	}

	MicrometerAddressResolverGroupMetrics(AddressResolverGroup<T> resolverGroup, MicrometerChannelMetricsRecorder recorder) {
		super(resolverGroup, recorder);
	}

	@Override
	protected AddressResolver<T> newResolver(EventExecutor executor) {
		return new MicrometerDelegatingAddressResolver<>((MicrometerChannelMetricsRecorder) recorder, resolverGroup.getResolver(executor));
	}

	static final class FutureHandlerContext extends Observation.Context
			implements ReactorNettyHandlerContext, Supplier<Observation.Context> {
		static final String CONTEXTUAL_NAME = "hostname resolution";
		static final String TYPE = "client";

		final String netPeerName;
		final String netPeerPort;
		final MicrometerChannelMetricsRecorder recorder;

		// status is not known beforehand
		String status = UNKNOWN;

		FutureHandlerContext(MicrometerChannelMetricsRecorder recorder, SocketAddress remoteAddress) {
			this.recorder = recorder;
			if (remoteAddress instanceof InetSocketAddress) {
				InetSocketAddress address = (InetSocketAddress) remoteAddress;
				this.netPeerName = address.getHostString();
				this.netPeerPort = address.getPort() + "";
			}
			else {
				this.netPeerName = remoteAddress.toString();
				this.netPeerPort = "";
			}
		}

		@Override
		public Observation.Context get() {
			return this;
		}

		@Override
		public Timer getTimer() {
			return recorder.getResolveAddressTimer(getName(), netPeerName + ':' + netPeerPort, status);
		}

		@Override
		public String getContextualName() {
			return CONTEXTUAL_NAME;
		}

		@Override
		public KeyValues getHighCardinalityKeyValues() {
			return KeyValues.of(NET_PEER_NAME.asString(), netPeerName, NET_PEER_PORT.asString(), netPeerPort,
					REACTOR_NETTY_PROTOCOL.asString(), recorder.protocol(),
					REACTOR_NETTY_STATUS.asString(), status, REACTOR_NETTY_TYPE.asString(), TYPE);
		}

		@Override
		public KeyValues getLowCardinalityKeyValues() {
			return KeyValues.of(REMOTE_ADDRESS.asString(), netPeerName + ':' + netPeerPort, STATUS.asString(), status);
		}
	}

	static final class MicrometerDelegatingAddressResolver<T extends SocketAddress> extends DelegatingAddressResolver<T> {

		final String name;

		MicrometerDelegatingAddressResolver(MicrometerChannelMetricsRecorder recorder, AddressResolver<T> resolver) {
			super(recorder, resolver);
			this.name = recorder.name();
		}

		Future<List<T>> resolveAll(SocketAddress address, ContextView contextView) {
			return resolveAllInternal(address, () -> resolver.resolveAll(address), contextView);
		}

		Future<List<T>> resolveAllInternal(SocketAddress address, Supplier<Future<List<T>>> resolver, ContextView contextView) {
			// Cannot invoke the recorder anymore:
			// 1. The recorder is one instance only, it is invoked for all name resolutions that can happen
			// 2. The recorder does not have knowledge about name resolution lifecycle
			// 3. There is no connection, so we cannot hold the context information in the Channel
			//
			// Move the implementation from the recorder here
			FutureHandlerContext handlerContext = new FutureHandlerContext((MicrometerChannelMetricsRecorder) recorder, address);
			Observation sample = Observation.createNotStarted(name + ADDRESS_RESOLVER, handlerContext, OBSERVATION_REGISTRY);
			if (contextView.hasKey(OBSERVATION_KEY)) {
				sample.parentObservation(contextView.get(OBSERVATION_KEY));
			}
			sample.start();
			return resolver.get()
			               .addListener(future -> {
			                   handlerContext.status = future.isSuccess() ? SUCCESS : ERROR;
			                   sample.stop();
			               });
		}

		@Override
		Future<T> resolveInternal(SocketAddress address, Supplier<Future<T>> resolver) {
			// Cannot invoke the recorder anymore:
			// 1. The recorder is one instance only, it is invoked for all name resolutions that can happen
			// 2. The recorder does not have knowledge about name resolution lifecycle
			// 3. There is no connection, so we cannot hold the context information in the Channel
			//
			// Move the implementation from the recorder here
			FutureHandlerContext handlerContext = new FutureHandlerContext((MicrometerChannelMetricsRecorder) recorder, address);
			Observation observation = Observation.start(name + ADDRESS_RESOLVER, handlerContext, OBSERVATION_REGISTRY);
			return resolver.get()
			               .addListener(future -> {
			                   handlerContext.status = future.isSuccess() ? SUCCESS : ERROR;
			                   observation.stop();
			               });
		}
	}
}
