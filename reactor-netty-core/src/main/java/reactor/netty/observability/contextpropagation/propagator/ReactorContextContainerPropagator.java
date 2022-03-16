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
package reactor.netty.observability.contextpropagation.propagator;

import io.micrometer.contextpropagation.ContextContainer;
import io.micrometer.contextpropagation.ContextContainerPropagator;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Abstraction to tell context propagation how to read from and write to {@link Context}.
 *
 * @author Marcin Grzejszczak
 * @since 1.1.0
 */
public class ReactorContextContainerPropagator implements ContextContainerPropagator<ContextView, Context> {

	static final String CONTEXT_PROPAGATION_KEY = ContextContainer.class.getName();

	@Override
	public ContextContainer get(ContextView container) {
		return container.getOrDefault(CONTEXT_PROPAGATION_KEY, ContextContainer.NOOP);
	}

	@Override
	public Class<?> getSupportedContextClassForGet() {
		return ContextView.class;
	}

	@Override
	public Class<?> getSupportedContextClassForSet() {
		return Context.class;
	}

	@Override
	public Context remove(Context ctx) {
		return ctx.delete(CONTEXT_PROPAGATION_KEY);
	}

	@Override
	public Context set(Context container, ContextContainer value) {
		return container.put(CONTEXT_PROPAGATION_KEY, value);
	}
}
