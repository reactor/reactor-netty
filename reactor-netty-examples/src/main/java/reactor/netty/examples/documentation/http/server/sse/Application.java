/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.documentation.http.server.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.function.BiFunction;

public class Application {

	public static void main(String[] args) {
		DisposableServer server =
				HttpServer.create()
				          .route(routes -> routes.get("/sse", serveSse()))
				          .bindNow();

		server.onDispose()
		      .block();
	}

	/**
	 * Prepares SSE response.
	 * The "Content-Type" is "text/event-stream".
	 * The flushing strategy is "flush after every element" emitted by the provided Publisher.
	 */
	private static BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> serveSse() {
		Flux<Long> flux = Flux.interval(Duration.ofSeconds(10));
		return (request, response) ->
		        response.sse()
		                .send(flux.map(Application::toByteBuf), b -> true);
	}

	/**
	 * Transforms the Object to ByteBuf following the expected SSE format.
	 */
	private static ByteBuf toByteBuf(Object any) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			out.write("data: ".getBytes(Charset.defaultCharset()));
			MAPPER.writeValue(out, any);
			out.write("\n\n".getBytes(Charset.defaultCharset()));
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		return ByteBufAllocator.DEFAULT
		                       .buffer()
		                       .writeBytes(out.toByteArray());
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();
}