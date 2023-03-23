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
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import io.netty.handler.codec.http.HttpRequest;

public final class ReactorNettyDelegatingPropagatingReceiver implements TracingObservationHandler<ReceiverContext<HttpRequest>> {

	final ReactorNettyPropagatingReceiverTracingObservationHandler delegate;

	public ReactorNettyDelegatingPropagatingReceiver(Tracer tracer, Propagator propagator) {
		this.delegate = new ReactorNettyPropagatingReceiverTracingObservationHandler(tracer, propagator);
	}

	@Override
	public Span getParentSpan(Observation.ContextView context) {
		return delegate.getParentSpan(context);
	}

	@Override
	public Span getRequiredSpan(ReceiverContext<HttpRequest> context) {
		return delegate.getRequiredSpan(context);
	}

	@Override
	public String getSpanName(ReceiverContext<HttpRequest> context) {
		return delegate.getSpanName(context);
	}

	@Override
	public Tracer getTracer() {
		return delegate.getTracer();
	}

	@Override
	public TracingContext getTracingContext(ReceiverContext<HttpRequest> context) {
		return delegate.getTracingContext(context);
	}

	@Override
	public void onError(ReceiverContext<HttpRequest> context) {
		delegate.onError(context);
	}

	@Override
	public void onEvent(Observation.Event event, ReceiverContext<HttpRequest> context) {
		delegate.onEvent(event, context);
	}

	@Override
	public void onScopeClosed(ReceiverContext<HttpRequest> context) {
		delegate.onScopeClosed(context);
	}

	@Override
	public void onScopeOpened(ReceiverContext<HttpRequest> context) {
		delegate.onScopeOpened(context);
	}

	@Override
	public void onStart(ReceiverContext<HttpRequest> context) {
		delegate.onStart(context);
	}

	@Override
	public void onStop(ReceiverContext<HttpRequest> context) {
		delegate.onStop(context);
	}

	@Override
	public boolean supportsContext(Observation.Context context) {
		return delegate.supportsContext(context);
	}

	@Override
	public void tagSpan(ReceiverContext<HttpRequest> context, Span span) {
		delegate.tagSpan(context, span);
	}
}
