/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.micrometer;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import org.assertj.core.api.AbstractAssert;
import reactor.util.annotation.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Assertion methods for {@link Timer}.
 * To create an instance of this class, invoke {@link TimerAssert#assertTimer(MeterRegistry, String, String...)}
 *
 * @author Violeta Georgieva
 * @since 1.0.43
 */
public final class TimerAssert extends AbstractAssert<TimerAssert, Timer> {

	public static TimerAssert assertTimer(MeterRegistry registry, String name, String... tags) {
		return new TimerAssert(registry.find(name).tags(tags).timer(), TimerAssert.class);
	}

	public TimerAssert hasCountEqualTo(long expected) {
		isNotNull();
		long count = actual.count();
		if (count != expected) {
			failWithMessage("%nExpecting count:%n  %s%nto be equal to:%n  %s", count, expected);
		}
		return this;
	}

	public TimerAssert hasTotalTimeGreaterThan(double expected) {
		isNotNull();
		double totalTime = actual.totalTime(TimeUnit.NANOSECONDS);
		if (totalTime <= expected) {
			failWithMessage("%nExpecting total time:%n  %s%nto be greater than:%n  %s", totalTime, expected);
		}
		return this;
	}

	public TimerAssert hasTotalTimeGreaterThanOrEqualTo(double expected) {
		isNotNull();
		double totalTime = actual.totalTime(TimeUnit.NANOSECONDS);
		if (totalTime < expected) {
			failWithMessage("%nExpecting total time:%n  %s%nto be greater than or equal to:%n  %s", totalTime, expected);
		}
		return this;
	}

	private TimerAssert(@Nullable Timer timer, Class<?> selfType) {
		super(timer, selfType);
	}
}
