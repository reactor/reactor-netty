/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.tracing.context.HttpClientHandlerContext;
import io.micrometer.core.instrument.transport.http.HttpClientRequest;
import io.micrometer.core.instrument.transport.http.HttpClientResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.HttpClientTracingRecordingHandler;
import io.micrometer.tracing.http.HttpClientHandler;

public class ReactorNettyHttpClientTracingRecordingHandler extends HttpClientTracingRecordingHandler {

	/**
	 * Creates a new instance of {@link HttpClientTracingRecordingHandler}.
	 *
	 * @param tracer  tracer
	 * @param handler http client handler
	 */
	public ReactorNettyHttpClientTracingRecordingHandler(Tracer tracer, HttpClientHandler handler) {
		super(tracer, handler);
	}

	@Override
	public void tagSpan(HttpClientHandlerContext context, Meter.Id id, Span span) {
		ReactorNettyHttpClientTags.tagSpan(context, span);
	}

	@Override
	public boolean supportsContext(Timer.HandlerContext context) {
		return context instanceof ReactorNettyHandlerContext && super.supportsContext(context) && context.get(HttpClientRequest.class) != null && context.get(HttpClientResponse.class) != null;
	}

}
