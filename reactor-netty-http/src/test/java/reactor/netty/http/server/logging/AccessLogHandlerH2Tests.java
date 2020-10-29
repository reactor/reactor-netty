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
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Headers;
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
class AccessLogHandlerH2Tests {

	@Test
	void accessLogArgs() {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.pipeline().addLast(new AccessLogHandlerH2(
				args -> {
					assertAccessLogArgProvider(args, channel.remoteAddress());
					return AccessLog.create("{}={}", HEADER_CONNECTION_NAME,
							args.requestHeader(HEADER_CONNECTION_NAME));
				}));

		Http2Headers requestHeaders = new DefaultHttp2Headers();
		requestHeaders.method(HttpMethod.GET.name());
		requestHeaders.path(URI);
		requestHeaders.add(HEADER_CONNECTION_NAME, HEADER_CONNECTION_VALUE);
		channel.writeInbound(new DefaultHttp2HeadersFrame(requestHeaders));

		Http2Headers responseHeaders = new DefaultHttp2Headers();
		responseHeaders.status(HttpResponseStatus.OK.codeAsText());
		channel.writeOutbound(new DefaultHttp2HeadersFrame(responseHeaders));

		ByteBuf byteBuf = Unpooled.buffer(RESPONSE_CONTENT.length);
		byteBuf.writeBytes(RESPONSE_CONTENT);
		channel.writeOutbound(new DefaultHttp2DataFrame(byteBuf, true));
	}

	private void assertAccessLogArgProvider(AccessLogArgProvider args, SocketAddress remoteAddress) {
		assertThat(args.remoteAddress()).isEqualTo(remoteAddress);
		assertThat(args.user()).isEqualTo(AbstractAccessLogArgProvider.MISSING);
		assertThat(args.zonedDateTime()).isNotNull();
		assertThat(args.method()).isEqualTo(HttpMethod.GET.name());
		assertThat(args.uri()).isEqualTo(URI);
		assertThat(args.protocol()).isEqualTo(AccessLogArgProviderH2.H2_PROTOCOL_NAME);
		assertThat(args.status()).isEqualTo(HttpResponseStatus.OK.codeAsText());
		assertThat(args.contentLength()).isEqualTo(RESPONSE_CONTENT.length);
		assertThat(args.requestHeader(HEADER_CONNECTION_NAME)).isEqualTo(HEADER_CONNECTION_VALUE);
	}

}
