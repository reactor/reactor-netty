package reactor.netty.instrumentation.zipkin;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import zipkin2.Span;
import zipkin2.reporter.Reporter;

class InMemoryReporter implements Reporter<Span> {
  private final AtomicBoolean once = new AtomicBoolean(false);
  private final CountDownLatch latch = new CountDownLatch(1);
  private final List<Span> report = new LinkedList<>();

  @Override
  public void report(Span span) {
    report.add(span);

    System.err.println(span);

    if (once.compareAndSet(false, true)) {
      latch.countDown();
    }
  }

  public Span span(int index) {
    Span span = report.get(index);
    return Objects.requireNonNull(span, "span [" + index + "] cannot be null");
  }

  public InMemoryReporter await() {
    try {
      latch.await(10, TimeUnit.SECONDS);
      return this;
    } catch (InterruptedException err) {
      throw new RuntimeException(err);
    }
  }
}
