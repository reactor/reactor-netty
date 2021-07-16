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
package reactor.netty.examples.documentation.udp.client.uds;

import io.netty.channel.unix.DomainSocketAddress;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.udp.UdpClient;

import java.io.File;

public class Application {

	public static void main(String[] args) {
		Connection connection =
				UdpClient.create()
				         .bindAddress(Application::newDomainSocketAddress)
				         .remoteAddress(() -> new DomainSocketAddress("/tmp/test-server.sock")) //<1>
				         .handle((in, out) ->
				             out.sendString(Mono.just("hello"))
				                .then(in.receive()
				                        .asString()
				                        .doOnNext(System.out::println)
				                        .then()))
				         .connectNow();

		connection.onDispose()
		          .block();
	}

	private static DomainSocketAddress newDomainSocketAddress() {
		try {
			File tempFile = new File("/tmp/test-client.sock");
			tempFile.delete();
			tempFile.deleteOnExit();
			return new DomainSocketAddress(tempFile);
		}
		catch (Exception e) {
			throw new RuntimeException("Error creating a temporary file", e);
		}
	}
}