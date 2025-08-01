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
package reactor.netty.examples.documentation.quic.client.lifecycle;

import reactor.netty.Connection;
import reactor.netty.quic.QuicClient;

public class Application {

	public static void main(String[] args) throws InterruptedException {
		Connection connection =
				QuicClient.create()
				          .doOnConnected(quicConnection -> System.out.println("Connected!")) //<1>
				          .connectNow();

		connection.onDispose()
		          .block();
	}
}