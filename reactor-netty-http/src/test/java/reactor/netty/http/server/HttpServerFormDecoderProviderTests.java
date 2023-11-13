/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static reactor.netty.http.server.HttpServerFormDecoderProvider.Build.DEFAULT_CHARSET;
import static reactor.netty.http.server.HttpServerFormDecoderProvider.Build.DEFAULT_MAX_IN_MEMORY_SIZE;
import static reactor.netty.http.server.HttpServerFormDecoderProvider.Build.DEFAULT_MAX_SIZE;
import static reactor.netty.http.server.HttpServerFormDecoderProvider.Build.DEFAULT_SCHEDULER;
import static reactor.netty.http.server.HttpServerFormDecoderProvider.Build.DEFAULT_STREAMING;

/**
 * This test class verifies {@link HttpServerFormDecoderProvider}.
 *
 * @author Violeta Georgieva
 * @since 1.0.11
 */
class HttpServerFormDecoderProviderTests {

	private HttpServerFormDecoderProvider.Build builder;

	@BeforeEach
	void setUp() {
		builder = new HttpServerFormDecoderProvider.Build();
	}

	@Test
	void baseDirectory() {
		checkDefaultBaseDirectory(builder);

		Path path = Paths.get("/tmp");
		builder.baseDirectory(path);

		assertThat(builder.baseDirectory).as("base directory").isSameAs(path);

		checkDefaultCharset(builder);
		checkDefaultMaxInMemorySize(builder);
		checkDefaultMaxSize(builder);
		checkDefaultScheduler(builder);
		checkDefaultStreaming(builder);
	}

	@Test
	void baseDirectoryBadValue() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.baseDirectory(null));
	}

	@Test
	void charset() {
		checkDefaultCharset(builder);

		builder.charset(Charset.defaultCharset());

		assertThat(builder.charset).as("charset").isSameAs(Charset.defaultCharset());

		checkDefaultBaseDirectory(builder);
		checkDefaultMaxInMemorySize(builder);
		checkDefaultMaxSize(builder);
		checkDefaultScheduler(builder);
		checkDefaultStreaming(builder);
	}

	@Test
	void charsetBadValue() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.charset(null));
	}

	@Test
	void maxInMemorySize() {
		checkDefaultMaxInMemorySize(builder);

		builder.maxInMemorySize(-1);

		assertThat(builder.maxInMemorySize).as("max in-memory size").isEqualTo(-1);

		checkDefaultBaseDirectory(builder);
		checkDefaultCharset(builder);
		checkDefaultMaxSize(builder);
		checkDefaultScheduler(builder);
		checkDefaultStreaming(builder);
	}

	@Test
	void maxInMemorySizeBadValue() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxInMemorySize(-2))
				.withMessage("Maximum in-memory size must be greater or equal to -1");
	}

	@Test
	void maxSize() {
		checkDefaultMaxSize(builder);

		builder.maxSize(256);

		assertThat(builder.maxSize).as("max size").isEqualTo(256);

		checkDefaultBaseDirectory(builder);
		checkDefaultCharset(builder);
		checkDefaultMaxInMemorySize(builder);
		checkDefaultScheduler(builder);
		checkDefaultStreaming(builder);
	}

	@Test
	void maxSizeBadValue() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxSize(-2))
				.withMessage("Maximum size must be be greater or equal to -1");
	}

	@Test
	void scheduler() {
		checkDefaultScheduler(builder);

		builder.scheduler(Schedulers.immediate());

		assertThat(builder.scheduler).as("scheduler").isSameAs(Schedulers.immediate());

		checkDefaultBaseDirectory(builder);
		checkDefaultCharset(builder);
		checkDefaultMaxInMemorySize(builder);
		checkDefaultMaxSize(builder);
		checkDefaultStreaming(builder);
	}

	@Test
	void schedulerBadValue() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.scheduler(null));
	}

	@Test
	void streaming() {
		checkDefaultStreaming(builder);

		builder.streaming(true);

		assertThat(builder.streaming).as("streaming").isTrue();

		checkDefaultBaseDirectory(builder);
		checkDefaultCharset(builder);
		checkDefaultMaxInMemorySize(builder);
		checkDefaultMaxSize(builder);
		checkDefaultScheduler(builder);
	}

	private static void checkDefaultBaseDirectory(HttpServerFormDecoderProvider.Build builder) {
		assertThat(builder.baseDirectory).as("default base directory").isNull();
	}

	private static void checkDefaultCharset(HttpServerFormDecoderProvider.Build builder) {
		assertThat(builder.charset).as("default charset")
				.isSameAs(DEFAULT_CHARSET)
				.isSameAs(StandardCharsets.UTF_8);
	}

	private static void checkDefaultMaxInMemorySize(HttpServerFormDecoderProvider.Build builder) {
		assertThat(builder.maxInMemorySize).as("default max in-memory size")
				.isEqualTo(DEFAULT_MAX_IN_MEMORY_SIZE)
				.isEqualTo(16 * 1024);
	}

	private static void checkDefaultMaxSize(HttpServerFormDecoderProvider.Build builder) {
		assertThat(builder.maxSize).as("default max size")
				.isEqualTo(DEFAULT_MAX_SIZE)
				.isEqualTo(-1);
	}

	private static void checkDefaultScheduler(HttpServerFormDecoderProvider.Build builder) {
		assertThat(builder.scheduler).as("default scheduler")
				.isSameAs(DEFAULT_SCHEDULER)
				.isSameAs(Schedulers.boundedElastic());
	}

	private static void checkDefaultStreaming(HttpServerFormDecoderProvider.Build builder) {
		assertThat(builder.streaming).as("default streaming")
				.isEqualTo(DEFAULT_STREAMING)
				.isFalse();
	}
}
