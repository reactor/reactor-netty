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
package reactor.ipc.netty.options;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import org.junit.Test;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

public class NettyOptionsTest {

	@Test
	public void afterChannelInit() throws InterruptedException {
		List<Channel> initializedChannels = new ArrayList<>();

		Connection connection =
				HttpServer.create(opt -> opt.afterChannelInit(initializedChannels::add))
				          .start((req, resp) -> resp.sendNotFound())
				          .getContext();

		assertThat(initializedChannels).hasSize(0);

		HttpClient.create(opt -> opt.connectAddress(() -> connection.address()))
		          .get("/", req -> req.failOnClientError(false).send())
		          .block();

		assertThat(initializedChannels)
				.hasSize(1)
				.doesNotContain(connection.channel());
	}

	@Test
	public void afterChannelInitThenChannelGroup() {
		ChannelGroup group = new DefaultChannelGroup(null);
		List<Channel> initializedChannels = new ArrayList<>();

		Connection connection =
				HttpServer.create(opt -> opt
						.afterChannelInit(initializedChannels::add)
						.channelGroup(group)
				)
				          .start((req, resp) -> resp.sendNotFound())
				          .getContext();

		HttpClient.create(opt -> opt.connectAddress(() -> connection.address()))
		          .get("/", req -> req.failOnClientError(false).send())
		          .block();

		assertThat((Iterable<Channel>) group)
				.hasSize(1)
				.hasSameElementsAs(initializedChannels)
				.doesNotContain(connection.channel());
	}

	@Test
	public void afterChannelInitAfterChannelGroup() {
		//this test only differs from afterChannelInitThenChannelGroup in the order of the options
		ChannelGroup group = new DefaultChannelGroup(null);
		List<Channel> initializedChannels = new ArrayList<>();

		Connection connection =
				HttpServer.create(opt -> opt
						.channelGroup(group)
						.afterChannelInit(initializedChannels::add)
				)
				          .start((req, resp) -> resp.sendNotFound())
				          .getContext();

		HttpClient.create(opt -> opt.connectAddress(() -> connection.address()))
		          .get("/", req -> req.failOnClientError(false).send())
		          .block();

		assertThat((Iterable<Channel>) group)
				.hasSize(1)
				.hasSameElementsAs(initializedChannels)
				.doesNotContain(connection.channel());
	}

	@Test
	public void channelIsAddedToChannelGroup() {
		//create a ChannelGroup that never removes disconnected channels
		ChannelGroup group = new DefaultChannelGroup(null) {
			@Override
			public boolean remove(Object o) {
				System.err.println("removed " + o);
				return false;
			}
		};

		Connection connection =
				HttpServer.create(opt -> opt.channelGroup(group))
				          .start((req, resp) -> resp.sendNotFound())
				          .getContext();

		HttpClient.create(opt -> opt.connectAddress(() -> connection.address()))
		          .get("/", req -> req.failOnClientError(false).send())
		          .block();

		assertThat((Iterable<Channel>) group)
				//the main Connection channel is not impacted by pipeline options
				.doesNotContain(connection.channel())
				//the GET triggered a Channel added to the group
				.hasSize(1);
	}

	@Test
	public void afterNettyContextInit() {
		AtomicInteger readCount = new AtomicInteger();
		ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter() {
			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg)
					throws Exception {
				readCount.incrementAndGet();
				super.channelRead(ctx, msg);
			}
		};
		String handlerName = "test";

		Connection connection =
				HttpServer.create(opt -> opt.afterNettyContextInit(c -> c.addHandlerFirst(handlerName, handler)))
				          .start((req, resp) -> resp.sendNotFound())
				          .getContext();

		HttpClientResponse response1 = HttpClient.create(opt -> opt.connectAddress(() -> connection.address()))
		                                         .get("/", req -> req.failOnClientError(false).send())
		                                         .block();

		assertThat(response1.status().code()).isEqualTo(404);

		//the "main" context doesn't get enriched with handlers from options...
		assertThat(connection.channel().pipeline().names()).doesNotContain(handlerName);
		//...but the child channels that are created for requests are
		assertThat(readCount.get()).isEqualTo(1);

		HttpClientResponse response2 = HttpClient.create(opt -> opt.connectAddress(() -> connection.address()))
		                                         .get("/", req -> req.failOnClientError(false).send())
		                                         .block();

		assertThat(response2.status().code()).isEqualTo(404); //reactor handler was applied and produced a response
		assertThat(readCount.get()).isEqualTo(1); //BUT channelHandler wasn't applied a second time since not Shareable
	}

}