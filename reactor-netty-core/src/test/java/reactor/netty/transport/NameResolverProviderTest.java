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

import io.netty.handler.logging.LogLevel;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.TcpResources;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assumptions.assumeThat;
import static reactor.netty.transport.NameResolverProvider.Build.DEFAULT_CACHE_MAX_TIME_TO_LIVE;
import static reactor.netty.transport.NameResolverProvider.Build.DEFAULT_CACHE_MIN_TIME_TO_LIVE;
import static reactor.netty.transport.NameResolverProvider.Build.DEFAULT_CACHE_NEGATIVE_TIME_TO_LIVE;
import static reactor.netty.transport.NameResolverProvider.Build.DEFAULT_MAX_PAYLOAD_SIZE;
import static reactor.netty.transport.NameResolverProvider.Build.DEFAULT_MAX_QUERIES_PER_RESOLVE;
import static reactor.netty.transport.NameResolverProvider.Build.DEFAULT_NDOTS;
import static reactor.netty.transport.NameResolverProvider.Build.DEFAULT_QUERY_TIMEOUT;

/**
 * @author Violeta Georgieva
 */
class NameResolverProviderTest {
	private NameResolverProvider.Build builder;

	@BeforeEach
	void setUp() {
		builder = new NameResolverProvider.Build();
	}

	@Test
	void cacheMaxTimeToLive() {
		assertThat(builder.build().cacheMaxTimeToLive()).isEqualTo(DEFAULT_CACHE_MAX_TIME_TO_LIVE);

		Duration cacheMaxTimeToLive = Duration.ofSeconds(5);
		builder.cacheMaxTimeToLive(cacheMaxTimeToLive);
		assertThat(builder.build().cacheMaxTimeToLive()).isEqualTo(cacheMaxTimeToLive);
	}

