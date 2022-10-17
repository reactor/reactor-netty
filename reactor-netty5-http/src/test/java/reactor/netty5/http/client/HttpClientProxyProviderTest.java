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
import org.junit.jupiter.api.Test;
import reactor.netty5.transport.ProxyProvider;

import java.net.InetSocketAddress;
import java.util.Properties;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static reactor.netty5.http.client.HttpClientProxyProvider.HTTPS_PROXY_HOST;
import static reactor.netty5.http.client.HttpClientProxyProvider.HTTPS_PROXY_PASSWORD;
import static reactor.netty5.http.client.HttpClientProxyProvider.HTTPS_PROXY_PORT;
import static reactor.netty5.http.client.HttpClientProxyProvider.HTTPS_PROXY_USER;
import static reactor.netty5.http.client.HttpClientProxyProvider.HTTP_NON_PROXY_HOSTS;
import static reactor.netty5.http.client.HttpClientProxyProvider.HTTP_PROXY_HOST;
import static reactor.netty5.http.client.HttpClientProxyProvider.HTTP_PROXY_PASSWORD;
import static reactor.netty5.http.client.HttpClientProxyProvider.HTTP_PROXY_PORT;
import static reactor.netty5.http.client.HttpClientProxyProvider.HTTP_PROXY_USER;
import static reactor.netty5.http.client.HttpClientProxyProvider.createFrom;

class HttpClientProxyProviderTest {

	private static final InetSocketAddress ADDRESS_1 = InetSocketAddress.createUnresolved("localhost", 80);
	private static final String DEFAULT_NON_PROXY_HOSTS_TRANSFORMED_TO_REGEX = "\\Qlocalhost\\E|\\Q127.\\E.*|\\Q[::1]\\E";
	@SuppressWarnings("UnnecessaryLambda")
	private static final Consumer<HttpHeaders> HEADER_1 = list -> list.add("Authorization", "Bearer 123");
	@SuppressWarnings("UnnecessaryLambda")
	private static final Consumer<HttpHeaders> HEADER_2 = list -> list.add("Authorization", "Bearer 456");

