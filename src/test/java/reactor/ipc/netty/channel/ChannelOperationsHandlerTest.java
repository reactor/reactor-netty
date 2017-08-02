/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.Test;

import io.netty.channel.embedded.EmbeddedChannel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

public class ChannelOperationsHandlerTest {

	@Test
	public void publisherSenderOnCompleteFlushInProgress() {
		NettyContext server =
				HttpServer.create(0)
				          .newHandler((req, res) ->
				                  req.receive()
				                     .asString()
				                     .doOnNext(System.err::println)
				                     .then(res.status(200).sendHeaders().then()))
				          .block(Duration.ofSeconds(30));

		Flux<String> flux = Flux.range(1, 257).map(count -> count + "");
		Mono<HttpClientResponse> client =
				HttpClient.create(server.address().getPort())
				          .post("/", req -> req.sendString(flux));

		StepVerifier.create(client)
		            .expectNextMatches(res -> res.status().code() == 200)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void keepPrefetchSizeConstant() {
		ChannelOperationsHandler handler = new ChannelOperationsHandler(null);

		EmbeddedChannel channel = new EmbeddedChannel(handler);
		channel.config().setWriteBufferLowWaterMark(1024)
		                .setWriteBufferHighWaterMark(1024);

		assertThat(handler.prefetch == (handler.inner.requested - handler.inner.produced)).isTrue();

		StepVerifier.create(FutureMono.deferFuture(() -> channel.writeAndFlush(Flux.range(0, 70))))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(handler.prefetch == (handler.inner.requested - handler.inner.produced)).isTrue();
	}
}
