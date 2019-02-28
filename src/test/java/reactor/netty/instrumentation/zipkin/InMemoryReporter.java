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

import java.util.ArrayList;
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
  private final List<Span> report = new ArrayList<>();

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
