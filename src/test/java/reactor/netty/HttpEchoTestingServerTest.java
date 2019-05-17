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
package reactor.netty;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import org.junit.ClassRule;
import org.junit.Test;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Simon Baslé
 */
public class HttpEchoTestingServerTest {

	@ClassRule
	public static HttpEchoTestingServer server = new HttpEchoTestingServer();

	@Test
	public void echoGetStatus() {
		Tuple2<Integer, String> response =
				HttpClient.create()
				          .baseUrl(server.getBaseUrl())
				          .get()
				          .uri("/status/400?q=p+space&p=q+space")
				          .responseSingle((r, body) -> body.asString().map(b -> Tuples.of(r.status().code(), b)))
				          .block();

		assertThat(response.getT1()).as("status code").isEqualTo(400);
		assertThat(response.getT2())
				.startsWith("{\n" +
						"  \"method\" : \"GET\",\n" +
						"  \"uri\" : \"" + server.getBaseUrl() + "status/400?q=p+space&p=q+space\",\n" +
						"  \"path\" : \"/status/400\",\n" +
						"  \"queryParams\" : {\n" +
						"    \"q\" : [ \"p+space\" ],\n" +
						"    \"p\" : [ \"q+space\" ]\n" +
						"  },\n" +
						"  \"headers\" : {\n" +
						"    \"User-Agent\" : \"ReactorNetty/dev\",\n")
				.endsWith(
						"    \"Accept\" : \"*/*\",\n" +
								"    \"Content-Length\" : \"0\"\n" +
								"  },\n" +
								"  \"body\" : \"\"\n" +
								"}"
				);
	}

	@Test
	public void echoPutAnything() {
		Tuple2<Integer, String> response =
				HttpClient.create()
				          .baseUrl(server.getBaseUrl())
				          .put()
				          .uri("/anything?q=p+space&p=q+space")
				          .send(Mono.just(ByteBufUtil.writeUtf8(ByteBufAllocator.DEFAULT, "Example Body é™")))
				          .responseSingle((r, body) -> body.asString().map(b -> Tuples.of(r.status().code(), b)))
				          .block();

		assertThat(response.getT1()).as("status code").isEqualTo(200);
		assertThat(response.getT2())
				.startsWith("{\n" +
						"  \"method\" : \"PUT\",\n" +
						"  \"uri\" : \"" + server.getBaseUrl() + "anything?q=p+space&p=q+space\",\n" +
						"  \"path\" : \"/anything\",\n" +
						"  \"queryParams\" : {\n" +
						"    \"q\" : [ \"p+space\" ],\n" +
						"    \"p\" : [ \"q+space\" ]\n" +
						"  },\n" +
						"  \"headers\" : {\n" +
						"    \"User-Agent\" : \"ReactorNetty/dev\",\n")
				.endsWith(
						"    \"Accept\" : \"*/*\",\n" +
								"    \"Content-Length\" : \"18\"\n" +
								"  },\n" +
								"  \"body\" : \"Example Body é™\"\n" +
								"}"
				);
	}

	@Test
	public void echoHeaders() {
		Tuple2<Integer, String> response =
				HttpClient.create()
				          .baseUrl(server.getBaseUrl())
				          .headers(h -> h.add("x-custom", "foo")
				                         .add(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE)
				                         .remove(HttpHeaderNames.HOST))
				          .get()
				          .uri("/headers")
				          .responseSingle((r, body) -> body.asString().map(b -> Tuples.of(r.status().code(), b)))
				          .block();

		assertThat(response.getT1()).as("status code").isEqualTo(200);
		assertThat(response.getT2())
				.isEqualTo("{\n" +
						"  \"headers\" : {\n" +
						"    \"User-Agent\" : \"ReactorNetty/dev\",\n" +
						"    \"Host\" : \"localhost:" + server.getPort() + "\",\n" +
						"    \"Cache-Control\" : \"no-cache\",\n" +
						"    \"Accept\" : \"*/*\",\n" +
						"    \"Content-Length\" : \"0\",\n" +
						"    \"x-custom\" : \"foo\"\n" +
						"  }\n" +
						"}"
				);
	}

}