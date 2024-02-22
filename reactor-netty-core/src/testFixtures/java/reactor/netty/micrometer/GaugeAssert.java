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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.assertj.core.api.AbstractAssert;
import reactor.util.annotation.Nullable;

/**
 * Assertion methods for {@link Gauge}.
 * To create an instance of this class, invoke {@link GaugeAssert#assertGauge(MeterRegistry, String, String...)}
 *
 * @author Violeta Georgieva
 * @since 1.0.43
 */
public final class GaugeAssert extends AbstractAssert<GaugeAssert, Gauge> {

	public static GaugeAssert assertGauge(MeterRegistry registry, String name, String... tags) {
		return new GaugeAssert(registry.find(name).tags(tags).gauge(), GaugeAssert.class);
	}

	public GaugeAssert hasValueGreaterThan(double expected) {
		isNotNull();
		double value = actual.value();
		if (value <= expected) {
			failWithMessage("%nExpecting value:%n  %s%nto be greater than:%n  %s", value, expected);
		}
		return this;
	}

	public GaugeAssert hasValueEqualTo(double expected) {
		isNotNull();
		double value = actual.value();
		if (value != expected) {
			failWithMessage("%nExpecting value:%n  %s%nto be equal to:%n  %s", value, expected);
		}
		return this;
	}

	public GaugeAssert hasValueLessThan(double expected) {
		isNotNull();
		double value = actual.value();
		if (value >= expected) {
			failWithMessage("%nExpecting value:%n  %s%n to be less than:%n  %s", value, expected);
		}
		return this;
	}

	private GaugeAssert(@Nullable Gauge gauge, Class<?> selfType) {
		super(gauge, selfType);
	}
}
