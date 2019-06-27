/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.channel;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 */
public class MonoSendManyTest {

	@Test
	public void testPromiseSendTimeout() {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new WriteTimeoutHandler(1), new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Flux.just("test"), channel, b -> false);

		StepVerifier.create(m)
		            .then(() -> {
		                channel.runPendingTasks(); //run flush
		                assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test");
		            })
		            .verifyComplete();
	}
}
