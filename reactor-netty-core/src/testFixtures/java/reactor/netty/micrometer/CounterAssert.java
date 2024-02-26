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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.assertj.core.api.AbstractAssert;
import reactor.util.annotation.Nullable;

/**
 * Assertion methods for {@link Counter}.
 * To create an instance of this class, invoke {@link CounterAssert#assertCounter(MeterRegistry, String, String...)}
 *
 * @author Violeta Georgieva
 * @since 1.0.43
 */
public final class CounterAssert extends AbstractAssert<CounterAssert, Counter> {

	public static CounterAssert assertCounter(MeterRegistry registry, String name, String... tags) {
		return new CounterAssert(registry.find(name).tags(tags).counter(), CounterAssert.class);
	}

	public CounterAssert hasCountGreaterThanOrEqualTo(double expected) {
		isNotNull();
		double count = actual.count();
		if (count < expected) {
			failWithMessage("%nExpecting count:%n  %s%nto be greater than or equal to:%n  %s", count, expected);
		}
		return this;
	}

	private CounterAssert(@Nullable Counter counter, Class<?> selfType) {
		super(counter, selfType);
	}
}
