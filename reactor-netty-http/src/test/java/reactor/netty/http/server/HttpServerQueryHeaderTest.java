/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.io.IOException;
import java.time.Duration;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufFlux;

import static org.assertj.core.api.Assertions.assertThat;

class HttpServerQueryHeaderTest extends BaseHttpTest {

	@Test
	void acceptQueryHeaderUsesStructuredFieldSyntax() {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.head("/contacts", (req, res) -> res.acceptQuery(
				          		"application/x-www-form-urlencoded",
				          		"application/sql; charset=UTF-8",
				          		"*/*")
				                                                     .status(200)
				                                                     .send()))
				          .bindNow();

		String acceptQuery = createClient(disposableServer::address)
				.head()
				.uri("/contacts")
				.responseSingle((res, bytes) -> Mono.justOrEmpty(res.responseHeaders().get("Accept-Query")))
				.block(Duration.ofSeconds(30));

		assertThat(acceptQuery).isEqualTo(
				"\"application/x-www-form-urlencoded\", \"application/sql\";charset=\"UTF-8\", \"*/*\"");
	}

	@Test
	void queryMethodAllowsFormDecoding() {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.route(req -> HttpMethod.QUERY.equals(req.method()), (req, res) ->
				                  res.send(req.receiveForm()
				                              .map(data -> {
				                                  try {
				                                      return res.alloc().buffer().writeBytes(data.get());
				                                  }
				                                  catch (IOException e) {
				                                      throw new RuntimeException(e);
				                                  }
				                              }))
				          ))
				          .bindNow();

		String response = createClient(disposableServer::address)
				.headers(h -> h.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED))
				.request(HttpMethod.QUERY)
				.uri("/form")
				.send(ByteBufFlux.fromString(Mono.just("key=value&")))
				.responseContent()
				.aggregate()
				.asString()
				.block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("value");
	}

	@Test
	void queryMethodAllowsFormDecodingWithSendForm() {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.route(req -> HttpMethod.QUERY.equals(req.method()), (req, res) ->
				                  res.send(req.receiveForm()
				                              .map(data -> {
				                                  try {
				                                      return res.alloc().buffer().writeBytes(data.get());
				                                  }
				                                  catch (IOException e) {
				                                      throw new RuntimeException(e);
				                                  }
				                              }))
				          ))
				          .bindNow();

		String response = createClient(disposableServer::address)
				.request(HttpMethod.QUERY)
				.uri("/form")
				.sendForm((req, form) -> form.attr("key", "value"))
				.responseContent()
				.aggregate()
				.asString()
				.block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("value");
	}

	@Test
	void queryMethodAllowsMultipartFormDecodingWithSendForm() {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.route(req -> HttpMethod.QUERY.equals(req.method()), (req, res) ->
				                  res.send(req.receiveForm()
				                              .map(data -> {
				                                  try {
				                                      return res.alloc().buffer().writeBytes(data.get());
				                                  }
				                                  catch (IOException e) {
				                                      throw new RuntimeException(e);
				                                  }
				                              }))
				          ))
				          .bindNow();

		String response = createClient(disposableServer::address)
				.request(HttpMethod.QUERY)
				.uri("/form")
				.sendForm((req, form) -> form.multipart(true).attr("key", "value"))
				.responseContent()
				.aggregate()
				.asString()
				.block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("value");
	}
}
