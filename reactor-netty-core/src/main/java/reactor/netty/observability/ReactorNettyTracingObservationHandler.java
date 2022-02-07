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

import io.micrometer.api.instrument.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;

/**
 * Abstraction over all Reactor Netty handlers.
 *
 * @author Marcin Grzejszczak
 * @since 1.1.0
 */
public final class ReactorNettyTracingObservationHandler extends DefaultTracingObservationHandler {

	public ReactorNettyTracingObservationHandler(Tracer tracer) {
		super(tracer);
	}

	@Override
	public void tagSpan(Observation.Context context, Span span) {
		ReactorNettyObservabilityUtils.tagSpan(context, span);
	}

	@Override
	@SuppressWarnings("unchecked")
	public String getSpanName(Observation.Context context) {
		String name = context.getContextualName();
		if (name != null) {
			return name;
		}
		return super.getSpanName(context);
	}

	@Override
	public boolean supportsContext(Observation.Context handlerContext) {
		return handlerContext instanceof ReactorNettyHandlerContext;
	}
}
