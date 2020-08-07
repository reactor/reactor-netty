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

import io.netty.resolver.ResolvedAddressTypes;
import org.junit.Before;
import org.junit.Test;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.TcpResources;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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
public class NameResolverProviderTest {
	private NameResolverProvider.Build builder;

	@Before
	public void setUp() {
		builder = new NameResolverProvider.Build();
	}

	@Test
	public void cacheMaxTimeToLive() {
		assertThat(builder.build().cacheMaxTimeToLive()).isEqualTo(DEFAULT_CACHE_MAX_TIME_TO_LIVE);

		Duration cacheMaxTimeToLive = Duration.ofSeconds(5);
		builder.cacheMaxTimeToLive(cacheMaxTimeToLive);
		assertThat(builder.build().cacheMaxTimeToLive()).isEqualTo(cacheMaxTimeToLive);
	}

	@Test
	public void cacheMaxTimeToLiveBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.cacheMaxTimeToLive(null));

		builder.cacheMaxTimeToLive(Duration.ofSeconds(Long.MAX_VALUE));
		assertThatExceptionOfType(ArithmeticException.class)
				.isThrownBy(() -> builder.build().newNameResolverGroup(TcpResources.get()));
	}

	@Test
	public void cacheMinTimeToLive() {
		assertThat(builder.build().cacheMinTimeToLive()).isEqualTo(DEFAULT_CACHE_MIN_TIME_TO_LIVE);

		Duration cacheMinTimeToLive = Duration.ofSeconds(5);
		builder.cacheMinTimeToLive(cacheMinTimeToLive);
		assertThat(builder.build().cacheMinTimeToLive()).isEqualTo(cacheMinTimeToLive);
	}

	@Test
	public void cacheMinTimeToLiveBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.cacheMinTimeToLive(null));

		builder.cacheMinTimeToLive(Duration.ofSeconds(Long.MAX_VALUE));
		assertThatExceptionOfType(ArithmeticException.class)
				.isThrownBy(() -> builder.build().newNameResolverGroup(TcpResources.get()));
	}

	@Test
	public void cacheNegativeTimeToLive() {
		assertThat(builder.build().cacheNegativeTimeToLive()).isEqualTo(DEFAULT_CACHE_NEGATIVE_TIME_TO_LIVE);

		Duration cacheNegativeTimeToLive = Duration.ofSeconds(5);
		builder.cacheNegativeTimeToLive(cacheNegativeTimeToLive);
		assertThat(builder.build().cacheNegativeTimeToLive()).isEqualTo(cacheNegativeTimeToLive);
	}

	@Test
	public void cacheNegativeTimeToLiveBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.cacheNegativeTimeToLive(null));

		builder.cacheNegativeTimeToLive(Duration.ofSeconds(Long.MAX_VALUE));
		assertThatExceptionOfType(ArithmeticException.class)
				.isThrownBy(() -> builder.build().newNameResolverGroup(TcpResources.get()));
	}

	@Test
	public void disableOptionalRecord() {
		assertFalse(builder.build().isDisableOptionalRecord());

		builder.disableOptionalRecord(true);
		assertTrue(builder.build().isDisableOptionalRecord());
	}

	@Test
	public void disableRecursionDesired() {
		assertFalse(builder.build().isDisableRecursionDesired());

		builder.disableRecursionDesired(true);
		assertTrue(builder.build().isDisableRecursionDesired());
	}

	@Test
	public void maxPayloadSize() {
		assertThat(builder.build().maxPayloadSize()).isEqualTo(DEFAULT_MAX_PAYLOAD_SIZE);

		builder.maxPayloadSize(1024);
		assertThat(builder.build().maxPayloadSize()).isEqualTo(1024);
	}

	@Test
	public void maxPayloadSizeBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxPayloadSize(0))
				.withMessage("maxPayloadSize must be positive");
	}

	@Test
	public void maxQueriesPerResolve() {
		assertThat(builder.build().maxQueriesPerResolve()).isEqualTo(DEFAULT_MAX_QUERIES_PER_RESOLVE);

		builder.maxQueriesPerResolve(4);
		assertThat(builder.build().maxQueriesPerResolve()).isEqualTo(4);
	}

	@Test
	public void maxQueriesPerResolveBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxQueriesPerResolve(0))
				.withMessage("maxQueriesPerResolve must be positive");
	}

	@Test
	public void ndots() {
		assertThat(builder.build().ndots()).isEqualTo(DEFAULT_NDOTS);

		builder.ndots(4);
		assertThat(builder.build().ndots()).isEqualTo(4);
	}

	@Test
	public void ndotsBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.ndots(-2))
				.withMessage("ndots must be greater or equal to -1");
	}

	@Test
	public void queryTimeout() {
		assertThat(builder.build().queryTimeout()).isEqualTo(DEFAULT_QUERY_TIMEOUT);

		Duration queryTimeout = Duration.ofSeconds(5);
		builder.queryTimeout(queryTimeout);
		assertThat(builder.build().queryTimeout()).isEqualTo(queryTimeout);
	}

	@Test
	public void queryTimeoutBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.queryTimeout(null));
	}

	@Test
	public void resolvedAddressTypes() {
		assertNull(builder.build().resolvedAddressTypes());

		builder.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY);
		assertThat(builder.build().resolvedAddressTypes()).isEqualTo(ResolvedAddressTypes.IPV4_ONLY);
	}

	@Test
	public void resolvedAddressTypesBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.resolvedAddressTypes(null));
	}

	@Test
	public void roundRobinSelection() {
		assertFalse(builder.build().isRoundRobinSelection());

		builder.roundRobinSelection(true);
		assertTrue(builder.build().isRoundRobinSelection());
	}

	@Test
	public void runOn() {
		assertNull(builder.build().loopResources());
		assertTrue(builder.build().isPreferNative());

		LoopResources loop = LoopResources.create("runOn");
		builder.runOn(loop, false);
		assertThat(builder.build().loopResources()).isEqualTo(loop);
		assertFalse(builder.build().isPreferNative());
	}

	@Test
	public void runOnBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.runOn(null, false));
	}

	@Test
	public void searchDomains() {
		assertNull(builder.build().searchDomains());

		List<String> searchDomains = Collections.singletonList("searchDomains");
		builder.searchDomains(searchDomains);
		assertThat(builder.build().searchDomains()).isEqualTo(searchDomains);
	}

	@Test
	public void searchDomainsBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> builder.searchDomains(null));
	}

	@Test
	public void trace() {
		assertFalse(builder.build().isTrace());

		builder.trace(true);
		assertTrue(builder.build().isTrace());
	}
}