	@Test
	void cacheMaxTimeToLiveBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.cacheMaxTimeToLive(null));

		builder.cacheMaxTimeToLive(Duration.ofSeconds(Long.MAX_VALUE));
		assertThatExceptionOfType(ArithmeticException.class)
				.isThrownBy(() -> builder.build().newNameResolverGroup(TcpResources.get(), LoopResources.DEFAULT_NATIVE));
	}

	@Test
	void cacheMinTimeToLive() {
		assertThat(builder.build().cacheMinTimeToLive()).isEqualTo(DEFAULT_CACHE_MIN_TIME_TO_LIVE);

		Duration cacheMinTimeToLive = Duration.ofSeconds(5);
		builder.cacheMinTimeToLive(cacheMinTimeToLive);
		assertThat(builder.build().cacheMinTimeToLive()).isEqualTo(cacheMinTimeToLive);
	}

	@Test
	void cacheMinTimeToLiveBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.cacheMinTimeToLive(null));

		builder.cacheMinTimeToLive(Duration.ofSeconds(Long.MAX_VALUE));
		assertThatExceptionOfType(ArithmeticException.class)
				.isThrownBy(() -> builder.build().newNameResolverGroup(TcpResources.get(), LoopResources.DEFAULT_NATIVE));
	}

	@Test
	void cacheNegativeTimeToLive() {
		assertThat(builder.build().cacheNegativeTimeToLive()).isEqualTo(DEFAULT_CACHE_NEGATIVE_TIME_TO_LIVE);

		Duration cacheNegativeTimeToLive = Duration.ofSeconds(5);
		builder.cacheNegativeTimeToLive(cacheNegativeTimeToLive);
		assertThat(builder.build().cacheNegativeTimeToLive()).isEqualTo(cacheNegativeTimeToLive);
	}

	@Test
	void cacheNegativeTimeToLiveBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.cacheNegativeTimeToLive(null));

		builder.cacheNegativeTimeToLive(Duration.ofSeconds(Long.MAX_VALUE));
		assertThatExceptionOfType(ArithmeticException.class)
				.isThrownBy(() -> builder.build().newNameResolverGroup(TcpResources.get(), LoopResources.DEFAULT_NATIVE));
	}

	@Test
	void disableOptionalRecord() {
		assertThat(builder.build().isDisableOptionalRecord()).isFalse();

		builder.disableOptionalRecord(true);
		assertThat(builder.build().isDisableOptionalRecord()).isTrue();
	}

	@Test
	void disableRecursionDesired() {
		assertThat(builder.build().isDisableRecursionDesired()).isFalse();

		builder.disableRecursionDesired(true);
		assertThat(builder.build().isDisableRecursionDesired()).isTrue();
	}

	@Test
	void maxPayloadSize() {
		assertThat(builder.build().maxPayloadSize()).isEqualTo(DEFAULT_MAX_PAYLOAD_SIZE);

		builder.maxPayloadSize(1024);
		assertThat(builder.build().maxPayloadSize()).isEqualTo(1024);
	}

	@Test
	void maxPayloadSizeBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxPayloadSize(0))
				.withMessage("maxPayloadSize must be positive");
	}

	@Test
	void maxQueriesPerResolve() {
		assertThat(builder.build().maxQueriesPerResolve()).isEqualTo(DEFAULT_MAX_QUERIES_PER_RESOLVE);

		builder.maxQueriesPerResolve(4);
		assertThat(builder.build().maxQueriesPerResolve()).isEqualTo(4);
	}

	@Test
	void maxQueriesPerResolveBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxQueriesPerResolve(0))
				.withMessage("maxQueriesPerResolve must be positive");
	}

	@Test
	void ndots() {
		assertThat(builder.build().ndots()).isEqualTo(DEFAULT_NDOTS);

		builder.ndots(4);
		assertThat(builder.build().ndots()).isEqualTo(4);
	}

	@Test
	void ndotsBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.ndots(-2))
				.withMessage("ndots must be greater or equal to -1");
	}

	@Test
	void queryTimeout() {
		assertThat(builder.build().queryTimeout()).isEqualTo(DEFAULT_QUERY_TIMEOUT);

		Duration queryTimeout = Duration.ofSeconds(5);
		builder.queryTimeout(queryTimeout);
		assertThat(builder.build().queryTimeout()).isEqualTo(queryTimeout);
	}

	@Test
	void queryTimeoutBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.queryTimeout(null));
	}

	@Test
	void resolvedAddressTypes() {
		assertThat(builder.build().resolvedAddressTypes()).isNull();

		builder.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY);
		assertThat(builder.build().resolvedAddressTypes()).isEqualTo(ResolvedAddressTypes.IPV4_ONLY);
	}

	@Test
	void resolvedAddressTypesBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.resolvedAddressTypes(null));
	}

	@Test
	void roundRobinSelection() {
		assertThat(builder.build().isRoundRobinSelection()).isFalse();

		builder.roundRobinSelection(true);
		assertThat(builder.build().isRoundRobinSelection()).isTrue();
	}

	@Test
	void runOn() {
		assertThat(builder.build().loopResources()).isNull();
		assertThat(builder.build().isPreferNative()).isTrue();

		LoopResources loop = LoopResources.create("runOn");
		builder.runOn(loop, false);
		assertThat(builder.build().loopResources()).isEqualTo(loop);
		assertThat(builder.build().isPreferNative()).isFalse();
	}

	@Test
	void runOnBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.runOn(null, false));
	}

	@Test
	void searchDomains() {
		assertThat(builder.build().searchDomains()).isNull();

		List<String> searchDomains = Collections.singletonList("searchDomains");
		builder.searchDomains(searchDomains);
		assertThat(builder.build().searchDomains()).isEqualTo(searchDomains);
	}

	@Test
	void searchDomainsBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.searchDomains(null));
	}

	@Test
	void traceBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.trace(null, LogLevel.DEBUG));

		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.trace("category", null));
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void testMacOsResolver() {
		// MacOS binaries are not available for Netty SNAPSHOT version
		assumeThat(MacOSDnsServerAddressStreamProvider.isAvailable()).isTrue();
		assertThat(DnsServerAddressStreamProviders.platformDefault())
				.isInstanceOf(MacOSDnsServerAddressStreamProvider.class);
	}
}
