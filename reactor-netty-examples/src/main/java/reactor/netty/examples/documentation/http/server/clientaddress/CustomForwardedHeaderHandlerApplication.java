/*
 * Copyright (c) 2020-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.documentation.http.server.clientaddress;

import java.net.InetSocketAddress;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.transport.AddressUtils;

public class CustomForwardedHeaderHandlerApplication {

	public static void main(String[] args) {
		DisposableServer server =
				HttpServer.create()
				          .forwarded((connectionInfo, request) -> {  // <1>
				              String hostHeader = request.headers().get("X-Forwarded-Host");
				              if (hostHeader != null) {
				                  String[] hosts = hostHeader.split(",", 2);
				                  InetSocketAddress hostAddress = AddressUtils.createUnresolved(
				                      hosts[hosts.length - 1].trim(),
				                      connectionInfo.getHostAddress().getPort());
				                  connectionInfo = connectionInfo.withHostAddress(hostAddress);
				              }
				              return connectionInfo;
				          })
				          .route(routes ->
				              routes.get("/clientip",
				                  (request, response) ->
				                      response.sendString(Mono.just(request.remoteAddress() // <2>
				                                                           .getHostString()))))
				          .bindNow();

		server.onDispose()
		      .block();
	}
}