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
   * Create a new {@link Context} which contains a {@link Span} element.
   * @param span a current span
   */
  public static Context create(@NotNull Span span) {
    return Context.of(KEY, span);
  }

  /**
   * Add a {@link Span} to this {@link Context}
   * @param span a current span
   */
  public Context put(@NonNull Span span) {
    return ctx.put(KEY, span);
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
