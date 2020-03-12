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
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class HttpClientHeadersWhen extends HttpClientOperator
		implements Function<Bootstrap, Bootstrap> {

	final Function<? super HttpHeaders, Mono<? extends HttpHeaders>> headers;

	HttpClientHeadersWhen(HttpClient client,
			Function<? super HttpHeaders, Mono<? extends HttpHeaders>> headers) {
		super(client);
		this.headers = Objects.requireNonNull(headers, "headers");
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
				conf -> headers.apply(getOrCreate(conf.headers))
				               .map(conf::headers));
		return bootstrap;
	}

	static HttpHeaders getOrCreate(@Nullable HttpHeaders h) {
		if (h == null) {
			return new DefaultHttpHeaders();
		}
		return h;
	}
}
