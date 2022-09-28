/*
 * Copyright (c) 2017-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.client;

import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.handler.codec.http.DefaultFullHttpResponse;
import io.netty5.handler.codec.http.DefaultLastHttpContent;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty.contrib.handler.codec.json.JsonObjectDecoder;
import java.nio.charset.StandardCharsets;

import io.netty5.handler.codec.http.headers.HttpHeaders;
import org.junit.jupiter.api.Test;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.NettyPipeline;

import static io.netty5.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Simon Baslé
 */
class HttpClientOperationsTest {

	@Test
	void addDecoderReplaysLastHttp() {
		EmbeddedChannel channel = new EmbeddedChannel();
		Buffer buf = channel.bufferAllocator().copyOf("{\"foo\":1}".getBytes(StandardCharsets.UTF_8));
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener())
				.addHandlerLast(new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("JsonObjectDecoder$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(Buffer.class);
		((Buffer) content).close();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent<?>) content).close();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void addNamedDecoderReplaysLastHttp() {
		EmbeddedChannel channel = new EmbeddedChannel();
		Buffer buf = channel.bufferAllocator().copyOf("{\"foo\":1}".getBytes(StandardCharsets.UTF_8));
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener())
				.addHandlerLast("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("json$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(Buffer.class);
		((Buffer) content).close();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent<?>) content).close();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void addEncoderReplaysLastHttp() {
		EmbeddedChannel channel = new EmbeddedChannel();
		Buffer buf = channel.bufferAllocator().copyOf("{\"foo\":1}".getBytes(StandardCharsets.UTF_8));
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener())
				.addHandlerLast(new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("JsonObjectDecoder$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(Buffer.class);
		((Buffer) content).close();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent<?>) content).close();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void addNamedEncoderReplaysLastHttp() {
		EmbeddedChannel channel = new EmbeddedChannel();
		Buffer buf = channel.bufferAllocator().copyOf("{\"foo\":1}".getBytes(StandardCharsets.UTF_8));
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener())
				.addHandlerLast("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("json$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(Buffer.class);
		((Buffer) content).close();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent<?>) content).close();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void testConstructorWithProvidedReplacement() {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.pipeline().addFirst(NettyPipeline.SslHandler, new ChannelHandlerAdapter() {
		});

		HttpClientOperations ops1 = new HttpClientOperations(() -> channel,
				ConnectionObserver.emptyListener());
		ops1.followRedirectPredicate((req, res) -> true);
		ops1.started = true;
		ops1.retrying = true;
		ops1.redirecting = new RedirectClientException(HttpHeaders.newHeaders().add(HttpHeaderNames.LOCATION, "/"),
				HttpResponseStatus.MOVED_PERMANENTLY);

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
				ConnectionObserver.emptyListener());
		ops.setNettyResponse(new DefaultFullHttpResponse(HTTP_1_1, status, channel.bufferAllocator().allocate(0)));
		assertThat(ops.status().reasonPhrase()).isEqualTo(status.reasonPhrase());
	}
}
