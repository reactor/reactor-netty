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
package reactor.netty.http.server;

import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http2.Http2HeadersFrame;

/**
 * @author limaoning
 */
final class AccessLogArgProviderH2 extends AbstractAccessLogArgProvider {

	static final String H2_PROTOCOL_NAME = "HTTP/2.0";

	Http2HeadersFrame requestHeaders;
	Http2HeadersFrame responseHeaders;

	AccessLogArgProviderH2 channel(SocketChannel channel) {
		super.channel = channel;
		return this;
	}

	AccessLogArgProviderH2 requestHeaders(Http2HeadersFrame requestHeaders) {
		this.requestHeaders = requestHeaders;
		return this;
	}

	AccessLogArgProviderH2 responseHeaders(Http2HeadersFrame responseHeaders) {
		this.responseHeaders = responseHeaders;
		return this;
	}

	@Override
	public CharSequence method() {
		return requestHeaders.headers().method();
	}

	@Override
	public CharSequence uri() {
		return requestHeaders.headers().path();
	}

	@Override
	public String protocol() {
		return H2_PROTOCOL_NAME;
	}

	@Override
	public CharSequence status() {
		return responseHeaders.headers().status();
	}

	@Override
	void increaseContentLength(long contentLength) {
		super.contentLength += contentLength;
	}

	@Override
	public CharSequence header(CharSequence name) {
		return requestHeaders.headers().get(name);
	}

}
