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

package reactor.netty.http.client;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.bootstrap.Bootstrap;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import reactor.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class HttpClientCookie extends HttpClientOperator
		implements Function<Bootstrap, Bootstrap> {

	final Consumer<? super Cookie> cookie;
	final String name;
	final Cookie readyCookie;

	HttpClientCookie(HttpClient client, String name, Consumer<? super Cookie> cookie) {
		super(client);
		this.name = Objects.requireNonNull(name, "name");
		this.cookie = Objects.requireNonNull(cookie, "cookie");
		this.readyCookie = null;
	}

	HttpClientCookie(HttpClient client, Cookie cookie) {
		super(client);
		this.readyCookie = Objects.requireNonNull(cookie, "cookie");
		this.name = null;
		this.cookie = null;
	}

	@Override
	protected TcpClient tcpConfiguration() {
		return source.tcpConfiguration().bootstrap(this);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Bootstrap apply(Bootstrap bootstrap) {
		HttpHeaders h = HttpClientConfiguration.headers(bootstrap);
		if (h == null) {
			h = new DefaultHttpHeaders();
		}

		Cookie c;

		if (readyCookie == null) {
			c = new DefaultCookie(name, "");
			cookie.accept(c);
		}
		else {
			c = readyCookie;
		}

		if (!c.value().isEmpty()) {
			h.add(HttpHeaderNames.COOKIE, HttpClientConfiguration.getOrCreate(bootstrap).cookieEncoder.encode(c));
			HttpClientConfiguration.headers(bootstrap, h);
		}
		return bootstrap;
	}
}
