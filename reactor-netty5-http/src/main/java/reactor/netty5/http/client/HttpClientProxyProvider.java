/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.client;

import io.netty.contrib.handler.proxy.HttpProxyHandler;
import io.netty.contrib.handler.proxy.ProxyHandler;
import io.netty5.handler.codec.http.DefaultHttpHeaders;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import reactor.netty5.transport.ProxyProvider;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Extended {@link ProxyProvider} that gives ability to configure the request headers for the http proxy.
 *
 * @author Violeta Georgieva
 * @since 2.0.0
 */
public final class HttpClientProxyProvider extends ProxyProvider {

	public interface Builder<T extends Builder<T>> extends ProxyProvider.Builder<T> {

		/**
		 * A consumer to add request headers for the http proxy.
		 *
		 * @param headers A consumer to add request headers for the http proxy.
		 * @return {@code this}
		 */
		T httpHeaders(Consumer<HttpHeaders> headers);
	}

	final Supplier<? extends HttpHeaders> httpHeaders;

	HttpClientProxyProvider(Build builder) {
		super(builder);
		this.httpHeaders = builder.httpHeaders;
	}

	@Override
	public ProxyHandler newProxyHandler() {
		if (getType() != Proxy.HTTP) {
			return super.newProxyHandler();
		}
		String username = getUsername();
		String password = getPasswordValue();
		ProxyHandler proxyHandler = username != null && password != null ?
				new HttpProxyHandler(getAddress().get(), username, password, this.httpHeaders.get()) :
				new HttpProxyHandler(getAddress().get(), this.httpHeaders.get());
		proxyHandler.setConnectTimeoutMillis(getConnectTimeoutMillis());
		return proxyHandler;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof HttpClientProxyProvider that)) {
			return false;
		}
		if (!super.equals(o)) {
			return false;
		}
		return Objects.equals(httpHeaders.get(), that.httpHeaders.get());
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), httpHeaders.get());
	}

	static final class Build extends AbstractBuild<Build> implements Builder<Build> {

		static final Supplier<? extends HttpHeaders> NO_HTTP_HEADERS = () -> null;

		Supplier<? extends HttpHeaders> httpHeaders = NO_HTTP_HEADERS;

		@Override
		public HttpClientProxyProvider build() {
			return new HttpClientProxyProvider(this);
		}

		@Override
		public Build get() {
			return this;
		}

		@Override
		public Build httpHeaders(Consumer<HttpHeaders> headers) {
			if (headers != null) {
				this.httpHeaders = () -> new DefaultHttpHeaders() {
					{
						headers.accept(this);
					}
				};
			}
			return get();
		}
	}
}
