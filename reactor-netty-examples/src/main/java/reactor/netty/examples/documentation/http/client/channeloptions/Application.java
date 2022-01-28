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
package reactor.netty.examples.documentation.http.client.channeloptions;

import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.EpollChannelOption;
//import io.netty.channel.socket.nio.NioChannelOption;
//import jdk.net.ExtendedSocketOptions;
import reactor.netty.http.client.HttpClient;
import java.net.InetSocketAddress;

public class Application {

	public static void main(String[] args) {
		HttpClient client =
				HttpClient.create()
				          .bindAddress(() -> new InetSocketAddress("host", 1234))
				          .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000) //<1>
				          .option(ChannelOption.SO_KEEPALIVE, true)            //<2>
				          // The options below are available only when NIO transport (Java 11) is used
				          // on Mac or Linux (Java does not currently support these extended options on Windows)
				          // https://bugs.openjdk.java.net/browse/JDK-8194298
				          //.option(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPIDLE), 300)
				          //.option(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPINTERVAL), 60)
				          //.option(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPCOUNT), 8);
				          // The options below are available only when Epoll transport is used
				          .option(EpollChannelOption.TCP_KEEPIDLE, 300)        //<3>
				          .option(EpollChannelOption.TCP_KEEPINTVL, 60)        //<4>
				          .option(EpollChannelOption.TCP_KEEPCNT, 8);          //<5>

		String response =
				client.get()
				      .uri("https://example.com/")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block();

		System.out.println("Response " + response);
	}
}
