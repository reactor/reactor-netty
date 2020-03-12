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
package reactor.netty.channel;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.SUCCESS;

/**
 * @author Violeta Georgieva
 */
public class AddressResolverGroupMetricsTest {
	private MeterRegistry registry;

	@Before
	public void setUp() {
		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@After
	public void tearDown() {
		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();
	}

	@Test
	public void test() throws Exception {
		DisposableServer server =
				HttpServer.create()
				          .host("localhost")
				          .port(0)
				          .handle((req, res) -> res.header("Connection", "close")
				                                   .sendString(Mono.just("test")))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(1);
		HttpClient.create()
		          .doOnResponse((res, conn) ->
		              conn.channel()
		                  .closeFuture()
		                  .addListener(f -> latch.countDown()))
		          .metrics(true)
		          .get()
		          .uri("http://localhost:" + server.port())
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		assertThat(getTimerValue("localhost:" + server.port())).isGreaterThan(0);

		server.disposeNow();
	}


	private double getTimerValue(String address) {
		Timer timer = registry.find("reactor.netty.http.client.address.resolver")
		                      .tags(REMOTE_ADDRESS, address, STATUS, SUCCESS).timer();
		double result = -1;
		if (timer != null) {
			result = timer.totalTime(TimeUnit.NANOSECONDS);
		}
		return result;
	}
}
