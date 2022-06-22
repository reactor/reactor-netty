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
package reactor.netty.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.MeterObservationHandler;
import io.micrometer.observation.Observation;

/**
 * Reactor Netty timer observation handler.
 * This timer observation handler is used to reduce memory overhead when creating Timer object instances.
 *
 * @author Pierre De Rop
 * @author Violeta Georgieva
 * @since 1.1.0
 */
public final class ReactorNettyTimerObservationHandler implements MeterObservationHandler<Observation.Context> {
	final MeterRegistry meterRegistry;

	public ReactorNettyTimerObservationHandler(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	@Override
	public void onStart(Observation.Context context) {
		Timer.Sample sample = Timer.start(meterRegistry);
		context.put(Timer.Sample.class, sample);
	}

	@Override
	public void onStop(Observation.Context context) {
		ReactorNettyHandlerContext reactorNettyContext = (ReactorNettyHandlerContext) context;
		Timer timer = reactorNettyContext.getTimer();
		if (timer != null) {
			Timer.Sample sample = context.getRequired(Timer.Sample.class);
			sample.stop(timer);
		}
	}

	@Override
	public boolean supportsContext(Observation.Context context) {
		return context instanceof ReactorNettyHandlerContext;
	}

}

