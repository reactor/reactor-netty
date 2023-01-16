/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.udp.qotm;

import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.udp.UdpClient;

public class QuoteOfTheMomentClient {

	private static final int PORT = Integer.parseInt(System.getProperty("port", "7686"));
	private static final boolean WIRETAP = System.getProperty("wiretap") != null;

	public static void main(String[] args) {
		UdpClient client =
				UdpClient.create()
				         .port(PORT)
				         .wiretap(WIRETAP);

		Connection conn = client.connectNow();

		conn.inbound()
		    .receive()
		    .asString()
		    .doOnNext(text -> {
		        if (text.startsWith("QOTM:")) {
		            System.out.println("Quote of the Moment: " + text.substring(6));
		            conn.disposeNow();
		        }
		    })
		    .doOnError(err -> {
		        err.printStackTrace();
		        conn.disposeNow();
		    })
		    .subscribe();

		conn.outbound()
		    .sendString(Mono.just("QOTM?"))
		    .then()
		    .subscribe();

		conn.onReadIdle(5000, () -> {
			System.err.println("QOTM request timed out.");
			conn.disposeNow();
		});

		conn.onDispose()
		    .block();
	}
}
