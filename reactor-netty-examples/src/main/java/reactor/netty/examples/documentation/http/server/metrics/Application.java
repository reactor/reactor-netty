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
package reactor.netty.examples.documentation.http.server.metrics;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.config.MeterFilter;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

public class Application {

	public static void main(String[] args) {
		Metrics.globalRegistry //<1>
		       .config()
		       .meterFilter(MeterFilter.maximumAllowableTags("reactor.netty.http.server", "URI", 100, MeterFilter.deny()));

		DisposableServer server =
				HttpServer.create()
				          .metrics(true, s -> {
				              if (s.startsWith("/stream/")) { //<2>
				                  return "/stream/{n}";
				              }
				              else if (s.startsWith("/bytes/")) {
				                  return "/bytes/{n}";
				              }
				              return s;
				          }) //<3>
				          .route(r ->
				              r.get("/stream/{n}",
				                   (req, res) -> res.sendString(Mono.just(req.param("n"))))
				               .get("/bytes/{n}",
				                   (req, res) -> res.sendString(Mono.just(req.param("n")))))
				          .bindNow();

		server.onDispose()
		      .block();
	}
}