	@Test
	void differentAuthHeaders() {
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1)).isNotEqualTo(createHeaderProxy(ADDRESS_1, HEADER_2));
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode()).isNotEqualTo(createHeaderProxy(ADDRESS_1, HEADER_2).hashCode());
	}

	@Test
	void equalProxyProvidersAuthHeader() {
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1)).isEqualTo(createHeaderProxy(ADDRESS_1, HEADER_1));
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode()).isEqualTo(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode());
	}

	@Test
	void proxyFromSystemProperties_basicAuthSetFromHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(HTTP_PROXY_HOST, "host");
		properties.setProperty(HTTP_PROXY_USER, "user");
		properties.setProperty(HTTP_PROXY_PASSWORD, "password");

		ProxyProvider provider = createFrom(properties);
		assertThat(provider).isNotNull();

		ProxyHandler handler = provider.newProxyHandler();
		assertThat(handler.getClass()).isEqualTo(HttpProxyHandler.class);

		HttpProxyHandler httpHandler = (HttpProxyHandler) handler;
		assertThat(httpHandler.username()).isEqualTo("user");
		assertThat(httpHandler.password()).isEqualTo("password");
	}

	@Test
	void proxyFromSystemProperties_basicAuthSetFromHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(HTTPS_PROXY_HOST, "host");
		properties.setProperty(HTTPS_PROXY_USER, "user");
		properties.setProperty(HTTPS_PROXY_PASSWORD, "password");

		ProxyProvider provider = createFrom(properties);
		assertThat(provider).isNotNull();

		ProxyHandler handler = provider.newProxyHandler();
		assertThat(handler.getClass()).isEqualTo(HttpProxyHandler.class);

		HttpProxyHandler httpHandler = (HttpProxyHandler) handler;
		assertThat(httpHandler.username()).isEqualTo("user");
		assertThat(httpHandler.password()).isEqualTo("password");
	}

	@Test
	void proxyFromSystemProperties_customNonProxyHostsSetForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(HTTP_PROXY_HOST, "host");
		properties.setProperty(HTTP_NON_PROXY_HOSTS, "non-host");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo("\\Qnon-host\\E");
	}

	@Test
	void proxyFromSystemProperties_customNonProxyHostsSetForHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(HTTPS_PROXY_HOST, "host");
		properties.setProperty(HTTP_NON_PROXY_HOSTS, "non-host");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo("\\Qnon-host\\E");
	}

	@Test
	void proxyFromSystemProperties_customNonProxyHostsWithWildcardSetForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(HTTP_PROXY_HOST, "host");
		properties.setProperty(HTTP_NON_PROXY_HOSTS, "*.non-host");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo(".*\\Q.non-host\\E");
	}

	@Test
	void proxyFromSystemProperties_customNonProxyHostsWithWildcardSetForHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(HTTPS_PROXY_HOST, "host");
		properties.setProperty(HTTP_NON_PROXY_HOSTS, "*.non-host");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo(".*\\Q.non-host\\E");
	}

	@Test
	void proxyFromSystemProperties_defaultNonHttpHostsSetForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(HTTP_PROXY_HOST, "host");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo(DEFAULT_NON_PROXY_HOSTS_TRANSFORMED_TO_REGEX);
	}

	@Test
	void proxyFromSystemProperties_defaultNonHttpHostsSetForHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(HTTPS_PROXY_HOST, "host");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo(DEFAULT_NON_PROXY_HOSTS_TRANSFORMED_TO_REGEX);
	}

	@Test
	void proxyFromSystemProperties_errorWhenHttpPortIsEmptyString() {
		Properties properties = new Properties();
		properties.setProperty(HTTP_PROXY_HOST, "host");
		properties.setProperty(HTTP_PROXY_PORT, "");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> createFrom(properties))
				.withMessage("expected system property http.proxyPort to be a number but got empty string");
	}

	@Test
	void proxyFromSystemProperties_errorWhenHttpPortIsNotANumber() {
		Properties properties = new Properties();
		properties.setProperty(HTTP_PROXY_HOST, "host");
		properties.setProperty(HTTP_PROXY_PORT, "8080Hello");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> createFrom(properties))
				.withMessage("expected system property http.proxyPort to be a number but got 8080Hello");
	}

	@Test
	void proxyFromSystemProperties_errorWhenHttpsPortIsEmptyString() {
		Properties properties = new Properties();
		properties.setProperty(HTTPS_PROXY_HOST, "host");
		properties.setProperty(HTTPS_PROXY_PORT, "");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> createFrom(properties))
				.withMessage("expected system property https.proxyPort to be a number but got empty string");
	}

	@Test
	void proxyFromSystemProperties_errorWhenHttpsPortIsNotANumber() {
		Properties properties = new Properties();
		properties.setProperty(HTTPS_PROXY_HOST, "host");
		properties.setProperty(HTTPS_PROXY_PORT, "8080Hello");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> createFrom(properties))
				.withMessage("expected system property https.proxyPort to be a number but got 8080Hello");
	}

	@Test
	void proxyFromSystemProperties_httpsProxyOverHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(HTTPS_PROXY_HOST, "https");
		properties.setProperty(HTTP_PROXY_HOST, "http");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getHostString()).isEqualTo("https");
	}

	@Test
	void proxyFromSystemProperties_npeWhenHttpProxyUsernameIsSetButNotPassword() {
		Properties properties = new Properties();
		properties.setProperty(HTTP_PROXY_HOST, "host");
		properties.setProperty(HTTP_PROXY_USER, "user");

		Throwable throwable = catchThrowable(() -> createFrom(properties));
		assertThat(throwable)
				.isInstanceOf(NullPointerException.class)
				.hasMessage("Proxy username is set via 'http.proxyUser', but 'http.proxyPassword' is not set.");
	}

	@Test
	void proxyFromSystemProperties_npeWhenHttpsProxyUsernameIsSetButNotPassword() {
		Properties properties = new Properties();
		properties.setProperty(HTTPS_PROXY_HOST, "host");
		properties.setProperty(HTTPS_PROXY_USER, "user");

		Throwable throwable = catchThrowable(() -> createFrom(properties));
		assertThat(throwable)
				.isInstanceOf(NullPointerException.class)
				.hasMessage("Proxy username is set via 'https.proxyUser', but 'https.proxyPassword' is not set.");
	}

	@Test
	void proxyFromSystemProperties_parseHttpPortFromSystemProperties() {
		Properties properties = new Properties();
		properties.setProperty(HTTP_PROXY_HOST, "host");
		properties.setProperty(HTTP_PROXY_PORT, "8080");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(8080);
	}

	@Test
	void proxyFromSystemProperties_parseHttpsPortFromSystemProperties() {
		Properties properties = new Properties();
		properties.setProperty(HTTPS_PROXY_HOST, "host");
		properties.setProperty(HTTPS_PROXY_PORT, "8443");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(8443);
	}

	@Test
	void proxyFromSystemProperties_port80SetByDefaultForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(HTTP_PROXY_HOST, "host");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(80);
	}

	@Test
	void proxyFromSystemProperties_port443SetByDefaultForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(HTTPS_PROXY_HOST, "host");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(443);
	}

	@Test
	void proxyFromSystemProperties_proxyProviderIsNotNullWhenHttpHostSet() {
		Properties properties = new Properties();
		properties.setProperty(HTTP_PROXY_HOST, "host");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
		assertThat(provider.getAddress().get().getHostString()).isEqualTo("host");
	}

	@Test
	void proxyFromSystemProperties_proxySettingsIsNotNullWhenHttpSHostSet() {
		Properties properties = new Properties();
		properties.setProperty(HTTPS_PROXY_HOST, "host");

		ProxyProvider provider = createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
		assertThat(provider.getAddress().get().getHostString()).isEqualTo("host");
	}

	private ProxyProvider createHeaderProxy(InetSocketAddress address, Consumer<HttpHeaders> authHeader) {
		return new HttpClientProxyProvider.Build()
				.type(ProxyProvider.Proxy.HTTP)
				.address(address)
				.httpHeaders(authHeader)
				.build();
	}
}
