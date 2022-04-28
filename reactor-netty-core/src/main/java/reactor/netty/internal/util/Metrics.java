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
package reactor.netty.internal.util;

import static io.micrometer.core.instrument.Metrics.globalRegistry;

/**
 * Utility class around instrumentation and metrics with Micrometer.
 * <p><strong>Note:</strong> This utility class is for internal use only. It can be removed at any time.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
public class Metrics {

	static final boolean isMicrometerAvailable;

	static {
		boolean micrometer;
		try {
			globalRegistry.getRegistries();
			micrometer = true;
		}
		catch (Throwable t) {
			micrometer = false;
		}
		isMicrometerAvailable = micrometer;
	}

	/**
	 * Check if the current runtime supports metrics / instrumentation, by
	 * verifying if Micrometer is on the classpath.
	 *
	 * @return true if the Micrometer instrumentation facade is available
	 */
	public static boolean isInstrumentationAvailable() {
		return isMicrometerAvailable;
	}
}