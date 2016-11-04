/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.util;

import java.util.Objects;

import reactor.core.Cancellation;
import reactor.core.Exceptions;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
public final class CompositeCancellation implements Cancellation {

	static final Logger       log  = Loggers.getLogger(CompositeCancellation.class);
	static final Cancellation NOOP = () -> {
	};

	public static Cancellation from(Cancellation... cancellations) {
		Objects.requireNonNull(cancellations, "cancellations");
		if (cancellations.length == 0) {
			return NOOP;
		}
		else if (cancellations.length == 1) {
			return cancellations[0];
		}
		return new CompositeCancellation(cancellations);
	}

	final Cancellation[] cancellations;

	CompositeCancellation(Cancellation[] cancellations) {
		this.cancellations = cancellations;
	}

	@Override
	public void dispose() {
		for (Cancellation c : cancellations) {
			try {
				if(c != null) {
					c.dispose();
				}
			}
			catch (Throwable t) {
				Exceptions.throwIfFatal(t);
				log.error("Error while disposing " + c.toString(), t);
			}
		}
	}
}
