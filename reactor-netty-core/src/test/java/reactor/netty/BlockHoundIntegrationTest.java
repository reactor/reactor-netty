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
import reactor.blockhound.BlockingOperationError;
import reactor.netty.resources.LoopResources;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Smoke test that ensures that BlockHound is enabled
 */
class BlockHoundIntegrationTest {

	@Test
	@SuppressWarnings("FutureReturnValueIgnored")
	void shouldDisallowBlockingCalls() {
		LoopResources resources = LoopResources.create("foo", 1, true);
		try {
			CompletableFuture<?> future = new CompletableFuture<>();
			resources.onServerSelect(false)
			         .submit(() -> {
			             try {
			                 Thread.sleep(10);
			                 future.complete(null);
			             }
			             catch (Throwable e) {
			                 future.completeExceptionally(e);
			             }
			             return "";
			         });

			assertThatExceptionOfType(ExecutionException.class)
					.isThrownBy(() -> future.get(10, TimeUnit.SECONDS))
					.withCauseInstanceOf(BlockingOperationError.class)
					.withMessageContaining("Blocking call!");
		}
		finally {
			resources.disposeLater()
			         .block(Duration.ofSeconds(10));
		}
	}
}