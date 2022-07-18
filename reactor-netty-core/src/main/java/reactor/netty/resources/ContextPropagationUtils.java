/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.function.Function;
import java.util.function.Predicate;

final class ContextPropagationUtils {

	static final Predicate<Object> OBSERVATION_KEY = k -> k == ObservationThreadLocalAccessor.KEY;

	static Function<Context, Context> captureObservation(ContextView source) {
		ContextSnapshot snapshot = ContextSnapshot.captureUsing(OBSERVATION_KEY, source);
		return snapshot::updateContext;
	}

	private ContextPropagationUtils() {}
}
