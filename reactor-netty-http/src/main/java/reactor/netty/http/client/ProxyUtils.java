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

import reactor.netty.transport.ProxyProvider;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Properties;

public class ProxyUtils {

	static final String HTTP_PROXY_HOST = "http.proxyHost";
	static final String HTTP_PROXY_PORT = "http.proxyPort";
	static final String HTTPS_PROXY_HOST = "https.proxyHost";
	static final String HTTPS_PROXY_PORT = "https.proxyPort";
	static final String HTTP_NON_PROXY_HOSTS = "http.nonProxyHosts";
	static final String DEFAULT_NON_PROXY_HOSTS = "localhost|127.*|[::1]";

	static final String SOCKS_PROXY_HOST = "socksProxyHost";
	static final String SOCKS_PROXY_PORT = "socksProxyPort";
	static final String SOCKS_VERSION = "socksProxyVersion";
	static final String SOCKS_VERSION_5 = "5";
	static final String SOCKS_USERNAME = "java.net.socks.username";
	static final String SOCKS_PASSWORD = "java.net.socks.password";


	static ProxyProvider httpProxyFrom(Properties properties) {
		String hostname = properties.getProperty(HTTPS_PROXY_HOST);
		if (hostname == null) {
			hostname = properties.getProperty(HTTP_PROXY_HOST);
		}
		int port = properties.containsKey(HTTPS_PROXY_HOST) ? 443 : 80;
		if (properties.containsKey(HTTPS_PROXY_PORT)) {
			port = Integer.parseInt(properties.getProperty(HTTPS_PROXY_PORT));
		}
		else if (properties.containsKey(HTTP_PROXY_PORT)) {
			port = Integer.parseInt(properties.getProperty(HTTP_PROXY_PORT));
		}
		String nonProxyHosts = properties.getProperty(HTTP_NON_PROXY_HOSTS, DEFAULT_NON_PROXY_HOSTS);

		return ProxyProvider.builder()
				.type(ProxyProvider.Proxy.HTTP)
				.host(hostname)
				.port(port)
				.nonProxyHosts(nonProxyHosts)
				.build();
	}

	static ProxyProvider socksProxyFrom(Properties properties) {
		String hostname = properties.getProperty(SOCKS_PROXY_HOST);
		ProxyProvider.Proxy type = ProxyProvider.Proxy.SOCKS5;
		if (properties.containsKey(SOCKS_VERSION)) {
			type = SOCKS_VERSION_5.equals(properties.getProperty(SOCKS_VERSION))
					? ProxyProvider.Proxy.SOCKS5
					: ProxyProvider.Proxy.SOCKS4;
		}
		int port = Integer.parseInt(properties.getProperty(SOCKS_PROXY_PORT, "1080"));

		ProxyProvider.Builder proxy = ProxyProvider.builder()
				.type(type)
				.host(hostname)
				.port(port);

		if (properties.containsKey(SOCKS_USERNAME)) {
			proxy = proxy.username(properties.getProperty(SOCKS_USERNAME));
		}
		if (properties.containsKey(SOCKS_PASSWORD)) {
			proxy = proxy.password(u -> properties.getProperty(SOCKS_PASSWORD));
		}

		return proxy.build();
	}

	@Nullable
	static ProxyProvider findProvider(Properties properties) {
		Objects.requireNonNull(properties, "properties");

		if (properties.containsKey(HTTP_PROXY_HOST) || properties.containsKey(HTTPS_PROXY_HOST)) {
			return httpProxyFrom(properties);
		}
		if (properties.containsKey(SOCKS_PROXY_HOST)) {
			return socksProxyFrom(properties);
		}

		return null;
	}
}