/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import reactor.core.publisher.Mono;

/**
 * @author Simon Baslé
 */
public class CompositeNettyContext implements NettyContext {

	List<? extends NettyContext> delegates;

	public CompositeNettyContext(List<? extends NettyContext> delegates) {
		this.delegates = delegates;
	}

	@Override
	public NettyContext addHandler(ChannelHandler handler) {
		delegates.forEach(c -> c.addHandler(handler));
		return this;
	}

	@Override
	public NettyContext addHandler(String name, ChannelHandler handler) {
		delegates.forEach(c -> c.addHandler(name, handler));
		return this;
	}

	@Override
	public NettyContext addHandlerLast(ChannelHandler handler) {
		delegates.forEach(c -> c.addHandlerLast(handler));
		return this;
	}

	@Override
	public NettyContext addHandlerLast(String name, ChannelHandler handler) {
		delegates.forEach(c -> c.addHandlerLast(name, handler));
		return this;
	}

	@Override
	public NettyContext addHandlerFirst(ChannelHandler handler) {
		delegates.forEach(c -> c.addHandlerFirst(handler));
		return this;
	}

	@Override
	public NettyContext addHandlerFirst(String name, ChannelHandler handler) {
		delegates.forEach(c -> c.addHandlerFirst(name, handler));
		return this;
	}

	@Override
	public InetSocketAddress address() {
		return delegates.stream().findFirst().map(NettyContext::address).orElse(null);
	}

	@Override
	public Channel channel() {
		return delegates.stream().findFirst().map(NettyContext::channel).orElse(null);
	}

	@Override
	public void dispose() {
		delegates.forEach(NettyContext::dispose);
	}

	@Override
	public boolean isDisposed() {
		return delegates.stream().allMatch(NettyContext::isDisposed);
	}

	@Override
	public NettyContext markPersistent(boolean persist) {
		delegates.forEach(c -> c.markPersistent(persist));
		return this;
	}

	@Override
	public Mono<Void> onClose() {
		return Mono.when(
				delegates.stream()
				         .map(NettyContext::onClose)
				         .collect(Collectors.toList()));
	}

	@Override
	public NettyContext onClose(Runnable onClose) {
		onClose().subscribe(null, e -> onClose.run(), onClose);
		return this;
	}

	@Override
	public NettyContext removeHandler(String name) {
		delegates.forEach(c -> c.removeHandler(name));
		return this;
	}

	@Override
	public NettyContext replaceHandler(String name, ChannelHandler handler) {
		delegates.forEach(c -> c.replaceHandler(name, handler));
		return this;
	}
}
