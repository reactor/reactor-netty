/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty.instrumentation.zipkin;

import brave.Span;
import com.sun.istack.internal.NotNull;
import java.util.function.Consumer;
import reactor.util.annotation.NonNull;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

/**
 * Span related methods for {@link Context}.
 */
public class TracingContext {
  private static final String KEY = Span.class.toString();

  private final Context ctx;

  private TracingContext(@NonNull Context ctx) {
    this.ctx = ctx;
  }

  /**
   * Return the current context.
   */
  public Context ctx() {
    return ctx;
  }

  /**
   * Create a new {@link Context} which contains a {@link Span} element.
   * @param span a current span
   */
  public static Context create(@NotNull Span span) {
    return Context.of(KEY, span);
  }

  /**
   * Add a {@link Span} to this {@link Context}
   * @param span a current span
   * @return a new tracing context with a {@link Span} element
   */
  public TracingContext put(@NonNull Span span) {
    return new TracingContext(ctx.put(KEY, span));
  }

  /**
   * Consume a {@link Span} element if it exists in the current context
   * @param consumer a span consumer
   * @return this context
   */
  public TracingContext span(@NonNull Consumer<Span> consumer) {
    Span span = span();
    if (span != null) {
      consumer.accept(span);
    }

    return this;
  }

  /**
   * @return a current {@link Span} element or null
   */
  public @Nullable Span span() {
    return ctx.getOrDefault(KEY, null);
  }

  /**
   * Decorate a given {@link Context}.
   * @param ctx a current context
   */
  public static TracingContext of(@NonNull Context ctx) {
    return new TracingContext(ctx);
  }
}
