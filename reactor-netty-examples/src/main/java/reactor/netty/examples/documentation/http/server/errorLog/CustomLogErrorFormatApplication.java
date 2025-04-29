/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.documentation.http.server.errorLog;

import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.logging.error.ErrorLog;

public class CustomLogErrorFormatApplication {

	public static void main(String[] args) {
		DisposableServer server =
				HttpServer.create()
				          .errorLog(true, x -> ErrorLog.create("method={}, uri={}", x.httpServerInfos().method(), x.httpServerInfos().uri()))
				          .bindNow();

		server.onDispose()
		      .block();
	}
}