/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.server;

import brave.Span;
import reactor.util.context.ContextView;

import java.util.Optional;
import java.util.function.Consumer;

import static reactor.netty.http.server.BraveHttpServerTracing.KEY_TRACING_CONTEXT;

/**
 * {@link TracingContext} provides the current span utilizing the reactive {@link ContextView}.
 *
 * {@code
 *     Mono.deferContextual(Mono::just)
 *         .flatMap(ctx -> TracingContext.of(ctx)
 *                                       .ifPresent(tracing -> tracing.span(span -> ...)));
 * }
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public interface TracingContext {

	/**
	 * Returns the current {@link TracingContext} if any.
	 *
	 * @param ctx the current {@link ContextView}
	 * @return the current {@link TracingContext} if any
	 */
	static Optional<TracingContext> of(ContextView ctx) {
		return ctx.getOrEmpty(KEY_TRACING_CONTEXT);
	}

	/**
	 * A {@link Span} consumer.
	 *
	 * @param consumer a {@link Span} consumer
	 * @return this
	 */
	TracingContext span(Consumer<Span> consumer);
}
