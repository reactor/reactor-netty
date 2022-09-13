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
package reactor.netty5.http;

import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.handler.codec.http.DefaultHttpContent;
import io.netty5.handler.codec.http.DefaultHttpResponse;
import io.netty5.handler.codec.http.DefaultLastHttpContent;
import io.netty5.handler.codec.http.EmptyLastHttpContent;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.HttpVersion;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.handler.codec.http.cookie.Cookie;
import io.netty.contrib.handler.codec.json.JsonObjectDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.netty5.Connection;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 */
class HttpOperationsTest {

	@Test
	void httpAndJsonDecoders() {

		EmbeddedChannel channel = new EmbeddedChannel();
		Connection testContext = () -> channel;

		ChannelHandler handler = new JsonObjectDecoder(true);
		testContext.addHandlerLast("foo", handler);

		HttpOperations.autoAddHttpExtractor(testContext, "foo", handler);

		String json1 = "[{\"some\": 1} , {\"valu";
		String json2 = "e\": true, \"test\": 1}]";

		Object[] content = new Object[3];
		content[0] = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		content[1] = new DefaultHttpContent(channel.bufferAllocator().copyOf(json1.getBytes(StandardCharsets.UTF_8)));
		content[2] = new DefaultLastHttpContent(channel.bufferAllocator().copyOf(json2.getBytes(StandardCharsets.UTF_8)));

		channel.writeInbound(content);

		Object t = channel.readInbound();
		assertThat(t).isInstanceOf(HttpResponse.class);
		assertThat(t).isNotInstanceOf(HttpContent.class);

		t = channel.readInbound();
		assertThat(t).isInstanceOf(Buffer.class);
		try (Buffer b = (Buffer) t) {
			assertThat(b.readCharSequence(b.readableBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"some\": 1}");
		}

		t = channel.readInbound();
		assertThat(t).isInstanceOf(Buffer.class);
		try (Buffer b = (Buffer) t) {
			assertThat(b.readCharSequence(b.readableBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"value\": true, \"test\": 1}");
		}

		t = channel.readInbound();
		assertThat(t).isInstanceOf(EmptyLastHttpContent.class);
		((LastHttpContent) t).close();

		t = channel.readInbound();
		assertThat(t).isNull();
	}

	@Test
	void testPath() {
		TestHttpInfos infos = new TestHttpInfos();
		Flux<String> expectations = Flux.just("", "", "", "/", "a", "a", "", "a", "", "a", "a", "a b", "a");

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
						"http://localhost:8080/a%20b",
						"http://localhost:8080/a?b={}"));

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
						"localhost:8080/a%20b",
						"localhost:8080/a?b={}"));

		doTestPath(infos, expectations,
				Flux.just("", "/", "//", "///", "/a", "/a/", "/?b", "/a?b", "/#b", "/a#b", "/a?b#c", "/a%20b", "/a?b={}"));
	}

	private void doTestPath(TestHttpInfos infos, Flux<String> expectations, Flux<String> uris) {
		uris.zipWith(expectations)
		            .doOnNext(tuple -> {
		                infos.uri = tuple.getT1();
		                assertThat(tuple.getT2()).isEqualTo(infos.path());
		            })
		            .blockLast();
	}

	@Test
	void testFullPath() {
		assertThat(HttpOperations.resolvePath("http://localhost:8080")).isEqualTo("");
		assertThat(HttpOperations.resolvePath("http://localhost:8080/")).isEqualTo("/");
		assertThat(HttpOperations.resolvePath("http://localhost:8080//")).isEqualTo("//");
		assertThat(HttpOperations.resolvePath("http://localhost:8080///")).isEqualTo("///");
		assertThat(HttpOperations.resolvePath("http://localhost:8080/a")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("http://localhost:8080/a/")).isEqualTo("/a/");
		assertThat(HttpOperations.resolvePath("http://localhost:8080/?b")).isEqualTo("/");
		assertThat(HttpOperations.resolvePath("http://localhost:8080/a?b")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("http://localhost:8080/#b")).isEqualTo("/");
		assertThat(HttpOperations.resolvePath("http://localhost:8080/a#b")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("http://localhost:8080/a?b#c")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("http://localhost:8080/a%20b")).isEqualTo("/a b");
		assertThat(HttpOperations.resolvePath("http://localhost:8080/a?b={}")).isEqualTo("/a");

		assertThat(HttpOperations.resolvePath("localhost:8080")).isEqualTo("");
		assertThat(HttpOperations.resolvePath("localhost:8080/")).isEqualTo("/");
		assertThat(HttpOperations.resolvePath("localhost:8080//")).isEqualTo("//");
		assertThat(HttpOperations.resolvePath("localhost:8080///")).isEqualTo("///");
		assertThat(HttpOperations.resolvePath("localhost:8080/a")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("localhost:8080/a/")).isEqualTo("/a/");
		assertThat(HttpOperations.resolvePath("localhost:8080/?b")).isEqualTo("/");
		assertThat(HttpOperations.resolvePath("localhost:8080/a?b")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("localhost:8080/#b")).isEqualTo("/");
		assertThat(HttpOperations.resolvePath("localhost:8080/a#b")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("localhost:8080/a?b#c")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("localhost:8080/a%20b")).isEqualTo("/a b");
		assertThat(HttpOperations.resolvePath("localhost:8080/a?b={}")).isEqualTo("/a");

		assertThat(HttpOperations.resolvePath("")).isEqualTo("");
		assertThat(HttpOperations.resolvePath("/")).isEqualTo("/");
		assertThat(HttpOperations.resolvePath("//")).isEqualTo("//");
		assertThat(HttpOperations.resolvePath("///")).isEqualTo("///");
		assertThat(HttpOperations.resolvePath("/a")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("/a/")).isEqualTo("/a/");
		assertThat(HttpOperations.resolvePath("/?b")).isEqualTo("/");
		assertThat(HttpOperations.resolvePath("/a?b")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("/#b")).isEqualTo("/");
		assertThat(HttpOperations.resolvePath("/a#b")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("/a?b#c")).isEqualTo("/a");
		assertThat(HttpOperations.resolvePath("/a%20b")).isEqualTo("/a b");
		assertThat(HttpOperations.resolvePath("/a?b={}")).isEqualTo("/a");
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
		public String requestId() {
			return "";
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
