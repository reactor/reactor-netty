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
package reactor.netty.examples.documentation.http.server.multipart.custom;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

public class Application {

	public static void main(String[] args) {
		DisposableServer server =
				HttpServer.create()
				          .httpFormDecoder(builder -> builder.maxInMemorySize(0))                  //<1>
				          .route(routes ->
				              routes.post("/multipart", (request, response) -> response.sendString(
				                      request.receiveForm(builder -> builder.maxInMemorySize(256)) //<2>
				                             .flatMap(data -> Mono.just('[' + data.getName() + ']')))))
				          .bindNow();

		server.onDispose()
		      .block();
	}
}