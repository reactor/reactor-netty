/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.BaseHttpTest.createClient;

class HeaderTest {

	@ParameterizedTest
	@ValueSource(ints = {1_000, 1_000_000})
	void testHeaderTooLong(final int headerSize) {
		final String path = "/";
		final DisposableServer server = HttpServer.create()
				.port(0)
				.httpRequestDecoder(c -> c.maxHeaderSize(100))
				.route(routes -> routes.get(path, (req, resp) -> resp.sendString(Mono.just("oh nos!"))))
				.bindNow();

		final String headerVal =
				IntStream.range(0, headerSize)
						.mapToObj(i -> "a")
						.collect(Collectors.joining());

		final HttpClient client = createClient(server.port());

		final int status = client.headers(hs -> hs.add("longheader", headerVal))
				.get()
				.uri(path)
				.response()
				.map(resp -> resp.status().code())
				.block();

		assertThat(status).isEqualTo(413);
		server.disposeNow();
	}
}
