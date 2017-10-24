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
package reactor.ipc.netty.http.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.ContextHandler;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

/**
 * @author Simon Baslé
 */
public class HttpClientOperationsTest {

	ContextHandler<Channel> handler = new ContextHandler<Channel>((a, b, c) -> null, null, null, null, null) {
		@Override
		public void fireContextActive(Connection context) {

		}

		@Override
		public void setFuture(Future<?> future) {

		}

		@Override
		protected void doPipeline(Channel channel) {

		}

		@Override
		protected Publisher<Void> onCloseOrRelease(Channel channel) {
			return Mono.never();
		}

		@Override
		public void accept(Channel channel) {

		}

		@Override
		public void dispose() {

		}
	};

	@Test
	public void addDecoderReplaysLastHttp() throws Exception {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(channel,
				(response, request) -> null, handler);

		ops.addHandler(new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names().iterator().next(), is("JsonObjectDecoder$extractor"));
		assertThat(channel.readInbound(), instanceOf(ByteBuf.class));
		assertThat(channel.readInbound(), instanceOf(LastHttpContent.class));
		assertThat(channel.readInbound(), nullValue());
	}

	@Test
	public void addNamedDecoderReplaysLastHttp() throws Exception {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(channel,
				(response, request) -> null, handler);

		ops.addHandler("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names().iterator().next(), is("json$extractor"));
		assertThat(channel.readInbound(), instanceOf(ByteBuf.class));
		assertThat(channel.readInbound(), instanceOf(LastHttpContent.class));
		assertThat(channel.readInbound(), nullValue());
	}

	@Test
	public void addEncoderReplaysLastHttp() throws Exception {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(channel,
				(response, request) -> null, handler);

		ops.addHandler(new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names().iterator().next(), is("JsonObjectDecoder$extractor"));
		assertThat(channel.readInbound(), instanceOf(ByteBuf.class));
		assertThat(channel.readInbound(), instanceOf(LastHttpContent.class));
		assertThat(channel.readInbound(), nullValue());
	}

	@Test
	public void addNamedEncoderReplaysLastHttp() throws Exception {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(channel,
				(response, request) -> null, handler);

		ops.addHandler("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names().iterator().next(), is("json$extractor"));
		assertThat(channel.readInbound(), instanceOf(ByteBuf.class));
		assertThat(channel.readInbound(), instanceOf(LastHttpContent.class));
		assertThat(channel.readInbound(), nullValue());
	}

	@Test
	public void testConstructorWithProvidedReplacement() {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.pipeline().addFirst(NettyPipeline.SslHandler, new ChannelHandlerAdapter() {
		});

		HttpClientOperations ops1 = new HttpClientOperations(channel,
				(response, request) -> null, handler);
		ops1.followRedirect();

		HttpClientOperations ops2 = new HttpClientOperations(channel, ops1);

		assertSame(ops1.channel(), ops2.channel());
		assertSame(ops1.started, ops2.started);
		assertSame(ops1.redirectedFrom, ops2.redirectedFrom);
		assertSame(ops1.isSecure, ops2.isSecure);
		assertSame(ops1.nettyRequest, ops2.nettyRequest);
		assertSame(ops1.responseState, ops2.responseState);
		assertSame(ops1.redirectable, ops2.redirectable);
		assertSame(ops1.inboundPrefetch, ops2.inboundPrefetch);
		assertSame(ops1.requestHeaders, ops2.requestHeaders);
	}
}
