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

import io.netty.handler.codec.http2.Http2HeadersFrame;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.util.Objects;

/**
 * @author limaoning
 */
final class AccessLogArgProviderH2 extends AbstractAccessLogArgProvider<AccessLogArgProviderH2> {

	static final String H2_PROTOCOL_NAME = "HTTP/2.0";

	Http2HeadersFrame requestHeaders;

	AccessLogArgProviderH2(@Nullable SocketAddress remoteAddress) {
		super(remoteAddress);
	}

	AccessLogArgProviderH2 requestHeaders(Http2HeadersFrame requestHeaders) {
		this.requestHeaders = Objects.requireNonNull(requestHeaders, "requestHeaders");
		onRequest();
		return get();
	}

	@Override
	@Nullable
	public CharSequence requestHeader(CharSequence name) {
		Objects.requireNonNull(name, "name");
		return requestHeaders == null ? null : requestHeaders.headers().get(name);
	}

	@Override
	void onRequest() {
		super.onRequest();
		if (requestHeaders != null) {
			super.method = requestHeaders.headers().method();
			super.uri = requestHeaders.headers().path();
			super.protocol = H2_PROTOCOL_NAME;
		}
	}

	@Override
	void clear() {
		super.clear();
		this.requestHeaders = null;
	}

	@Override
	public AccessLogArgProviderH2 get() {
		return this;
	}

}
