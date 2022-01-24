/*
 * Copyright (c) 2019-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.transport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

class ProxyProviderTest {

	@SuppressWarnings("UnnecessaryLambda")
	private static final Function<String, String> PASSWORD_1 = username -> "123";
	@SuppressWarnings("UnnecessaryLambda")
	private static final Function<String, String> PASSWORD_2 = username -> "456";

	private static final String NON_PROXY_HOSTS = "localhost";

	private static final InetSocketAddress ADDRESS_1 = InetSocketAddress.createUnresolved("localhost", 80);
	private static final InetSocketAddress ADDRESS_2 = InetSocketAddress.createUnresolved("example.com", 80);

	@SuppressWarnings("UnnecessaryLambda")
	private static final Consumer<HttpHeaders> HEADER_1 = list -> list.add("Authorization", "Bearer 123");
	@SuppressWarnings("UnnecessaryLambda")
	private static final Consumer<HttpHeaders> HEADER_2 = list -> list.add("Authorization", "Bearer 456");

	private static final long CONNECT_TIMEOUT_1 = 100;
	private static final long CONNECT_TIMEOUT_2 = 200;

	private static final String DEFAULT_NON_PROXY_HOSTS_TRANSFORMED_TO_REGEX = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern(ProxyProvider.DEFAULT_NON_PROXY_HOSTS).toString();

	@Test
	void equalProxyProviders() {
		assertThat(createProxy(ADDRESS_1, PASSWORD_1)).isEqualTo(createProxy(ADDRESS_1, PASSWORD_1));
		assertThat(createProxy(ADDRESS_1, PASSWORD_1).hashCode()).isEqualTo(createProxy(ADDRESS_1, PASSWORD_1).hashCode());
	}

	@Test
	void equalProxyProvidersNoAuth() {
		assertThat(createNoAuthProxy(ADDRESS_1)).isEqualTo(createNoAuthProxy(ADDRESS_1));
		assertThat(createNoAuthProxy(ADDRESS_1).hashCode()).isEqualTo(createNoAuthProxy(ADDRESS_1).hashCode());
	}

	@Test
	void equalProxyProvidersAuthHeader() {
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1)).isEqualTo(createHeaderProxy(ADDRESS_1, HEADER_1));
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode()).isEqualTo(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode());
	}

	@Test
	void differentAddresses() {
		assertThat(createProxy(ADDRESS_1, PASSWORD_1)).isNotEqualTo(createProxy(ADDRESS_2, PASSWORD_1));
		assertThat(createProxy(ADDRESS_1, PASSWORD_1).hashCode()).isNotEqualTo(createProxy(ADDRESS_2, PASSWORD_1).hashCode());
	}

	@Test
	void differentPasswords() {
		assertThat(createProxy(ADDRESS_1, PASSWORD_1)).isNotEqualTo(createProxy(ADDRESS_1, PASSWORD_2));
		assertThat(createProxy(ADDRESS_1, PASSWORD_1).hashCode()).isNotEqualTo(createProxy(ADDRESS_1, PASSWORD_2).hashCode());
	}

	@Test
	void differentAuthHeaders() {
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1)).isNotEqualTo(createHeaderProxy(ADDRESS_1, HEADER_2));
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode()).isNotEqualTo(createHeaderProxy(ADDRESS_1, HEADER_2).hashCode());
	}

	@Test
	void differentConnectTimeout() {
		assertThat(createConnectTimeoutProxy(CONNECT_TIMEOUT_1)).isNotEqualTo(createConnectTimeoutProxy(CONNECT_TIMEOUT_2));
		assertThat(createConnectTimeoutProxy(CONNECT_TIMEOUT_1).hashCode()).isNotEqualTo(createConnectTimeoutProxy(CONNECT_TIMEOUT_2).hashCode());
	}

	@Test
	void connectTimeoutWithNonPositiveValue() {
		assertThat(createConnectTimeoutProxy(0).newProxyHandler().connectTimeoutMillis()).isEqualTo(0);
		assertThat(createConnectTimeoutProxy(-1).newProxyHandler().connectTimeoutMillis()).isEqualTo(0);
	}

	@Test
	void connectTimeoutWithDefault() {
		ProxyProvider provider = ProxyProvider.builder()
		                                      .type(ProxyProvider.Proxy.SOCKS5)
		                                      .address(ADDRESS_1)
		                                      .build();
		assertThat(provider.connectTimeoutMillis).isEqualTo(10000);
	}

	private SocketAddress someAddress(String host) {
		return new InetSocketAddress(host, 8080);
	}

	@Test
	void nonProxyHosts_wildcardInitially() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("*.foo.com");
		assertThat(pred.test(someAddress("some.other.com"))).as("Should proxy, nothing matching foo").isFalse();
		assertThat(pred.test(someAddress("some.foo.com"))).as("Should not proxy, prefix in wildcard").isTrue();
	}

	@Test
	void nonProxyHosts_wildcardFinish() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("foo*");
		assertThat(pred.test(someAddress("other.foo.com"))).as("Should proxy, nothing matching prefix").isFalse();
		assertThat(pred.test(someAddress("foo.other.com"))).as("Should not proxy, anything in wildcard").isTrue();
		assertThat(pred.test(someAddress("foo."))).as("Should not proxy, nothing in wildcard").isTrue();
	}

	@Test
	void nonProxyHosts_wildcardBoth() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("*foo*");
		assertThat(pred.test(someAddress("some.foo.com"))).as("Should not proxy, contains foo").isTrue();
		assertThat(pred.test(someAddress("some.other.com"))).as("Should proxy, no foo").isFalse();
	}

	@Test
	void nonProxyHosts_wildcardNone() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("foo.com");
		assertThat(pred.test(someAddress("foo.com"))).as("Should not proxy, exact match").isTrue();
		assertThat(pred.test(someAddress("other.com"))).as("Should proxy, mismatches").isFalse();
	}

	@Test
	void nonProxyHosts_concatenated() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("exact.com|*first.com|last.com*|*both.com*");
		assertThat(pred.test(someAddress("exact.com"))).as("Should not proxy, has exact match").isTrue();
		assertThat(pred.test(someAddress("other.first.com"))).as("Should not proxy, matches a wildcarded prefix").isTrue();
		assertThat(pred.test(someAddress("last.com.net"))).as("Should not proxy, matches a wildcarded suffix").isTrue();
		assertThat(pred.test(someAddress("some.both.com.other"))).as("Should not proxy, matches wildcards").isTrue();
		assertThat(pred.test(someAddress("both.com.first.com"))).as("Should not proxy, matches many").isTrue();
	}

	@Test
	void nonProxyHosts_null_1() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern(null);
		assertThat(pred.test(someAddress("foo.com"))).as("Should proxy when nonProxyHosts is blanked out").isFalse();
	}

	@Test
	void nonProxyHosts_null_2() {
		assertThat(createNonProxyHostsProxy(null).nonProxyHostPredicate.test(someAddress(NON_PROXY_HOSTS)))
				.as("Should proxy when nonProxyHosts is blanked out")
				.isFalse();
	}

	@Test
	void nonProxyHosts_empty_1() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("");
		assertThat(pred.test(someAddress("foo.com"))).as("Should proxy when nonProxyHosts is blanked out").isFalse();
	}

	@Test
	void nonProxyHosts_empty_2() {
		assertThat(createNonProxyHostsProxy("").nonProxyHostPredicate.test(someAddress(NON_PROXY_HOSTS)))
				.as("Should proxy when nonProxyHosts is blanked out")
				.isFalse();
	}

	@Test
	void nonProxyHosts_javaDefault() {
		ProxyProvider.RegexShouldProxyPredicate defaultPredicate = ProxyProvider.RegexShouldProxyPredicate.DEFAULT_NON_PROXY;
		assertThat(defaultPredicate.test(someAddress("127.0.0.1"))).as("Should not proxy loopback").isTrue();
		assertThat(defaultPredicate.test(someAddress("127.0.0.2"))).as("Should not proxy loopback").isTrue();
		assertThat(defaultPredicate.test(someAddress("0.0.0.0"))).as("Should not proxy default").isTrue();
		assertThat(defaultPredicate.test(someAddress("localhost"))).as("Should not proxy localhost").isTrue();
	}

	@Test
	void nonProxyHosts_wildcardInTheMiddle() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("some.*.com");
		assertThat(pred.test(someAddress("some.other.com"))).as("Should proxy, nothing matching other").isFalse();
		assertThat(pred.test(someAddress("some.foo.com"))).as("Should proxy, nothing matching foo").isFalse();
	}

	@Test
	void nonProxyHosts_builderDefault_empty() {
		Predicate<SocketAddress> pred = ProxyProvider.builder().type(ProxyProvider.Proxy.HTTP).host("something").build().getNonProxyHostsPredicate();
		assertThat(pred.test(someAddress("localhost"))).as("Default should proxy").isFalse();
	}

	@Test
	void shouldNotCreateProxyProviderWithMissingRemoteHostInfo() {
		ProxyProvider.Build builder = (ProxyProvider.Build) ProxyProvider.builder().type(ProxyProvider.Proxy.HTTP);
		assertThatIllegalArgumentException()
				.isThrownBy(builder::build)
				.withMessage("Neither address nor host is specified");
	}

	@Test
	void proxyFromSystemProperties_nullProxyProviderIfNoHostnamePropertySet() {
		Properties properties = new Properties();
		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNull();
	}

	@Test
	void proxyFromSystemProperties_proxyProviderIsNotNullWhenHttpHostSet() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
		assertThat(provider.getAddress().get().getHostString()).isEqualTo("host");
	}

	@Test
	void proxyFromSystemProperties_port80SetByDefaultForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(80);
	}

	@Test
	void proxyFromSystemProperties_parseHttpPortFromSystemProperties() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTP_PROXY_PORT, "8080");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(8080);
	}

	@Test
	void proxyFromSystemProperties_proxySettingsIsNotNullWhenHttpSHostSet() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
		assertThat(provider.getAddress().get().getHostString()).isEqualTo("host");
	}

	@Test
	void proxyFromSystemProperties_port443SetByDefaultForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(443);
	}

	@Test
	void proxyFromSystemProperties_parseHttpsPortFromSystemProperties() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTPS_PROXY_PORT, "8443");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(8443);
	}

	@Test
	void proxyFromSystemProperties_defaultNonHttpHostsSetForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo(DEFAULT_NON_PROXY_HOSTS_TRANSFORMED_TO_REGEX);
	}

	@Test
	void proxyFromSystemProperties_defaultNonHttpHostsSetForHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo(DEFAULT_NON_PROXY_HOSTS_TRANSFORMED_TO_REGEX);
	}

	@Test
	void proxyFromSystemProperties_httpsProxyOverHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "https");
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "http");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getHostString()).isEqualTo("https");
	}

	@Test
	void proxyFromSystemProperties_customNonProxyHostsSetForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTP_NON_PROXY_HOSTS, "non-host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo("\\Qnon-host\\E");
	}

	@Test
	void proxyFromSystemProperties_customNonProxyHostsSetForHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTP_NON_PROXY_HOSTS, "non-host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo("\\Qnon-host\\E");
	}

	@Test
	void proxyFromSystemProperties_customNonProxyHostsWithWildcardSetForHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTP_NON_PROXY_HOSTS, "*.non-host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo(".*\\Q.non-host\\E");
	}

	@Test
	void proxyFromSystemProperties_customNonProxyHostsWithWildcardSetForHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTP_NON_PROXY_HOSTS, "*.non-host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getNonProxyHostsPredicate().toString()).isEqualTo(".*\\Q.non-host\\E");
	}

	@Test
	void proxyFromSystemProperties_basicAuthSetFromHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTP_PROXY_USER, "user");
		properties.setProperty(ProxyProvider.HTTP_PROXY_PASSWORD, "password");

		ProxyProvider provider = ProxyProvider.createFrom(properties);
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
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTPS_PROXY_USER, "user");
		properties.setProperty(ProxyProvider.HTTPS_PROXY_PASSWORD, "password");

		ProxyProvider provider = ProxyProvider.createFrom(properties);
		assertThat(provider).isNotNull();

		ProxyHandler handler = provider.newProxyHandler();
		assertThat(handler.getClass()).isEqualTo(HttpProxyHandler.class);

		HttpProxyHandler httpHandler = (HttpProxyHandler) handler;
		assertThat(httpHandler.username()).isEqualTo("user");
		assertThat(httpHandler.password()).isEqualTo("password");
	}

	@Test
	void proxyFromSystemProperties_npeWhenHttpProxyUsernameIsSetButNotPassword() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTP_PROXY_USER, "user");

		Throwable throwable = catchThrowable(() -> ProxyProvider.createFrom(properties));
		assertThat(throwable)
				.isInstanceOf(NullPointerException.class)
				.hasMessage("Proxy username is set via 'http.proxyUser', but 'http.proxyPassword' is not set.");
	}

	@Test
	void proxyFromSystemProperties_npeWhenHttpsProxyUsernameIsSetButNotPassword() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTPS_PROXY_USER, "user");

		Throwable throwable = catchThrowable(() -> ProxyProvider.createFrom(properties));
		assertThat(throwable)
				.isInstanceOf(NullPointerException.class)
				.hasMessage("Proxy username is set via 'https.proxyUser', but 'https.proxyPassword' is not set.");
	}

	@Test
	void proxyFromSystemProperties_socksProxy5SetWhenSocksSystemPropertySet() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.SOCKS5);
		assertThat(provider.getAddress().get().getHostString()).isEqualTo("host");
	}

	@Test
	void proxyFromSystemProperties_overrideSocks5VersionWithCustomProperty() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.SOCKS_VERSION, "5");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.SOCKS5);
	}

	@Test
	void proxyFromSystemProperties_overrideSocks4VersionWithCustomProperty() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.SOCKS_VERSION, "4");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.SOCKS4);
	}

	@Test
	void proxyFromSystemProperties_defaultSocksPort() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(1080);
	}

	@Test
	void proxyFromSystemProperties_overrideSocksPortWithCustomProperty() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.SOCKS_PROXY_PORT, "2080");

		ProxyProvider provider = ProxyProvider.createFrom(properties);

		assertThat(provider).isNotNull();
		assertThat(provider.getAddress().get().getPort()).isEqualTo(2080);
	}

	@Test
	void proxyFromSystemProperties_setUserPasswordInSockProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.SOCKS_USERNAME, "user");
		properties.setProperty(ProxyProvider.SOCKS_PASSWORD, "pwd");

		ProxyProvider provider = ProxyProvider.createFrom(properties);
		assertThat(provider).isNotNull();

		ProxyHandler handler = provider.newProxyHandler();
		assertThat(handler.getClass()).isEqualTo(Socks5ProxyHandler.class);

		Socks5ProxyHandler httpHandler = (Socks5ProxyHandler) handler;
		assertThat(httpHandler.username()).isEqualTo("user");
		assertThat(httpHandler.password()).isEqualTo("pwd");
	}

	@Test
	void proxyFromSystemProperties_errorWhenHttpPortIsEmptyString() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTP_PROXY_PORT, "");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> ProxyProvider.createFrom(properties))
				.withMessage("expected system property http.proxyPort to be a number but got empty string");
	}

	@Test
	void proxyFromSystemProperties_errorWhenHttpsPortIsEmptyString() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTPS_PROXY_PORT, "");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> ProxyProvider.createFrom(properties))
				.withMessage("expected system property https.proxyPort to be a number but got empty string");
	}

	@Test
	void proxyFromSystemProperties_errorWhenSocksPortIsEmptyString() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.SOCKS_PROXY_PORT, "");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> ProxyProvider.createFrom(properties))
				.withMessage("expected system property socksProxyPort to be a number but got empty string");
	}

	@Test
	void proxyFromSystemProperties_errorWhenHttpPortIsNotANumber() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTP_PROXY_PORT, "8080Hello");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> ProxyProvider.createFrom(properties))
				.withMessage("expected system property http.proxyPort to be a number but got 8080Hello");
	}

	@Test
	void proxyFromSystemProperties_errorWhenHttpsPortIsNotANumber() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.HTTPS_PROXY_PORT, "8080Hello");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> ProxyProvider.createFrom(properties))
				.withMessage("expected system property https.proxyPort to be a number but got 8080Hello");
	}

	@Test
	void proxyFromSystemProperties_errorWhenSocksPortIsNotANumber() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.SOCKS_PROXY_PORT, "8080Hello");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> ProxyProvider.createFrom(properties))
				.withMessage("expected system property socksProxyPort to be a number but got 8080Hello");
	}

	@Test
	void proxyFromSystemProperties_errorWhenSocksVersionInvalid() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.SOCKS_VERSION, "42");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> ProxyProvider.createFrom(properties))
				.withMessage("only socks versions 4 and 5 supported but got 42");
	}

	private ProxyProvider createProxy(InetSocketAddress address, Function<String, String> passwordFunc) {
		return ProxyProvider.builder()
		                    .type(ProxyProvider.Proxy.SOCKS5)
		                    .address(address)
		                    .username("netty")
		                    .password(passwordFunc)
		                    .nonProxyHosts(NON_PROXY_HOSTS)
		                    .build();
	}

	private ProxyProvider createNoAuthProxy(InetSocketAddress address) {
		return ProxyProvider.builder()
		                    .type(ProxyProvider.Proxy.SOCKS5)
		                    .address(address)
		                    .nonProxyHosts(NON_PROXY_HOSTS)
		                    .build();
	}

	private ProxyProvider createHeaderProxy(InetSocketAddress address, Consumer<HttpHeaders> authHeader) {
		return ProxyProvider.builder()
		                    .type(ProxyProvider.Proxy.HTTP)
		                    .address(address)
		                    .httpHeaders(authHeader)
		                    .build();
	}

	private ProxyProvider createConnectTimeoutProxy(long connectTimeoutMillis) {
		return ProxyProvider.builder()
		                    .type(ProxyProvider.Proxy.SOCKS5)
		                    .address(ADDRESS_1)
		                    .connectTimeoutMillis(connectTimeoutMillis)
		                    .build();
	}

	private ProxyProvider createNonProxyHostsProxy(String nonProxyHosts) {
		return ProxyProvider.builder()
		                    .type(ProxyProvider.Proxy.HTTP)
		                    .address(ADDRESS_1)
		                    .nonProxyHosts(NON_PROXY_HOSTS)
		                    .nonProxyHosts(nonProxyHosts)
		                    .build();
	}
}
