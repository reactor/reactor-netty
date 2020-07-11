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

package reactor.netty.transport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ProxyProviderTest {

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

	@Test
	public void equalProxyProviders() {
		assertEquals(createProxy(ADDRESS_1, PASSWORD_1), createProxy(ADDRESS_1, PASSWORD_1));
		assertEquals(createProxy(ADDRESS_1, PASSWORD_1).hashCode(), createProxy(ADDRESS_1, PASSWORD_1).hashCode());
	}

	@Test
	public void equalProxyProvidersNoAuth() {
		assertEquals(createNoAuthProxy(ADDRESS_1), createNoAuthProxy(ADDRESS_1));
		assertEquals(createNoAuthProxy(ADDRESS_1).hashCode(), createNoAuthProxy(ADDRESS_1).hashCode());
	}

	@Test
	public void equalProxyProvidersAuthHeader() {
		assertEquals(createHeaderProxy(ADDRESS_1, HEADER_1), createHeaderProxy(ADDRESS_1, HEADER_1));
		assertEquals(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode(), createHeaderProxy(ADDRESS_1, HEADER_1).hashCode());
	}

	@Test
	public void differentAddresses() {
		assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1), createProxy(ADDRESS_2, PASSWORD_1));
		assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1).hashCode(), createProxy(ADDRESS_2, PASSWORD_1).hashCode());
	}

	@Test
	public void differentPasswords() {
		assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1), createProxy(ADDRESS_1, PASSWORD_2));
		assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1).hashCode(), createProxy(ADDRESS_1, PASSWORD_2).hashCode());
	}

	@Test
	public void differentAuthHeaders() {
		assertNotEquals(createHeaderProxy(ADDRESS_1, HEADER_1), createHeaderProxy(ADDRESS_1, HEADER_2));
		assertNotEquals(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode(), createHeaderProxy(ADDRESS_1, HEADER_2).hashCode());
	}

	@Test
	public void differentConnectTimeout() {
		assertNotEquals(createConnectTimeoutProxy(CONNECT_TIMEOUT_1), createConnectTimeoutProxy(CONNECT_TIMEOUT_2));
		assertNotEquals(createConnectTimeoutProxy(CONNECT_TIMEOUT_1).hashCode(), createConnectTimeoutProxy(CONNECT_TIMEOUT_2).hashCode());
	}

	@Test
	public void connectTimeoutWithNonPositiveValue() {
		assertEquals(0, createConnectTimeoutProxy(0).newProxyHandler().connectTimeoutMillis());
		assertEquals(0, createConnectTimeoutProxy(-1).newProxyHandler().connectTimeoutMillis());
	}

	@Test
	public void connectTimeoutWithDefault() {
		ProxyProvider provider = ProxyProvider.builder()
		                                      .type(ProxyProvider.Proxy.SOCKS5)
		                                      .address(ADDRESS_1)
		                                      .build();
		assertEquals(10000, provider.connectTimeoutMillis);
	}

	private SocketAddress someAddress(String host) {
		return new InetSocketAddress(host, 8080);
	}

	@Test
	public void nonProxyHosts_wildcardInitially() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("*.foo.com");
		assertFalse("Should proxy, nothing matching foo", pred.test(someAddress("some.other.com")));
		assertTrue("Should not proxy, prefix in wildcard", pred.test(someAddress("some.foo.com")));
	}

	@Test
	public void nonProxyHosts_wildcardFinish() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("foo*");
		assertFalse("Should proxy, nothing matching prefix", pred.test(someAddress("other.foo.com")));
		assertTrue("Should not proxy, anything in wildcard", pred.test(someAddress("foo.other.com")));
		assertTrue("Should proxy, nothing in wildcard", pred.test(someAddress("foo.")));
	}

	@Test
	public void nonProxyHosts_wildcardBoth() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("*foo*");
		assertTrue("Should not proxy, contains foo", pred.test(someAddress("some.foo.com")));
		assertFalse("Should proxy, no foo", pred.test(someAddress("some.other.com")));
	}

	@Test
	public void nonProxyHosts_wildcardNone() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("foo.com");
		assertTrue("Should not proxy, exact match", pred.test(someAddress("foo.com")));
		assertFalse("Should proxy, mismatches", pred.test(someAddress("other.com")));
	}

	@Test
	public void nonProxyHosts_concatenated() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("exact.com|*first.com|last.com*|*both.com*");
		assertTrue("Should not proxy, has exact match", pred.test(someAddress("exact.com")));
		assertTrue("Should not proxy, matches a wildcarded prefix", pred.test(someAddress("other.first.com")));
		assertTrue("Should not proxy, matches a wildcarded suffix", pred.test(someAddress("last.com.net")));
		assertTrue("Should not proxy, matches wildcards", pred.test(someAddress("some.both.com.other")));
		assertTrue("Should not proxy, matches many", pred.test(someAddress("both.com.first.com")));
	}

	@Test
	public void nonProxyHosts_null() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern(null);
		assertFalse("Should proxy when nonProxyHosts is blanked out", pred.test(someAddress("foo.com")));
	}

	@Test
	public void nonProxyHosts_empty() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("");
		assertFalse("Should proxy when nonProxyHosts is blanked out", pred.test(someAddress("foo.com")));
	}

	@Test
	public void nonProxyHosts_javaDefault() {
		ProxyProvider.RegexShouldProxyPredicate defaultPredicate = ProxyProvider.RegexShouldProxyPredicate.DEFAULT_NON_PROXY;
		assertTrue("Should not proxy loopback", defaultPredicate.test(someAddress("127.0.0.1")));
		assertTrue("Should not proxy loopback", defaultPredicate.test(someAddress("127.0.0.2")));
		assertTrue("Should not proxy default", defaultPredicate.test(someAddress("0.0.0.0")));
		assertTrue("Should not proxy localhost", defaultPredicate.test(someAddress("localhost")));
	}

	@Test
	public void nonProxyHosts_wildcardInTheMiddle() {
		ProxyProvider.RegexShouldProxyPredicate pred = ProxyProvider.RegexShouldProxyPredicate.fromWildcardedPattern("some.*.com");
		assertFalse("Should proxy, nothing matching other", pred.test(someAddress("some.other.com")));
		assertFalse("Should proxy, nothing matching foo", pred.test(someAddress("some.foo.com")));
	}

	@Test
	public void nonProxyHosts_builderDefault_empty(){
		Predicate<SocketAddress> pred = ProxyProvider.builder().type(ProxyProvider.Proxy.HTTP).host("something").build().getNonProxyHostsPredicate();
		assertFalse("Default should proxy", pred.test(someAddress("localhost")));
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
		                    .nonProxyHosts("localhost")
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
}
