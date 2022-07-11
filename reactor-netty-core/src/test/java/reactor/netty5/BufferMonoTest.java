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
package reactor.netty5;

import io.netty5.buffer.api.Buffer;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;

import static io.netty5.buffer.api.DefaultBufferAllocators.preferredAllocator;

class BufferMonoTest {
	static final Random rndm = new Random();

	@Test
	void testAsByteArray() {
		byte[] bytes = new byte[256];
		rndm.nextBytes(bytes);
		byte[] expected = Arrays.copyOfRange(bytes, 5, bytes.length);
		try (Buffer buffer = preferredAllocator().copyOf(bytes)) {
			BufferMono mono = new BufferMono(Mono.just(buffer.skipReadableBytes(5)));
			StepVerifier.create(mono.asByteArray())
					.expectNextMatches(byteArray -> Arrays.equals(expected, byteArray))
					.expectComplete()
					.verify(Duration.ofSeconds(30));
		}
	}

	@Test
	void testAsByteBuffer() {
		byte[] bytes = new byte[256];
		rndm.nextBytes(bytes);
		byte[] expected = Arrays.copyOfRange(bytes, 5, bytes.length);
		try (Buffer buffer = preferredAllocator().copyOf(bytes)) {
			BufferMono mono = new BufferMono(Mono.just(buffer.skipReadableBytes(5)));
			StepVerifier.create(mono.asByteBuffer())
					.expectNextMatches(byteArray -> {
						byte[] bArray = new byte[byteArray.remaining()];
						byteArray.get(bArray);
						return Arrays.equals(expected, bArray);
					})
					.expectComplete()
					.verify(Duration.ofSeconds(30));
		}
	}

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
		doTestFromString(Flux.just("1", "2", "3"));
	}

	@Test
	void testFromString_Mono() {
		doTestFromString(Mono.just("123"));
	}

	private void doTestFromString(Publisher<? extends String> source) {
		StepVerifier.create(BufferMono.fromString(source))
				.expectNextMatches(b -> {
					String result = b.toString(Charset.defaultCharset());
					b.close();
					return "123".equals(result);
				})
				.expectComplete()
				.verify(Duration.ofSeconds(30));
	}

	private void doTestFromStringEmptyPublisher(Publisher<? extends String> source) {
		StepVerifier.create(BufferMono.fromString(source))
				.expectComplete()
				.verify(Duration.ofSeconds(30));
	}
}
