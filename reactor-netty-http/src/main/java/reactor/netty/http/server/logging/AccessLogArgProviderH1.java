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

import io.netty.handler.codec.http.HttpRequest;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.util.Objects;

/**
 * @author limaoning
 */
final class AccessLogArgProviderH1 extends AbstractAccessLogArgProvider<AccessLogArgProviderH1> {

	HttpRequest request;

	AccessLogArgProviderH1(@Nullable SocketAddress remoteAddress) {
		super(remoteAddress);
	}

	AccessLogArgProviderH1 request(HttpRequest request) {
		this.request = Objects.requireNonNull(request, "request");
		onRequest();
		return get();
	}

	@Override
	@Nullable
	public CharSequence requestHeader(CharSequence name) {
		Objects.requireNonNull(name, "name");
		return request == null ? null : request.headers().get(name);
	}

	@Override
	void onRequest() {
		super.onRequest();
		if (request != null) {
			super.method = request.method().name();
			super.uri = request.uri();
			super.protocol = request.protocolVersion().text();
		}
	}

	@Override
	void clear() {
		super.clear();
		this.request = null;
	}

	AccessLogArgProviderH1 contentLength(long contentLength) {
		super.contentLength = contentLength;
		return get();
	}

	@Override
	public AccessLogArgProviderH1 get() {
		return this;
	}

}
