/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import static org.assertj.core.api.Assertions.assertThat;

public class PublisherContextTest {

	@Test
	public void shouldSubscribeWithContextCorrectlyToMonoPublisher() {
		AtomicReference<Context> context = new AtomicReference<>();
		Publisher<String> publisher = PublisherContext.withContext(
				Mono.just("a")
                    .subscriberContext(c -> {
                        context.set(c);
                        return c;
                    }),
				Context.of("Hello", "World")
		);

		assertThat(publisher instanceof Mono).isTrue();
		StepVerifier.create(publisher)
		            .expectSubscription()
		            .expectNext("a")
		            .verifyComplete();
		assertThat(context.get().get("Hello").equals("World")).isTrue();
	}

	@Test
	public void shouldSubscribeWithContextCorrectlyToFluxPublisher() {
		AtomicReference<Context> context = new AtomicReference<>();
		Publisher<String> publisher = PublisherContext.withContext(
				Flux.just("a", "b", "c")
				    .subscriberContext(c -> {
					    context.set(c);
					    return c;
				    }),
				Context.of("Hello", "World")
		);

		assertThat(publisher instanceof Flux).isTrue();
		StepVerifier.create(publisher)
		            .expectSubscription()
		            .expectNext("a", "b", "c")
		            .verifyComplete();
		assertThat(context.get().get("Hello").equals("World")).isTrue();
	}

	@Test
	public void shouldSubscribeWithContextCorrectlyToPublisherAndReturnFlux() {
		AtomicReference<Context> context = new AtomicReference<>();
		Publisher<String> publisher = PublisherContext.withContext(
				s -> {
					context.set(((CoreSubscriber)s).currentContext());
					s.onSubscribe(new Subscription() {
						@Override
						public void request(long n) {

						}

						@Override
						public void cancel() {

						}
					});
					s.onComplete();
				},
				Context.of("Hello", "World")
		);

		assertThat(publisher instanceof Flux).isTrue();
		StepVerifier.create(publisher)
		            .expectSubscription()
		            .verifyComplete();
		assertThat(context.get().get("Hello").equals("World")).isTrue();
	}
}
