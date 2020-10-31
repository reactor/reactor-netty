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
package reactor.netty.http.server.logging;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.server.logging.LoggingTests.HEADER_CONNECTION_NAME;
import static reactor.netty.http.server.logging.LoggingTests.HEADER_CONNECTION_VALUE;
import static reactor.netty.http.server.logging.LoggingTests.RESPONSE_CONTENT;
import static reactor.netty.http.server.logging.LoggingTests.URI;

/**
 * @author limaoning
 */
class AccessLogHandlerH1Tests {

	@Test
	void responseNonChunked() {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.pipeline().addLast(new AccessLogHandlerH1(
				args -> {
					assertAccessLogArgProvider(args, channel.remoteAddress(), false);
					return AccessLog.create("{}={}", HEADER_CONNECTION_NAME,
							args.requestHeader(HEADER_CONNECTION_NAME));
				}));

		channel.writeInbound(newHttpRequest());

		channel.writeOutbound(newHttpResponse(false));

		channel.writeOutbound(new DefaultLastHttpContent());
	}

	@Test
	void responseChunked() {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.pipeline().addLast(new AccessLogHandlerH1(
				args -> {
					assertAccessLogArgProvider(args, channel.remoteAddress(), true);
					return AccessLog.create("{}={}", HEADER_CONNECTION_NAME,
							args.requestHeader(HEADER_CONNECTION_NAME));
				}));

		channel.writeInbound(newHttpRequest());

		channel.writeOutbound(newHttpResponse(true));

		ByteBuf byteBuf = Unpooled.buffer(RESPONSE_CONTENT.length);
		byteBuf.writeBytes(RESPONSE_CONTENT);
		channel.writeOutbound(byteBuf);

		channel.writeOutbound(new DefaultHttpContent(byteBuf));

		channel.writeOutbound(new DefaultLastHttpContent());
	}

	private HttpRequest newHttpRequest() {
		HttpHeaders requestHeaders = new DefaultHttpHeaders();
		requestHeaders.add(HEADER_CONNECTION_NAME, HEADER_CONNECTION_VALUE);
		return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, URI, requestHeaders);
	}

	private HttpResponse newHttpResponse(boolean chunked) {
		HttpHeaders responseHeaders = new DefaultHttpHeaders();
		if (chunked) {
			responseHeaders.add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		}
		else {
			responseHeaders.add(HttpHeaderNames.CONTENT_LENGTH, RESPONSE_CONTENT.length);
		}
		return new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, responseHeaders);
	}

	private void assertAccessLogArgProvider(AccessLogArgProvider args, SocketAddress remoteAddress, boolean chunked) {
		assertThat(args.remoteAddress()).isEqualTo(remoteAddress);
		assertThat(args.user()).isEqualTo(AbstractAccessLogArgProvider.MISSING);
		assertThat(args.zonedDateTime()).isNotNull();
		assertThat(args.method()).isEqualTo(HttpMethod.GET.name());
		assertThat(args.uri()).isEqualTo(URI);
		assertThat(args.protocol()).isEqualTo(HttpVersion.HTTP_1_1.text());
		assertThat(args.status()).isEqualTo(HttpResponseStatus.OK.codeAsText());
		if (chunked) {
			assertThat(args.contentLength()).isEqualTo(RESPONSE_CONTENT.length << 1);
		}
		else {
			assertThat(args.contentLength()).isEqualTo(RESPONSE_CONTENT.length);
		}
		assertThat(args.requestHeader(HEADER_CONNECTION_NAME)).isEqualTo(HEADER_CONNECTION_VALUE);
	}

}
