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
package reactor.netty.http.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.transport.http.HttpClientRequest;
import io.micrometer.observation.transport.http.context.HttpClientContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.HttpClientTracingObservationHandler;
import io.micrometer.tracing.http.HttpClientHandler;
import reactor.netty.observability.ReactorNettyHandlerContext;

import static reactor.netty.Metrics.REMOTE_ADDRESS;

/**
 * Reactor Netty specific {@link HttpClientTracingObservationHandler}
 *
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
public final class ReactorNettyHttpClientTracingObservationHandler extends HttpClientTracingObservationHandler {

	/**
	 * Creates a new instance of {@link HttpClientTracingObservationHandler}.
	 *
	 * @param tracer  tracer
	 * @param handler http client handler
	 */
	public ReactorNettyHttpClientTracingObservationHandler(Tracer tracer, HttpClientHandler handler) {
		super(tracer, handler);
	}

	@Override
	public void tagSpan(HttpClientContext context, Span span) {
		for (KeyValue tag : context.getHighCardinalityKeyValues()) {
			span.tag(tag.getKey(), tag.getValue());
		}
		for (KeyValue tag : context.getLowCardinalityKeyValues()) {
			if (tag.getKey().equals(REMOTE_ADDRESS)) {
				span.tag(tag.getKey(), tag.getValue());
				break;
			}
		}
	}

	@Override
	public boolean supportsContext(Observation.Context context) {
		return context instanceof ReactorNettyHandlerContext &&
				super.supportsContext(context) &&
				context.get(HttpClientRequest.class) != null;
	}
}
