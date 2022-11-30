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
package reactor.netty.contextpropagation;

import io.micrometer.context.ContextAccessor;
import io.netty.channel.Channel;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Map;
import java.util.function.Predicate;

import static reactor.netty.ReactorNetty.getChannelContext;
import static reactor.netty.ReactorNetty.setChannelContext;

/**
 * A {@code ContextAccessor} to enable reading values from a Netty {@link Channel} and
 * writing values to a Netty {@link Channel}.
 * <p><strong>Note:</strong> This public class implements the {@code io.micrometer:context-propagation} SPI library,
 * which is an optional dependency.
 *
 * @author Violeta Georgieva
 * @since 1.0.26
 */
public final class ChannelContextAccessor implements ContextAccessor<Channel, Channel> {

	@Override
	public Class<? extends Channel> readableType() {
		return Channel.class;
	}

	@Override
	public void readValues(Channel sourceContext, Predicate<Object> keyPredicate, Map<Object, Object> readValues) {
		ContextView contextView = getChannelContext(sourceContext);
		if (contextView != null) {
			contextView.forEach((k, v) -> {
				if (keyPredicate.test(k)) {
					readValues.put(k, v);
				}
			});
		}
	}

	@Override
	@Nullable
	@SuppressWarnings("TypeParameterUnusedInFormals")
	public <T> T readValue(Channel sourceContext, Object key) {
		ContextView contextView = getChannelContext(sourceContext);
		if (contextView != null) {
			return contextView.getOrDefault(key, null);
		}
		return null;
	}

	@Override
	public Class<? extends Channel> writeableType() {
		return Channel.class;
	}

	@Override
	public Channel writeValues(Map<Object, Object> valuesToWrite, Channel targetContext) {
		if (!valuesToWrite.isEmpty()) {
			ContextView contextView = getChannelContext(targetContext);
			setChannelContext(targetContext, contextView != null ?
							Context.of(contextView).putAllMap(valuesToWrite) : Context.of(valuesToWrite));
		}
		return targetContext;
	}
}
