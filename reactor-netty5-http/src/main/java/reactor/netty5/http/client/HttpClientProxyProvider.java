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
import io.netty5.handler.codec.http.headers.HttpHeaders;
import reactor.netty5.transport.ProxyProvider;
import reactor.util.annotation.Nullable;

import java.util.Objects;
import java.util.Properties;
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

	static final String HTTP_PROXY_HOST = "http.proxyHost";
	static final String HTTP_PROXY_PORT = "http.proxyPort";
	static final String HTTP_PROXY_USER = "http.proxyUser";
	static final String HTTP_PROXY_PASSWORD = "http.proxyPassword";
	static final String HTTPS_PROXY_HOST = "https.proxyHost";
	static final String HTTPS_PROXY_PORT = "https.proxyPort";
	static final String HTTPS_PROXY_USER = "https.proxyUser";
	static final String HTTPS_PROXY_PASSWORD = "https.proxyPassword";
	static final String HTTP_NON_PROXY_HOSTS = "http.nonProxyHosts";
	static final String DEFAULT_NON_PROXY_HOSTS = "localhost|127.*|[::1]";

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

	@Nullable
	static HttpClientProxyProvider createFrom(Properties properties) {
		Objects.requireNonNull(properties, "properties");

		if (properties.containsKey(HTTP_PROXY_HOST) || properties.containsKey(HTTPS_PROXY_HOST)) {
			return createHttpProxyFrom(properties);
		}

		return null;
	}

	/*
		assumes properties has either http.proxyHost or https.proxyHost
	 */
	static HttpClientProxyProvider createHttpProxyFrom(Properties properties) {
		String hostProperty;
		String portProperty;
		String userProperty;
		String passwordProperty;
		String defaultPort;
		if (properties.containsKey(HTTPS_PROXY_HOST)) {
			hostProperty = HTTPS_PROXY_HOST;
			portProperty = HTTPS_PROXY_PORT;
			userProperty = HTTPS_PROXY_USER;
			passwordProperty = HTTPS_PROXY_PASSWORD;
			defaultPort = "443";
		}
		else {
			hostProperty = HTTP_PROXY_HOST;
			portProperty = HTTP_PROXY_PORT;
			userProperty = HTTP_PROXY_USER;
			passwordProperty = HTTP_PROXY_PASSWORD;
			defaultPort = "80";
		}

		String hostname = Objects.requireNonNull(properties.getProperty(hostProperty), hostProperty);
		int port = parsePort(properties.getProperty(portProperty, defaultPort), portProperty);

		String nonProxyHosts = properties.getProperty(HTTP_NON_PROXY_HOSTS, DEFAULT_NON_PROXY_HOSTS);
		RegexShouldProxyPredicate transformedNonProxyHosts = RegexShouldProxyPredicate.fromWildcardedPattern(nonProxyHosts);

		Build proxy = new Build()
				.type(Proxy.HTTP)
				.host(hostname)
				.port(port)
				.nonProxyHostsPredicate(transformedNonProxyHosts);

		if (properties.containsKey(userProperty)) {
			proxy = proxy.username(properties.getProperty(userProperty));

			if (properties.containsKey(passwordProperty)) {
				proxy = proxy.password(u -> properties.getProperty(passwordProperty));
			}
			else {
				throw new NullPointerException("Proxy username is set via '" + userProperty + "', but '" + passwordProperty + "' is not set.");
			}
		}

		return proxy.build();
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
				this.httpHeaders = () -> {
					HttpHeaders newHeaders = HttpHeaders.newHeaders();
					headers.accept(newHeaders);
					return newHeaders;
				};
			}
			return get();
		}
	}
}
