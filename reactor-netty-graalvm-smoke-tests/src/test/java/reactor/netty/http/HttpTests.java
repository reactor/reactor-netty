/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class HttpTests {
	DisposableServer disposableServer;

	@AfterEach
	void tearDown() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
	}

	@Test
	void smokeTest() {
		String transport = System.getProperty("forceTransport");
		String osName = System.getProperty("os.name");
		if ("native".equals(transport)) {
			if ("Linux".equals(osName)) {
				assertThat(Epoll.isAvailable()).isTrue();
			}
			else if ("Mac OS X".equals(osName)) {
				assertThat(KQueue.isAvailable()).isTrue();
			}
		}
		else {
			assertThat(Epoll.isAvailable()).isFalse();
			assertThat(KQueue.isAvailable()).isFalse();
		}

		disposableServer =
				HttpServer.create()
				          .handle((req, res) -> res.sendString(Mono.just("Hello World!")))
				          .bindNow();

		HttpClient.create()
		          .port(disposableServer.port())
		          .get()
		          .uri("/")
		          .responseContent()
		          .aggregate()
		          .asString()
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(5));
	}
}
