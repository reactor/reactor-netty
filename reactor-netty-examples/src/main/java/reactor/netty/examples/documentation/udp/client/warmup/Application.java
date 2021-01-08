/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.documentation.udp.client.warmup;

import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.udp.UdpClient;

import java.time.Duration;

public class Application {

	public static void main(String[] args) {
		UdpClient udpClient = UdpClient.create()
		                               .host("example.com")
		                               .port(80)
		                               .handle((udpInbound, udpOutbound) -> udpOutbound.sendString(Mono.just("hello")));

		udpClient.warmup() //<1>
		         .block();

		Connection connection = udpClient.connectNow(Duration.ofSeconds(30)); //<2>

		connection.onDispose()
		          .block();
	}
}