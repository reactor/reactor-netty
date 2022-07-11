/*
 * Copyright (c) 2020-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.examples.documentation.http.server.routing;

import reactor.core.publisher.Mono;
import reactor.netty5.DisposableServer;
import reactor.netty5.http.server.HttpServer;

public class Application {

	public static void main(String[] args) {
		DisposableServer server =
				HttpServer.create()
				          .route(routes ->
				              routes.get("/hello",        //<1>
				                        (request, response) -> response.sendString(Mono.just("Hello World!")))
				                    .post("/echo",        //<2>
				                        (request, response) -> response.send(request.receive().transferOwnership()))
				                    .get("/path/{param}", //<3>
				                        (request, response) -> response.sendString(Mono.just(request.param("param"))))
				                    .ws("/ws",            //<4>
				                        (wsInbound, wsOutbound) -> wsOutbound.send(wsInbound.receive().transferOwnership())))
				          .bindNow();

		server.onDispose()
		      .block();
	}
}