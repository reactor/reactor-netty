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
package reactor.netty.examples.documentation.tcp.client.lifecycle;

import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;
import java.util.concurrent.TimeUnit;

public class Application {

	public static void main(String[] args) {
		Connection connection =
				TcpClient.create()
				         .host("example.com")
				         .port(80)
				         .doOnConnected(conn ->
				             conn.addHandlerFirst(new ReadTimeoutHandler(10, TimeUnit.SECONDS))) //<1>
				         .doOnChannelInit((observer, channel, remoteAddress) ->
				             channel.pipeline()
				                    .addFirst(new LoggingHandler("reactor.netty.examples")))     //<2>
				         .connectNow();

		connection.onDispose()
		          .block();
	}
}