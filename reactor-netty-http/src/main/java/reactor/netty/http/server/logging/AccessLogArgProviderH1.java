/*
 * Copyright (c) 2020-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server.logging;

import io.netty.handler.codec.http.HttpResponse;
import reactor.netty.http.server.HttpServerRequest;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.util.Objects;

/**
 * A provider of the args required for access log of HTTP/1.1.
 *
 * @author limaoning
 */
final class AccessLogArgProviderH1 extends AbstractAccessLogArgProvider<AccessLogArgProviderH1> {

	HttpServerRequest request;
	HttpResponse response;

	AccessLogArgProviderH1(@Nullable SocketAddress remoteAddress) {
		super(remoteAddress);
	}

	AccessLogArgProviderH1(AccessLogArgProviderH1 copy) {
		super(copy);
		this.request = copy.request;
		this.response = copy.response;
	}

	AccessLogArgProviderH1 request(HttpServerRequest request) {
		this.request = Objects.requireNonNull(request, "request");
		onRequest();
		return get();
	}

	AccessLogArgProviderH1 response(HttpResponse response) {
		this.response = Objects.requireNonNull(response, "response");
		return get();
	}

	@Override
	@Nullable
	public CharSequence status() {
		return response == null ? null : response.status().codeAsText();
	}

	@Override
	@Nullable
	public CharSequence requestHeader(CharSequence name) {
		Objects.requireNonNull(name, "name");
		return request == null ? null : request.requestHeaders().get(name);
	}

	@Override
	@Nullable
	public CharSequence responseHeader(CharSequence name) {
		Objects.requireNonNull(name, "name");
		return response == null ? null : response.headers().get(name);
	}

	@Override
	void onRequest() {
		if (request != null) {
			this.accessDateTime = request.timestamp();
			this.zonedDateTime = accessDateTime.format(DATE_TIME_FORMATTER);
			this.startTime = accessDateTime.toInstant().toEpochMilli();
			super.method = request.method().name();
			super.uri = request.uri();
			super.protocol = request.protocol();
			super.cookies = request.cookies();
			super.connectionInfo = request;
		}
	}

	@Override
	void clear() {
		super.clear();
		this.request = null;
		this.response = null;
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
