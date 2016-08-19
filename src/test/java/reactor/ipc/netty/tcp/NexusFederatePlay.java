/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.tcp;

import java.util.concurrent.CountDownLatch;

import reactor.ipc.netty.http.HttpClient;
import reactor.ipc.netty.nexus.Nexus;

/**
 * @author Stephane Maldini
 */
public class NexusFederatePlay {

	public static void main(String... args) throws Exception{

		Nexus nexus2 = Nexus.create(12014);
		nexus2.startAndAwait();

		Nexus nexus = Nexus.create();
		nexus.withSystemStats()
		     .federate("ws://localhost:12014/on/stream")
		     .startAndAwait();


		nexus.monitor(nexus);
//
		HttpClient client = HttpClient.create();
		client
		           .ws("ws://localhost:12014/on/stream")
				   .subscribe( ch ->
						   ch.receive().subscribe(System.out::println)
				   );

		nexus.monitor(nexus2);
		nexus.monitor(client);

		CountDownLatch latch = new CountDownLatch(1);

		latch.await();
	}
}
