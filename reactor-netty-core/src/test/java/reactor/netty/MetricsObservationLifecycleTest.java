/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Metrics#observationLifecycleRequired()}, which gates the built-in recorders' fast path that
 * records the response-time timer directly instead of through a full {@link io.micrometer.observation.Observation}.
 * <p>Methods are ordered because customizing the default registry is a one-way, JVM-global change: the
 * "pristine default" assertion must run before it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetricsObservationLifecycleTest {

	@Test
	@Order(0)
	void trackedConfigOverridesEveryPublicObservationConfigMutator() {
		// TrackedObservationConfig detects customization only through the fluent mutators it overrides. If a
		// Micrometer upgrade adds a new one, this must fail until the matching override is added — not stay
		// green while the new mutator silently bypasses detection.
		Set<String> fluentMutators = Arrays.stream(ObservationRegistry.ObservationConfig.class.getMethods())
				.filter(m -> m.getReturnType() == ObservationRegistry.ObservationConfig.class)
				.map(Method::getName)
				.collect(Collectors.toSet());
		Set<String> trackedOverrides = Arrays.stream(Metrics.TrackedObservationConfig.class.getDeclaredMethods())
				.map(Method::getName)
				.collect(Collectors.toSet());
		assertThat(trackedOverrides).containsAll(fluentMutators);
	}

	@Test
	@Order(1)
	void everyMutatorMarksCustomizedOnceArmed() {
		List<Consumer<Metrics.TrackedObservationConfig>> mutators = Arrays.asList(
				config -> config.observationHandler(context -> true),
				config -> config.observationPredicate((name, context) -> true),
				config -> config.observationFilter(context -> context),
				config -> config.observationConvention(context -> true));

		for (Consumer<Metrics.TrackedObservationConfig> mutate : mutators) {
			Metrics.TrackedObservationConfig config = new Metrics.TrackedObservationConfig();
			mutate.accept(config);
			assertThat(config.customized).as("unarmed mutator call is not customization").isFalse();

			config.armed = true;
			mutate.accept(config);
			assertThat(config.customized).as("armed mutator call marks the config customized").isTrue();
		}
	}

	@Test
	@Order(2)
	void pristineDefaultRegistryDoesNotRequireObservationLifecycle() {
		assertThat(Metrics.OBSERVATION_REGISTRY).isSameAs(Metrics.DEFAULT_OBSERVATION_REGISTRY);
		assertThat(Metrics.observationLifecycleRequired()).isFalse();
	}

	@Test
	@Order(3)
	void swappedRegistryRequiresObservationLifecycle() {
		ObservationRegistry previous = Metrics.OBSERVATION_REGISTRY;
		try {
			Metrics.observationRegistry(ObservationRegistry.create());
			assertThat(Metrics.observationLifecycleRequired()).isTrue();
		}
		finally {
			Metrics.observationRegistry(previous);
		}
	}

	@Test
	@Order(4)
	void noopRegistryRequiresObservationLifecycle() {
		ObservationRegistry previous = Metrics.OBSERVATION_REGISTRY;
		try {
			Metrics.observationRegistry(ObservationRegistry.NOOP);
			assertThat(Metrics.observationLifecycleRequired()).isTrue();
		}
		finally {
			Metrics.observationRegistry(previous);
		}
	}

	@Test
	@Order(5)
	void customizedDefaultRegistryRequiresObservationLifecycle() {
		// Registering any handler on the default registry marks it customized for the rest of the JVM,
		// so the bypass disengages. This is intentionally the last test — it cannot be undone.
		Metrics.OBSERVATION_REGISTRY.observationConfig().observationHandler(context -> true);
		assertThat(Metrics.observationLifecycleRequired()).isTrue();
	}
}
