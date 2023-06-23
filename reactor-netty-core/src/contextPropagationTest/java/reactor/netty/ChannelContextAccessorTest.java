/*
 * Copyright (c) 2022-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.util.context.Context;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.ReactorNetty.setChannelContext;

class ChannelContextAccessorTest {
	static final ContextRegistry registry = ContextRegistry.getInstance();

	static Object[][] data() {
		return new Object[][]{
				{null, "test1"},
				{Context.empty(), "test1"},
				{Context.of("test", "test"), "test1"}
		};
	}

	@ParameterizedTest
	@MethodSource("data")
	@SuppressWarnings("FutureReturnValueIgnored")
	void test(Context context, String expectation) {
		registry.registerThreadLocalAccessor(new TestThreadLocalAccessor());

		TestThreadLocalHolder.value("test1");

		Channel channel = new EmbeddedChannel();
		setChannelContext(channel, context);

		ContextSnapshot.captureAll(registry).updateContext(channel);

		TestThreadLocalHolder.value("test2");

		try (ContextSnapshot.Scope scope = ContextSnapshot.setAllThreadLocalsFrom(channel)) {
			assertThat(TestThreadLocalHolder.value()).isEqualTo(expectation);
		}
		finally {
			//"FutureReturnValueIgnored" this is deliberate
			channel.close();
			registry.removeThreadLocalAccessor(TestThreadLocalAccessor.KEY);
		}
	}
}
