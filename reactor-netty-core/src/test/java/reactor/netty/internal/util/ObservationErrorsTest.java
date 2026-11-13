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
package reactor.netty.internal.util;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static reactor.netty.Metrics.observationErrorMapper;
import static reactor.netty.internal.util.ObservationErrors.recordObservationError;

class ObservationErrorsTest {

	@AfterEach
	void resetMapper() {
		observationErrorMapper(null);
	}

	@ParameterizedTest
	@MethodSource("fatalErrors")
	void fatalMapperErrorsPropagate(Error error) {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(context -> true);
		Observation observation = Observation.start("test", registry);
		observationErrorMapper((context, cause) -> {
			throw error;
		});

		assertThatThrownBy(() -> recordObservationError(observation, new IllegalStateException("failure")))
				.isSameAs(error);
		assertThat(observation.getContext().getError()).isNull();
	}

	@ParameterizedTest
	@MethodSource("fatalErrors")
	void fatalObservationHandlerErrorsPropagate(Error error) {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
			@Override
			public void onError(Observation.Context context) {
				throw error;
			}

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}
		});
		Observation observation = Observation.start("test", registry);
		observationErrorMapper((context, cause) -> cause);

		Throwable cause = new IllegalStateException("failure");
		assertThatThrownBy(() -> recordObservationError(observation, cause)).isSameAs(error);
		assertThat(observation.getContext().getError()).isSameAs(cause);
	}

	static Stream<Error> fatalErrors() {
		return Stream.of(new OutOfMemoryError("fatal failure"), new ThreadDeath(), new LinkageError("fatal failure"));
	}
}
