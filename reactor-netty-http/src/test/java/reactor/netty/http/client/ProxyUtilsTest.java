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

import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import org.junit.jupiter.api.Test;
import reactor.netty.transport.ProxyProvider;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;


class ProxyUtilsTest {

	@Test
	void nullProxyProviderIfNoHostnameDefined() {
		Properties properties = new Properties();
		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNull();
	}

	@Test
	void proxyProviderIsNotNullWhenHttpHostSet() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTP_PROXY_HOST, "host");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
		assertThat(provider.getAddress().get().getHostName()).isEqualTo("host");
	}

	@Test
	void port80SetByDefaultForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTP_PROXY_HOST, "host");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(80);
	}

	@Test
	void parseHttpPortFromSystemProperties() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTP_PROXY_HOST, "host");
		properties.setProperty(ProxyUtils.HTTP_PROXY_PORT, "8080");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(8080);
	}

	@Test
	void proxySettingsIsNotNullWhenHttpSHostSet() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTPS_PROXY_HOST, "host");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
		assertThat(provider.getAddress().get().getHostName()).isEqualTo("host");
	}

	@Test
	void port443SetByDefaultForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTPS_PROXY_HOST, "host");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(443);
	}

	@Test
	void parseHttpsPortFromSystemProperties() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTPS_PROXY_HOST, "host");
		properties.setProperty(ProxyUtils.HTTP_PROXY_PORT, "8443");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(8443);
	}

	@Test
	void defaultNonHttpHostsSetForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTP_PROXY_HOST, "host");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo(ProxyUtils.DEFAULT_NON_PROXY_HOSTS);
	}

	@Test
	void defaultNonHttpHostsSetForHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTPS_PROXY_HOST, "host");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo(ProxyUtils.DEFAULT_NON_PROXY_HOSTS);
	}

	@Test
	void customNonProxyHostsSetForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTP_PROXY_HOST, "host");
		properties.setProperty(ProxyUtils.HTTP_NON_PROXY_HOSTS, "non-host");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo("non-host");
	}

	@Test
	void customNonProxyHostsSetForHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTPS_PROXY_HOST, "host");
		properties.setProperty(ProxyUtils.HTTP_NON_PROXY_HOSTS, "non-host");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo("non-host");
	}

	@Test
	void socksProxy5SetWhenSocksSystemPropertySet() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.SOCKS_PROXY_HOST, "host");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getHostName()).isEqualTo("host");
		assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.SOCKS5);
	}

	@Test
	void overrideSocksVersionWithCustomProperty() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyUtils.SOCKS_VERSION, "4");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.SOCKS4);
	}

	@Test
	void overrideSocksPortWithCustomProperty() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyUtils.SOCKS_PROXY_PORT, "2080");

		ProxyProvider provider = ProxyUtils.findProvider(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(2080);
	}

	@Test
	void setUserPasswordInSockProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyUtils.SOCKS_USERNAME, "user");
		properties.setProperty(ProxyUtils.SOCKS_PASSWORD, "pwd");

		ProxyProvider provider = ProxyUtils.findProvider(properties);
		assertThat(provider).isNotNull();

		ProxyHandler handler = provider.newProxyHandler();
		assertThat(handler).isNotNull();
		assertThat(handler.getClass()).isEqualTo(Socks5ProxyHandler.class);

		Socks5ProxyHandler httpHandler = (Socks5ProxyHandler) handler;
		assertThat(httpHandler.username()).isEqualTo("user");
		assertThat(httpHandler.password()).isEqualTo("pwd");
	}

	@Test
	void createClientWithHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTP_PROXY_HOST, "host");

		HttpClient client = HttpClient.create().proxyWithSystemProperties(properties);
		HttpClientConfig configuration = client.configuration();

		assertThat(configuration).isNotNull();
		assertThat(configuration.hasProxy()).isTrue();
		assertThat(configuration.proxyProvider().getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
	}

	@Test
	void createClientWithHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.HTTPS_PROXY_HOST, "host");

		HttpClient client = HttpClient.create().proxyWithSystemProperties(properties);
		HttpClientConfig configuration = client.configuration();

		assertThat(configuration).isNotNull();
		assertThat(configuration.hasProxy()).isTrue();
		assertThat(configuration.proxyProvider().getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
	}

	@Test
	void createClientWithSock5Proxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.SOCKS_PROXY_HOST, "host");

		HttpClient client = HttpClient.create().proxyWithSystemProperties(properties);
		HttpClientConfig configuration = client.configuration();

		assertThat(configuration).isNotNull();
		assertThat(configuration.hasProxy()).isTrue();
		assertThat(configuration.proxyProvider().getType()).isEqualTo(ProxyProvider.Proxy.SOCKS5);
	}

	@Test
	void createClientWithSock4Proxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyUtils.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyUtils.SOCKS_VERSION, "4");

		HttpClient client = HttpClient.create().proxyWithSystemProperties(properties);
		HttpClientConfig configuration = client.configuration();

		assertThat(configuration).isNotNull();
		assertThat(configuration.hasProxy()).isTrue();
		assertThat(configuration.proxyProvider().getType()).isEqualTo(ProxyProvider.Proxy.SOCKS4);
	}

	@Test
	void createClientWithSystemProxyProvider() {
		HttpClient client = HttpClient.create()
				.proxyWithSystemProperties();

		assertThat(client).isNotNull();
	}
}