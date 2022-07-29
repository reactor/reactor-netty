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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

import static io.netty5.buffer.api.DefaultBufferAllocators.preferredAllocator;
import static org.assertj.core.api.Assertions.assertThat;

class BufferFluxTest {
	private static final Random rndm = new Random();

	private static File temporaryDirectory;

	@BeforeAll
	static void createTempDir() {
		temporaryDirectory = createTemporaryDirectory();
	}

	@AfterAll
	static void deleteTempDir() {
		deleteTemporaryDirectoryRecursively(temporaryDirectory);
	}

	@Test
	void testAsByteArray() {
		byte[] bytes = new byte[256];
		rndm.nextBytes(bytes);
		byte[] expected = Arrays.copyOfRange(bytes, 5, bytes.length);
		try (Buffer buffer1 = preferredAllocator().copyOf(bytes);
		     Buffer buffer2 = preferredAllocator().copyOf(bytes)) {
			BufferFlux mono = new BufferFlux(Flux.just(buffer1.skipReadableBytes(5), buffer2.skipReadableBytes(5)),
					preferredAllocator());
			StepVerifier.create(mono.asByteArray().collectList())
					.expectNextMatches(byteArrayList ->
						byteArrayList.size() == 2 && Arrays.equals(expected, byteArrayList.get(0)) &&
								Arrays.equals(expected, byteArrayList.get(1)))
					.expectComplete()
					.verify(Duration.ofSeconds(30));
		}
	}

	@Test
	void testAsByteBuffer() {
		byte[] bytes = new byte[256];
		rndm.nextBytes(bytes);
		byte[] expected = Arrays.copyOfRange(bytes, 5, bytes.length);
		try (Buffer buffer1 = preferredAllocator().copyOf(bytes);
		     Buffer buffer2 = preferredAllocator().copyOf(bytes)) {
			BufferFlux mono = new BufferFlux(Flux.just(buffer1.skipReadableBytes(5), buffer2.skipReadableBytes(5)),
					preferredAllocator());
			StepVerifier.create(mono.asByteBuffer().collectList())
					.expectNextMatches(byteArrayList -> {
						if (byteArrayList.size() == 2) {
							byte[] bArray1 = new byte[byteArrayList.get(0).remaining()];
							byteArrayList.get(0).get(bArray1);
							byte[] bArray2 = new byte[byteArrayList.get(1).remaining()];
							byteArrayList.get(1).get(bArray2);
							return Arrays.equals(expected, bArray1) && Arrays.equals(expected, bArray2);
						}
						return false;
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
		List<String> original = Arrays.asList("1", "2", "3");
		StepVerifier.create(BufferFlux.fromString(Flux.fromIterable(original)).collectList())
				.expectNextMatches(list -> {
					List<String> newList =
							list.stream()
									.map(b -> {
										String result = b.toString(Charset.defaultCharset());
										b.close();
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
		StepVerifier.create(BufferFlux.fromString(source))
				.expectNextMatches(b -> {
					String result = b.toString(Charset.defaultCharset());
					b.close();
					return "123".equals(result);
				})
				.expectComplete()
				.verify(Duration.ofSeconds(30));
	}

	private void doTestFromStringEmptyPublisher(Publisher<? extends String> source) {
		StepVerifier.create(BufferFlux.fromString(source))
				.expectComplete()
				.verify(Duration.ofSeconds(30));
	}

	@Test
	void testFromPath() throws Exception {
		// Create a temporary file with some binary data that will be read in chunks using the BufferFlux
		final int chunkSize = 3;
		final Path tmpFile = new File(temporaryDirectory, "content.in").toPath();
		final byte[] data = new byte[]{0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8, 0x9};
		Files.write(tmpFile, data);
		// Make sure the file is 10 bytes (i.e. the same as the data length)
		assertThat(data.length).isEqualTo(Files.size(tmpFile));

		// Use the BufferFlux to read the file in chunks of 3 bytes max and write them into a ByteArrayOutputStream for verification
		final Iterator<Buffer> it = BufferFlux.fromPath(tmpFile, chunkSize)
				.toIterable()
				.iterator();
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		while (it.hasNext()) {
			Buffer bb = it.next();
			byte[] read = new byte[bb.readableBytes()];
			bb.readBytes(read, 0, read.length);
			bb.close();
			assertThat(bb.readableBytes()).isEqualTo(0);
			out.write(read);
		}

		// Verify that we read the file.
		assertThat(data).isEqualTo(out.toByteArray());
		System.out.println(Files.exists(tmpFile));
	}

	private static File createTemporaryDirectory() {
		try {
			return Files.createTempDirectory("ByteBufFluxTest").toFile();
		}
		catch (Exception e) {
			throw new RuntimeException("Error creating the temporary directory", e);
		}
	}

	private static void deleteTemporaryDirectoryRecursively(final File file) {
		if (temporaryDirectory == null || !temporaryDirectory.exists()) {
			return;
		}
		final File[] files = file.listFiles();
		if (files != null) {
			for (File childFile : files) {
				deleteTemporaryDirectoryRecursively(childFile);
			}
		}
		file.deleteOnExit();
	}
}
