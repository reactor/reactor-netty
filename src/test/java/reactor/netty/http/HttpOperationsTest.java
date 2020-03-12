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

package reactor.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.util.CharsetUtil;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.netty.Connection;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * @author Stephane Maldini
 */
public class HttpOperationsTest {

	@Test
	public void httpAndJsonDecoders() {

		EmbeddedChannel channel = new EmbeddedChannel();
		Connection testContext = () -> channel;

		ChannelHandler handler = new JsonObjectDecoder(true);
		testContext.addHandlerLast("foo", handler);

		HttpOperations.autoAddHttpExtractor(testContext, "foo", handler);

		String json1 = "[{\"some\": 1} , {\"valu";
		String json2 = "e\": true, \"test\": 1}]";

		Object[] content = new Object[3];
		content[0] = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		content[1] =
				new DefaultHttpContent(Unpooled.copiedBuffer(json1, CharsetUtil.UTF_8));
		content[2] = new DefaultLastHttpContent(Unpooled.copiedBuffer(json2,
				CharsetUtil.UTF_8));

		channel.writeInbound(content);

		Object t = channel.readInbound();
		assertThat(t, instanceOf(HttpResponse.class));
		assertThat(t, not(instanceOf(HttpContent.class)));

		t = channel.readInbound();
		assertThat(t, instanceOf(ByteBuf.class));
		ByteBuf b = (ByteBuf) t;
		assertThat(b.readCharSequence(b.readableBytes(), CharsetUtil.UTF_8),
				is("{\"some\": 1}"));
		b.release();

		t = channel.readInbound();
		assertThat(t, instanceOf(ByteBuf.class));
		b = (ByteBuf) t;
		assertThat(b.readCharSequence(b.readableBytes(), CharsetUtil.UTF_8),
				is("{\"value\": true, \"test\": 1}"));
		b.release();

		t = channel.readInbound();
		assertThat(t, is(LastHttpContent.EMPTY_LAST_CONTENT));
		((LastHttpContent) t).release();

		t = channel.readInbound();
		assertThat(t, nullValue());
	}

	@Test
	public void testPath() {
		TestHttpInfos infos = new TestHttpInfos();
		Flux<String> expectations = Flux.just("", "", "", "/", "a", "a", "", "a", "", "a", "a", "a b");

		doTestPath(infos, expectations,
				Flux.just("http://localhost:8080",
						"http://localhost:8080/",
						"http://localhost:8080//",
						"http://localhost:8080///",
						"http://localhost:8080/a",
						"http://localhost:8080/a/",
						"http://localhost:8080/?b",
						"http://localhost:8080/a?b",
						"http://localhost:8080/#b",
						"http://localhost:8080/a#b",
						"http://localhost:8080/a?b#c",
						"http://localhost:8080/a%20b"));

		doTestPath(infos, expectations,
				Flux.just("localhost:8080",
						"localhost:8080/",
						"localhost:8080//",
						"localhost:8080///",
						"localhost:8080/a",
						"localhost:8080/a/",
						"localhost:8080/?b",
						"localhost:8080/a?b",
						"localhost:8080/#b",
						"localhost:8080/a#b",
						"localhost:8080/a?b#c",
						"localhost:8080/a%20b"));

		doTestPath(infos, expectations, Flux.just("", "/", "//", "///", "/a", "/a/", "/?b", "/a?b", "/#b", "/a#b", "/a?b#c", "/a%20b"));
	}
	private void doTestPath(TestHttpInfos infos, Flux<String> expectations, Flux<String> uris) {
		uris.zipWith(expectations)
		            .doOnNext(tuple -> {
		                infos.uri = tuple.getT1();
		                assertEquals(tuple.getT2(), infos.path());
		            })
		            .blockLast();
	}

	@Test
	public void testFullPath() {
		assertEquals("", HttpOperations.resolvePath("http://localhost:8080"));
		assertEquals("/", HttpOperations.resolvePath("http://localhost:8080/"));
		assertEquals("//", HttpOperations.resolvePath("http://localhost:8080//"));
		assertEquals("///", HttpOperations.resolvePath("http://localhost:8080///"));
		assertEquals("/a", HttpOperations.resolvePath("http://localhost:8080/a"));
		assertEquals("/a/", HttpOperations.resolvePath("http://localhost:8080/a/"));
		assertEquals("/", HttpOperations.resolvePath("http://localhost:8080/?b"));
		assertEquals("/a", HttpOperations.resolvePath("http://localhost:8080/a?b"));
		assertEquals("/", HttpOperations.resolvePath("http://localhost:8080/#b"));
		assertEquals("/a", HttpOperations.resolvePath("http://localhost:8080/a#b"));
		assertEquals("/a", HttpOperations.resolvePath("http://localhost:8080/a?b#c"));
		assertEquals("/a b", HttpOperations.resolvePath("http://localhost:8080/a%20b"));

		assertEquals("", HttpOperations.resolvePath("localhost:8080"));
		assertEquals("/", HttpOperations.resolvePath("localhost:8080/"));
		assertEquals("//", HttpOperations.resolvePath("localhost:8080//"));
		assertEquals("///", HttpOperations.resolvePath("localhost:8080///"));
		assertEquals("/a", HttpOperations.resolvePath("localhost:8080/a"));
		assertEquals("/a/", HttpOperations.resolvePath("localhost:8080/a/"));
		assertEquals("/", HttpOperations.resolvePath("localhost:8080/?b"));
		assertEquals("/a", HttpOperations.resolvePath("localhost:8080/a?b"));
		assertEquals("/", HttpOperations.resolvePath("localhost:8080/#b"));
		assertEquals("/a", HttpOperations.resolvePath("localhost:8080/a#b"));
		assertEquals("/a", HttpOperations.resolvePath("localhost:8080/a?b#c"));
		assertEquals("/a b", HttpOperations.resolvePath("localhost:8080/a%20b"));

		assertEquals("", HttpOperations.resolvePath(""));
		assertEquals("/", HttpOperations.resolvePath("/"));
		assertEquals("//", HttpOperations.resolvePath("//"));
		assertEquals("///", HttpOperations.resolvePath("///"));
		assertEquals("/a", HttpOperations.resolvePath("/a"));
		assertEquals("/a/", HttpOperations.resolvePath("/a/"));
		assertEquals("/", HttpOperations.resolvePath("/?b"));
		assertEquals("/a", HttpOperations.resolvePath("/a?b"));
		assertEquals("/", HttpOperations.resolvePath("/#b"));
		assertEquals("/a", HttpOperations.resolvePath("/a#b"));
		assertEquals("/a", HttpOperations.resolvePath("/a?b#c"));
		assertEquals("/a b", HttpOperations.resolvePath("/a%20b"));
	}

	static final class TestHttpInfos implements HttpInfos {
		String uri;

		@Override
		public Map<CharSequence, Set<Cookie>> cookies() {
			return null;
		}

		@Override
		public boolean isKeepAlive() {
			return false;
		}

		@Override
		public boolean isWebsocket() {
			return false;
		}

		@Override
		public HttpMethod method() {
			return null;
		}

		@Override
		public String fullPath() {
			return HttpOperations.resolvePath(uri);
		}

		@Override
		public String uri() {
			return null;
		}

		@Override
		public HttpVersion version() {
			return null;
		}
	}
}
