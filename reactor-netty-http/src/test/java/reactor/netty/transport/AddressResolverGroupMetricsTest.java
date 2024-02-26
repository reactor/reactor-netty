/*
 * Copyright (c) 2019-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.transport;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.micrometer.TimerAssert.assertTimer;

/**
 * This test class verifies name resolution metrics functionality.
 *
 * @author Violeta Georgieva
 */
class AddressResolverGroupMetricsTest extends BaseHttpTest {
	private MeterRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() {
		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();
	}

	@Test
	void test() throws Exception {
		disposableServer =
				createServer()
				          .host("localhost")
				          .handle((req, res) -> res.header("Connection", "close")
				                                   .sendString(Mono.just("test")))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(1);
		HttpClient.create()
		          .doOnResponse((res, conn) ->
		              conn.channel()
		                  .closeFuture()
		                  .addListener(f -> latch.countDown()))
		          .metrics(true, Function.identity())
		          .get()
		          .uri("http://localhost:" + disposableServer.port())
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		String address = "localhost:" + disposableServer.port();
		assertTimer(registry, "reactor.netty.http.client.address.resolver", REMOTE_ADDRESS, address,  STATUS, SUCCESS)
				.hasTotalTimeGreaterThan(0);
	}
}
