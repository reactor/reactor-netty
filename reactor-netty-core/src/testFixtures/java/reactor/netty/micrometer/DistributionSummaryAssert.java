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

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.assertj.core.api.AbstractAssert;
import reactor.util.annotation.Nullable;

/**
 * Assertion methods for {@link DistributionSummary}.
 * To create an instance of this class, invoke {@link DistributionSummaryAssert#assertDistributionSummary(MeterRegistry, String, String...)}
 *
 * @author Violeta Georgieva
 * @since 1.0.43
 */
public final class DistributionSummaryAssert extends AbstractAssert<DistributionSummaryAssert, DistributionSummary> {

	public static DistributionSummaryAssert assertDistributionSummary(MeterRegistry registry, String name, String... tags) {
		return new DistributionSummaryAssert(registry.find(name).tags(tags).summary(), DistributionSummaryAssert.class);
	}

	public DistributionSummaryAssert hasCountGreaterThanOrEqualTo(long expected) {
		isNotNull();
		long count = actual.count();
		if (count < expected) {
			failWithMessage("%nExpecting count:%n  %s%nto be greater than or equal to:%n  %s", count, expected);
		}
		return this;
	}

	public DistributionSummaryAssert hasCountEqualTo(long expected) {
		isNotNull();
		long count = actual.count();
		if (count != expected) {
			failWithMessage("%nExpecting count:%n  %s%nto be equal to:%n  %s", count, expected);
		}
		return this;
	}

	public DistributionSummaryAssert hasTotalAmountGreaterThanOrEqualTo(double expected) {
		isNotNull();
		double totalAmount = actual.totalAmount();
		if (totalAmount < expected) {
			failWithMessage("%nExpecting total amount:%n  %s%nto be greater than or equal to:%n  %s", totalAmount, expected);
		}
		return this;
	}

	private DistributionSummaryAssert(@Nullable DistributionSummary distributionSummary, Class<?> selfType) {
		super(distributionSummary, selfType);
	}
}
