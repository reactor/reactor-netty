/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Function;

/**
 * Utility class used to apply/map some custom transformations on a given LoopResources instance
 * for onClient/onServer/onServerSelect callbacks.
 */
final class MapLoopResources implements LoopResources {
	final LoopResources parent;
	final Function<EventLoopGroup, EventLoopGroup> mapOnClient;
	final Function<EventLoopGroup, EventLoopGroup> mapOnServer;
	final Function<EventLoopGroup, EventLoopGroup> mapOnServerSelect;

	final static Function<EventLoopGroup, EventLoopGroup> UNCOLOCATE = (evg) -> {
		return evg instanceof ColocatedEventLoopGroup ? ((ColocatedEventLoopGroup) evg).get() : evg;
	};

	MapLoopResources(LoopResources parent, Function<EventLoopGroup, EventLoopGroup> mapOnClient) {
		this(parent, mapOnClient, Function.identity(), Function.identity());
	}

	MapLoopResources(LoopResources parent,
	                 Function<EventLoopGroup, EventLoopGroup> mapOnClient,
	                 Function<EventLoopGroup, EventLoopGroup> mapOnServer,
	                 Function<EventLoopGroup, EventLoopGroup> mapOnServerSelect) {
		this.parent = parent;
		this.mapOnClient = mapOnClient;
		this.mapOnServer = mapOnServer;
		this.mapOnServerSelect = mapOnServerSelect;
	}

	@Override
	public boolean daemon() {
		return parent.daemon();
	}

	@Override
	public void dispose() {
		parent.dispose();
	}

	@Override
	public boolean isDisposed() {
		return parent.isDisposed();
	}

	@Override
	public Mono<Void> disposeLater() {
		return parent.disposeLater();
	}

	@Override
	public Mono<Void> disposeLater(Duration quietPeriod, Duration timeout) {
		return parent.disposeLater(quietPeriod, timeout);
	}

	@Override
	public <CHANNEL extends Channel> CHANNEL onChannel(Class<CHANNEL> channelType, EventLoopGroup group) {
		return parent.onChannel(channelType, group);
	}

	@Override
	public <CHANNEL extends Channel> Class<? extends CHANNEL> onChannelClass(Class<CHANNEL> channelType, EventLoopGroup group) {
		return parent.onChannelClass(channelType, group);
	}

	@Override
	public EventLoopGroup onClient(boolean useNative) {
		return mapOnClient.apply(parent.onClient(useNative));
	}

	@Override
	public EventLoopGroup onServer(boolean useNative) {
		return mapOnServer.apply(parent.onServer(useNative));
	}

	@Override
	public EventLoopGroup onServerSelect(boolean useNative) {
		return mapOnServerSelect.apply(parent.onServerSelect(useNative));
	}
}
