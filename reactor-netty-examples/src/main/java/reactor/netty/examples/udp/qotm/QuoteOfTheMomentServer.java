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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.DatagramPacket;
import reactor.core.publisher.Flux;
import reactor.netty.udp.UdpServer;

import java.nio.charset.StandardCharsets;
import java.util.Random;

public class QuoteOfTheMomentServer {

	private static final int PORT = Integer.parseInt(System.getProperty("port", "7686"));
	private static final boolean WIRETAP = System.getProperty("wiretap") != null;

	private static final Random random = new Random();

	private static final String[] quotes = {"Where there is love there is life.",
			"First they ignore you, then they laugh at you, then they fight you, then you win.",
			"Be the change you want to see in the world.",
			"The weak can never forgive. Forgiveness is the attribute of the strong."};

	private static String nextQuote() {
		int quoteId;
		synchronized (random) {
			quoteId = random.nextInt(quotes.length);
		}
		return quotes[quoteId];

	}

	public static void main(String[] args) {
		UdpServer server =
				UdpServer.create()
				         .port(PORT)
				         .wiretap(WIRETAP)
				         .option(ChannelOption.SO_BROADCAST, true)
				         .handle((in, out) -> {
				             Flux<DatagramPacket> inFlux =
				                     in.receiveObject()
				                       .handle((incoming, sink) -> {
				                           if (incoming instanceof DatagramPacket) {
				                               DatagramPacket packet = (DatagramPacket) incoming;
				                               String content = packet.content().toString(StandardCharsets.UTF_8);

				                               if ("QOTM?".equalsIgnoreCase(content)) {
				                                   String nextQuote = nextQuote();
				                                   ByteBuf byteBuf =
				                                           Unpooled.copiedBuffer("QOTM: " + nextQuote, StandardCharsets.UTF_8);
				                                   DatagramPacket response = new DatagramPacket(byteBuf, packet.sender());
				                                   sink.next(response);
				                               }
				                           }
				                       });

				             return out.sendObject(inFlux);
				});

		server.bindNow()
		      .onDispose()
		      .block();
	}
}
