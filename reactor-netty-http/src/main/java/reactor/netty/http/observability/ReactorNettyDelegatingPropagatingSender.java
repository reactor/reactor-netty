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
package reactor.netty.http.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.transport.SenderContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import io.netty.handler.codec.http.HttpRequest;

public final class ReactorNettyDelegatingPropagatingSender implements TracingObservationHandler<SenderContext<HttpRequest>> {

	final ReactorNettyPropagatingSenderTracingObservationHandler delegate;

	public ReactorNettyDelegatingPropagatingSender(Tracer tracer, Propagator propagator) {
		this.delegate = new ReactorNettyPropagatingSenderTracingObservationHandler(tracer, propagator);
	}

	@Override
	public Span getParentSpan(Observation.ContextView context) {
		return delegate.getParentSpan(context);
	}

	@Override
	public Span getRequiredSpan(SenderContext<HttpRequest> context) {
		return delegate.getRequiredSpan(context);
	}

	@Override
	public String getSpanName(SenderContext<HttpRequest> context) {
		return delegate.getSpanName(context);
	}

	@Override
	public Tracer getTracer() {
		return delegate.getTracer();
	}

	@Override
	public TracingContext getTracingContext(SenderContext<HttpRequest> context) {
		return delegate.getTracingContext(context);
	}

	@Override
	public void onError(SenderContext<HttpRequest> context) {
		delegate.onError(context);
	}

	@Override
	public void onEvent(Observation.Event event, SenderContext<HttpRequest> context) {
		delegate.onEvent(event, context);
	}

	@Override
	public void onScopeClosed(SenderContext<HttpRequest> context) {
		delegate.onScopeClosed(context);
	}

	@Override
	public void onScopeOpened(SenderContext<HttpRequest> context) {
		delegate.onScopeOpened(context);
	}

	@Override
	public void onStart(SenderContext<HttpRequest> context) {
		delegate.onStart(context);
	}

	@Override
	public void onStop(SenderContext<HttpRequest> context) {
		delegate.onStop(context);
	}

	@Override
	public boolean supportsContext(Observation.Context context) {
		return delegate.supportsContext(context);
	}

	@Override
	public void tagSpan(SenderContext<HttpRequest> context, Span span) {
		delegate.tagSpan(context, span);
	}
}
