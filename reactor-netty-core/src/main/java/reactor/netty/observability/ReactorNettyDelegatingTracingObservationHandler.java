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
package reactor.netty.observability;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;

public final class ReactorNettyDelegatingTracingObservationHandler implements TracingObservationHandler<Observation.Context> {

	final ReactorNettyTracingObservationHandler delegate;

	public ReactorNettyDelegatingTracingObservationHandler(Tracer tracer) {
		this.delegate = new ReactorNettyTracingObservationHandler(tracer);
	}

	@Override
	public Span getParentSpan(Observation.ContextView context) {
		return delegate.getParentSpan(context);
	}

	@Override
	public Span getRequiredSpan(Observation.Context context) {
		return delegate.getRequiredSpan(context);
	}

	@Override
	public String getSpanName(Observation.Context context) {
		return delegate.getSpanName(context);
	}

	@Override
	public Tracer getTracer() {
		return delegate.getTracer();
	}

	@Override
	public TracingContext getTracingContext(Observation.Context context) {
		return delegate.getTracingContext(context);
	}

	@Override
	public void onError(Observation.Context context) {
		delegate.onError(context);
	}

	@Override
	public void onEvent(Observation.Event event, Observation.Context context) {
		delegate.onEvent(event, context);
	}

	@Override
	public void onScopeClosed(Observation.Context context) {
		delegate.onScopeClosed(context);
	}

	@Override
	public void onScopeOpened(Observation.Context context) {
		delegate.onScopeOpened(context);
	}

	@Override
	public void onStart(Observation.Context context) {
		delegate.onStart(context);
	}

	@Override
	public void onStop(Observation.Context context) {
		delegate.onStop(context);
	}

	@Override
	public boolean supportsContext(Observation.Context handlerContext) {
		return delegate.supportsContext(handlerContext);
	}

	@Override
	public void tagSpan(Observation.Context context, Span span) {
		delegate.tagSpan(context, span);
	}
}
