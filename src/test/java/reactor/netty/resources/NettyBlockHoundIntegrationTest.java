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

package reactor.netty.resources;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

/**
 * Smoke test that ensures that BlockHound is enabled
 */
public class NettyBlockHoundIntegrationTest {

    @Test
    public void shouldDisallowBlockingCalls() throws Exception {
        DefaultLoopResources resources = new DefaultLoopResources(
                "foo",
                1,
                true
        );
        try {
            CompletableFuture<?> future = new CompletableFuture<>();
            resources.cacheNioSelectLoops().submit(() -> {
                try {
                    Thread.sleep(10);
                    future.complete(null);
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
                return "";
            });

            try {
                future.get(10, TimeUnit.SECONDS);
                fail("should fail");
            }
            catch (ExecutionException e) {
                assertThat(
                        e.getCause().getMessage(),
                        containsString("Blocking call!")
                );
            }
        }
        finally {
            resources.disposeLater().block();
        }
    }

}