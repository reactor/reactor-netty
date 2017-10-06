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

package reactor.ipc.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.util.CharsetUtil;
import org.junit.Test;
import reactor.ipc.netty.Connection;

import static org.hamcrest.CoreMatchers.*;
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
		assertThat(((ByteBuf) t).toString(CharsetUtil.UTF_8), is("{\"some\": 1}"));

		t = channel.readInbound();
		assertThat(t, instanceOf(ByteBuf.class));
		assertThat(((ByteBuf) t).toString(CharsetUtil.UTF_8),
				is("{\"value\": true, \"test\": 1}"));

		t = channel.readInbound();
		assertThat(t, is(LastHttpContent.EMPTY_LAST_CONTENT));

		t = channel.readInbound();
		assertThat(t, nullValue());
	}

}
