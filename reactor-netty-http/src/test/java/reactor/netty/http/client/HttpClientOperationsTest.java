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
package reactor.netty.http.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Simon Baslé
 */
class HttpClientOperationsTest {

	@Test
	void addDecoderReplaysLastHttp() {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT)
				.addHandler(new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("JsonObjectDecoder$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(ByteBuf.class);
		((ByteBuf) content).release();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent) content).release();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void addNamedDecoderReplaysLastHttp() {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT)
				.addHandler("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("json$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(ByteBuf.class);
		((ByteBuf) content).release();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent) content).release();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void addEncoderReplaysLastHttp() {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT)
				.addHandler(new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("JsonObjectDecoder$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(ByteBuf.class);
		((ByteBuf) content).release();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent) content).release();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void addNamedEncoderReplaysLastHttp() {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT)
				.addHandler("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("json$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(ByteBuf.class);
		((ByteBuf) content).release();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent) content).release();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void testConstructorWithProvidedReplacement() {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.pipeline().addFirst(NettyPipeline.SslHandler, new ChannelHandlerAdapter() {
		});

		HttpClientOperations ops1 = new HttpClientOperations(() -> channel,
				ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT);
		ops1.followRedirectPredicate((req, res) -> true);
		ops1.started = true;
		ops1.retrying = true;
		ops1.redirecting = new RedirectClientException(new DefaultHttpHeaders().add(HttpHeaderNames.LOCATION, "/"));

		HttpClientOperations ops2 = new HttpClientOperations(ops1);

		assertThat(ops1.channel()).isSameAs(ops2.channel());
		assertThat(ops1.started).isSameAs(ops2.started);
		assertThat(ops1.retrying).isSameAs(ops2.retrying);
		assertThat(ops1.redirecting).isSameAs(ops2.redirecting);
		assertThat(ops1.redirectedFrom).isSameAs(ops2.redirectedFrom);
		assertThat(ops1.isSecure).isSameAs(ops2.isSecure);
		assertThat(ops1.nettyRequest).isSameAs(ops2.nettyRequest);
		assertThat(ops1.responseState).isSameAs(ops2.responseState);
		assertThat(ops1.followRedirectPredicate).isSameAs(ops2.followRedirectPredicate);
		assertThat(ops1.requestHeaders).isSameAs(ops2.requestHeaders);
	}

	@Test
	void testStatus() {
		doTestStatus(HttpResponseStatus.OK);
		doTestStatus(new HttpResponseStatus(200, "Some custom reason phrase for 200 status code"));
	}

	private void doTestStatus(HttpResponseStatus status) {
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(() -> channel,
				ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT);
		ops.setNettyResponse(new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.EMPTY_BUFFER));
		assertThat(ops.status().reasonPhrase()).isEqualTo(status.reasonPhrase());
	}
}
