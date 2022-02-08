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
package reactor.netty.observability;

import io.micrometer.api.instrument.Tag;
import io.micrometer.api.instrument.observation.Observation;
import io.micrometer.tracing.Span;
import reactor.netty.Metrics;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;

/**
 * Utility methods for observability.
 *
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
public final class ReactorNettyObservabilityUtils {

	private static final List<String> LEGACY_TAGS = Arrays.asList(Metrics.STATUS, Metrics.METHOD, Metrics.URI);

	/**
	 * Obtains the high cardinality tags from the context and sets them on the span.
	 * Obtains the host and port information and sets it on the span.
	 *
	 * @param context the {@link Observation.Context}
	 * @param span    the {@link Span}
	 */
	public static void tagClientSpan(Observation.Context context, Span span) {
		SocketAddress address = context.get(SocketAddress.class);
		if (address instanceof InetSocketAddress) {
			InetSocketAddress inet = (InetSocketAddress) address;
			span.remoteIpAndPort(inet.getHostString(), inet.getPort());
		}
		for (Tag tag : context.getHighCardinalityTags()) {
			span.tag(tag.getKey(), tag.getValue());
		}
	}

	/**
	 * Obtains the all cardinality tags from the context except for the legacy ones and sets them on the span.
	 * Obtains the host and port information and sets it on the span.
	 *
	 * @param context the {@link Observation.Context}
	 * @param span    the {@link Span}
	 */
	public static void tagSpan(Observation.Context context, Span span) {
		for (Tag tag : context.getHighCardinalityTags()) {
			span.tag(tag.getKey(), tag.getValue());
		}
		for (Tag tag : context.getLowCardinalityTags()) {
			if (LEGACY_TAGS.contains(tag.getKey())) {
				continue;
			}
			span.tag(tag.getKey(), tag.getValue());
		}
	}

	private ReactorNettyObservabilityUtils() {}
}
