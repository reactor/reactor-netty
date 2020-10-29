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
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;

/**
 * @author limaoning
 */
final class AccessLogArgProviderH1 extends AbstractAccessLogArgProvider {

	HttpRequest request;
	HttpResponse response;
	boolean chunked;

	AccessLogArgProviderH1 channel(SocketChannel channel) {
		super.channel = channel;
		return this;
	}

	AccessLogArgProviderH1 request(HttpRequest request) {
		this.request = request;
		return this;
	}

	AccessLogArgProviderH1 response(HttpResponse response) {
		this.response = response;
		this.chunked = HttpUtil.isTransferEncodingChunked(response);
		super.contentLength = HttpUtil.getContentLength(response, -1);
		return this;
	}

	@Override
	public CharSequence method() {
		return request.method().name();
	}

	@Override
	public CharSequence uri() {
		return request.uri();
	}

	@Override
	public String protocol() {
		return request.protocolVersion().text();
	}

	@Override
	public CharSequence status() {
		return response.status().codeAsText();
	}

	@Override
	void increaseContentLength(long contentLength) {
		if (chunked) {
			super.contentLength += contentLength;
		}
	}

	@Override
	public CharSequence header(CharSequence name) {
		return request.headers().get(name);
	}

}
