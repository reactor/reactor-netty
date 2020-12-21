/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

class ByteBufFluxTest {

	@Test
	void testFromString_EmptyFlux() {
		doTestFromStringEmptyPublisher(Flux.empty());
	}

	@Test
	void testFromString_EmptyMono() {
		doTestFromStringEmptyPublisher(Mono.empty());
	}

	@Test
	void testFromString_Callable() {
		doTestFromString(Mono.fromCallable(() -> "123"));
	}

	@Test
	void testFromString_Flux() {
		List<String> original = Arrays.asList("1", "2", "3");
		StepVerifier.create(ByteBufFlux.fromString(Flux.fromIterable(original)).collectList())
		            .expectNextMatches(list -> {
		                List<String> newList =
		                        list.stream()
		                            .map(b -> {
		                                String result = b.toString(Charset.defaultCharset());
		                                b.release();
		                                return result;
		                            })
		                            .collect(Collectors.toList());
		                return Objects.equals(original, newList);
		            })
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testFromString_Mono() {
		doTestFromString(Mono.just("123"));
	}

	private void doTestFromString(Publisher<? extends String> source) {
		StepVerifier.create(ByteBufFlux.fromString(source))
		            .expectNextMatches(b -> {
		                String result = b.toString(Charset.defaultCharset());
		                b.release();
		                return "123".equals(result);
		            })
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	private void doTestFromStringEmptyPublisher(Publisher<? extends String> source) {
		StepVerifier.create(ByteBufFlux.fromString(source))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}
}
