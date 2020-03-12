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
import java.util.function.Function;

import io.netty.bootstrap.Bootstrap;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import reactor.core.publisher.Mono;
import reactor.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class HttpClientCookieWhen extends HttpClientOperator
		implements Function<Bootstrap, Bootstrap> {

	final Function<? super Cookie, Mono<? extends Cookie>> cookie;
	final String name;

	HttpClientCookieWhen(HttpClient client,
			String name,
			Function<? super Cookie, Mono<? extends Cookie>> cookie) {
		super(client);
		this.cookie = Objects.requireNonNull(cookie, "cookie");
		this.name = Objects.requireNonNull(name, "name");
	}

	@Override
	protected TcpClient tcpConfiguration() {
		return source.tcpConfiguration()
		             .bootstrap(this);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Bootstrap apply(Bootstrap bootstrap) {
		HttpClientConfiguration.deferredConf(bootstrap,
				conf -> cookie.apply(new DefaultCookie(name, ""))
				               .map(c -> cookie(conf, c)));
		return bootstrap;
	}

	static HttpClientConfiguration cookie(HttpClientConfiguration conf, Cookie c) {
		if (!c.value().isEmpty()) {
			if (conf.headers == null) {
				conf.headers = new DefaultHttpHeaders();
			}
			conf.headers.add(HttpHeaderNames.COOKIE, conf.cookieEncoder.encode(c));
		}
		return conf;
	}
}
