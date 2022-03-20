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
package reactor.netty.observability.contextpropagation.propagator;

import io.micrometer.contextpropagation.ContextContainer;
import io.micrometer.contextpropagation.ContextContainerPropagator;
import io.netty5.channel.Channel;
import io.netty5.util.AttributeKey;

/**
 * Abstraction to tell context propagation how to read from and write to {@link Channel} attributes.
 *
 * @author Marcin Grzejszczak
 * @since 1.1.0
 */
public final class ChannelContextContainerPropagator implements ContextContainerPropagator<Channel, Channel> {

	static final AttributeKey<ContextContainer> CONTEXT_PROPAGATION_ATTR = AttributeKey.valueOf("$CONTEXT_PROPAGATION");

	@Override
	public ContextContainer get(Channel channel) {
		ContextContainer container = channel.attr(CONTEXT_PROPAGATION_ATTR).get();
		return container != null ? container : ContextContainer.NOOP;
	}

	@Override
	public Class<?> getSupportedContextClassForGet() {
		return Channel.class;
	}

	@Override
	public Class<?> getSupportedContextClassForSet() {
		return Channel.class;
	}

	@Override
	public Channel remove(Channel channel) {
		channel.attr(CONTEXT_PROPAGATION_ATTR).set(null);
		return channel;
	}

	@Override
	public Channel set(Channel channel, ContextContainer value) {
		channel.attr(CONTEXT_PROPAGATION_ATTR).compareAndSet(null, value);
		return channel;
	}
}
