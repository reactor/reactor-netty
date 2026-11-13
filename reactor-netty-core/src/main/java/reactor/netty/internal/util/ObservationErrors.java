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
import org.jspecify.annotations.Nullable;
import reactor.core.Exceptions;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.function.BiFunction;

/**
 * Utilities for configuring and recording observation errors.
 * <p><strong>Note:</strong> This utility class is for internal use only. It can be removed at any time.
 *
 * @author Violeta Georgieva
 * @since 1.4.0
 */
public final class ObservationErrors {

	/**
	 * Set the function used to map observation errors.
	 *
	 * @param observationErrorMapper the function or {@code null} to not record errors
	 * @return the previously configured function or {@code null}
	 */
	public static @Nullable BiFunction<Observation.Context, Throwable, @Nullable Throwable> observationErrorMapper(
			@Nullable BiFunction<Observation.Context, Throwable, @Nullable Throwable> observationErrorMapper) {
		BiFunction<Observation.Context, Throwable, @Nullable Throwable> previous = OBSERVATION_ERROR_MAPPER;
		OBSERVATION_ERROR_MAPPER = observationErrorMapper;
		return previous;
	}

	/**
	 * Record the failure cause when the configured function returns an error to record.
	 *
	 * @param observation the observation
	 * @param cause the failure cause
	 */
	public static void recordObservationError(Observation observation, Throwable cause) {
		BiFunction<Observation.Context, Throwable, @Nullable Throwable> mapper = OBSERVATION_ERROR_MAPPER;
		if (mapper == null || observation.isNoop()) {
			return;
		}
		try {
			Throwable error = mapper.apply(observation.getContext(), cause);
			if (error != null) {
				observation.error(error);
			}
		}
		catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			log.debug("Failed to record the error for observation [" + observation.getContext().getName() + "]", t);
		}
	}

	private ObservationErrors() {
	}

	static final Logger log = Loggers.getLogger(ObservationErrors.class);

	static volatile @Nullable BiFunction<Observation.Context, Throwable, @Nullable Throwable> OBSERVATION_ERROR_MAPPER;
}
