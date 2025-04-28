/*
 * Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.server.logging;

import io.netty5.handler.codec.http2.Http2HeadersFrame;
import org.jspecify.annotations.Nullable;

import java.net.SocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * A provider of the args required for access log of HTTP/2.
 *
 * @author limaoning
 */
final class AccessLogArgProviderH2 extends AbstractAccessLogArgProvider<AccessLogArgProviderH2> {

	static final String H2_PROTOCOL_NAME = "HTTP/2.0";

	@Nullable Http2HeadersFrame requestHeaders;
	@Nullable Http2HeadersFrame responseHeaders;

	AccessLogArgProviderH2(@Nullable SocketAddress remoteAddress) {
		super(remoteAddress);
	}

	AccessLogArgProviderH2 requestHeaders(Http2HeadersFrame requestHeaders) {
		this.requestHeaders = Objects.requireNonNull(requestHeaders, "requestHeaders");
		onRequest();
		return get();
	}

	AccessLogArgProviderH2 responseHeaders(Http2HeadersFrame responseHeaders) {
		this.responseHeaders = Objects.requireNonNull(responseHeaders, "responseHeaders");
		return get();
	}

	@Override
	public @Nullable CharSequence status() {
		return responseHeaders == null ? null : responseHeaders.headers().status();
	}

	@Override
	public @Nullable CharSequence requestHeader(CharSequence name) {
		Objects.requireNonNull(name, "name");
		return requestHeaders == null ? null : requestHeaders.headers().get(name);
	}

	@Override
	public @Nullable CharSequence responseHeader(CharSequence name) {
		Objects.requireNonNull(name, "name");
		return responseHeaders == null ? null : responseHeaders.headers().get(name);
	}

	@Override
	public @Nullable Iterator<Map.Entry<CharSequence, CharSequence>> requestHeaderIterator() {
		return requestHeaders == null ? null : requestHeaders.headers().iterator();
	}

	@Override
	public @Nullable Iterator<Map.Entry<CharSequence, CharSequence>> responseHeaderIterator() {
		return responseHeaders == null ? null : responseHeaders.headers().iterator();
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
		this.responseHeaders = null;
	}

	@Override
	public AccessLogArgProviderH2 get() {
		return this;
	}

}
