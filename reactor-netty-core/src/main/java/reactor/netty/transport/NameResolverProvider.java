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

import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.RoundRobinDnsAddressResolverGroup;
import reactor.netty.resources.LoopResources;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A {@link NameResolverProvider} will produce {@link DnsAddressResolverGroup}.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class NameResolverProvider {

	public interface NameResolverSpec {

		/**
		 * Build a new {@link NameResolverProvider}
		 *
		 * @return a new {@link NameResolverProvider}
		 */
		NameResolverProvider build();

		/**
		 * Sets the max time to live of the cached DNS resource records (resolution: seconds).
		 * If the time to live of the DNS resource record returned by the DNS server is greater
		 * than this max time to live, this resolver will ignore the time to live from
		 * the DNS server and will use this max time to live.
		 * Default to {@link Build#DEFAULT_CACHE_MAX_TIME_TO_LIVE}.
		 *
		 * @param cacheMaxTimeToLive the maximum time to live (resolution: seconds)
		 * @return {@code this}
		 */
		NameResolverSpec cacheMaxTimeToLive(Duration cacheMaxTimeToLive);

		/**
		 * Sets the min time to live of the cached DNS resource records (resolution: seconds).
		 * If the time to live of the DNS resource record returned by the DNS server is less
		 * than this min time to live, this resolver will ignore the time to live from
		 * the DNS server and will use this min time to live.
		 * Default to {@link Build#DEFAULT_CACHE_MIN_TIME_TO_LIVE}.
		 *
		 * @param cacheMinTimeToLive the minimum time to live (resolution: seconds)
		 * @return {@code this}
		 */
		NameResolverSpec cacheMinTimeToLive(Duration cacheMinTimeToLive);

		/**
		 * Sets the time to live of the cache for the failed DNS queries (resolution: seconds).
		 * Default to {@link Build#DEFAULT_CACHE_NEGATIVE_TIME_TO_LIVE}.
		 *
		 * @param cacheNegativeTimeToLive the time to live of the cache for the failed
		 * DNS queries (resolution: seconds)
		 * @return {@code this}
		 */
		NameResolverSpec cacheNegativeTimeToLive(Duration cacheNegativeTimeToLive);

		/**
		 * Disables the automatic inclusion of an optional record that tries to hint the remote DNS server about
		 * how much data the resolver can read per response. By default this is enabled.
		 *
		 * @param disable true if an optional record is not included
		 * @return {@code this}
		 */
		NameResolverSpec disableOptionalRecord(boolean disable);

		/**
		 * Specifies whether this resolver has to send a DNS query with the recursion desired (RD) flag set.
		 * By default this is enabled.
		 *
		 * @param disable true if RD flag is not set
		 * @return {@code this}
		 */
		NameResolverSpec disableRecursionDesired(boolean disable);

		/**
		 * Sets the capacity of the datagram packet buffer (in bytes).
		 * Default to {@link Build#DEFAULT_MAX_PAYLOAD_SIZE}.
		 *
		 * @param maxPayloadSize the capacity of the datagram packet buffer
		 * @return {@code this}
		 * @throws IllegalArgumentException if {@code maxPayloadSize} is not positive
		 */
		NameResolverSpec maxPayloadSize(int maxPayloadSize);

		/**
		 * Sets the maximum allowed number of DNS queries to send when resolving a host name.
		 * Default to {@link Build#DEFAULT_MAX_QUERIES_PER_RESOLVE}.
		 *
		 * @param maxQueriesPerResolve the max number of queries
		 * @return {@code this}
		 * @throws IllegalArgumentException if {@code maxQueriesPerResolve} is not positive
		 */
		NameResolverSpec maxQueriesPerResolve(int maxQueriesPerResolve);

		/**
		 * Sets the number of dots which must appear in a name before an initial absolute query is made.
		 * Default to {@link Build#DEFAULT_NDOTS} which determines the value from the OS on Unix
		 * or uses the value {@code 1}.
		 *
		 * @param ndots the ndots value
		 * @return {@code this}
		 * @throws IllegalArgumentException if {@code ndots} is less than -1
		 */
		NameResolverSpec ndots(int ndots);

		/**
		 * Sets the timeout of each DNS query performed by this resolver (resolution: milliseconds).
		 * Default to {@link Build#DEFAULT_QUERY_TIMEOUT}.
		 *
		 * @param queryTimeout the query timeout (resolution: milliseconds)
		 * @return {@code this}
		 */
		NameResolverSpec queryTimeout(Duration queryTimeout);

		/**
		 * Sets the list of the protocol families of the address resolved.
		 *
		 * @param resolvedAddressTypes the address types
		 * @return {@code this}
		 */
		NameResolverSpec resolvedAddressTypes(ResolvedAddressTypes resolvedAddressTypes);

		/**
		 * Enables an {@link AddressResolverGroup} of {@link DnsNameResolver}s that supports random selection
		 * of destination addresses if multiple are provided by the nameserver.
		 * See {@link RoundRobinDnsAddressResolverGroup}.
		 * Default to {@link DnsAddressResolverGroup}.
		 *
		 * @return {@code this}
		 */
		NameResolverSpec roundRobinSelection(boolean enable);

		/**
		 * Performs the communication with the DNS servers on the given {@link EventLoopGroup}.
		 *
		 * @param eventLoopGroup the {@link EventLoopGroup}
		 * @return {@code this}
		 */
		NameResolverSpec runOn(EventLoopGroup eventLoopGroup);

		/**
		 * Performs the communication with the DNS servers on a supplied {@link EventLoopGroup}
		 * from the {@link LoopResources} container.
		 * Will prefer native (epoll/kqueue) implementation if available
		 * unless the environment property {@code reactor.netty.native} is set to {@code false}.
		 *
		 * @param loopResources the {@link LoopResources}
		 * @return {@code this}
		 */
		NameResolverSpec runOn(LoopResources loopResources);

		/**
		 * Performs the communication with the DNS servers on a supplied {@link EventLoopGroup}
		 * from the {@link LoopResources} container.
		 *
		 * @param loopResources the {@link LoopResources}
		 * @param preferNative should prefer running on epoll or kqueue instead of java NIO
		 * @return {@code this}
		 */
		NameResolverSpec runOn(LoopResources loopResources, boolean preferNative);

		/**
		 * Sets the list of search domains of the resolver.
		 * By default the effective search domain list will be populated using
		 * the system DNS search domains.
		 *
		 * @param searchDomains the search domains
		 * @return {@code this}
		 */
		NameResolverSpec searchDomains(List<String> searchDomains);

		/**
		 * Sets if this resolver should generate the detailed trace information.
		 *
		 * @param enable true if trace is enabled
		 * @return {@code this}
		 */
		NameResolverSpec trace(boolean enable);
	}

	/**
	 * Creates a builder for {@link NameResolverProvider}.
	 *
	 * @return a new {@link NameResolverProvider.NameResolverSpec}
	 */
	public static NameResolverSpec builder() {
		return new Build();
	}

	/**
	 * Returns the configured max time to live of the cached DNS resource records.
	 *
	 * @return the configured max time to live of the cached DNS resource records
	 */
	public Duration cacheMaxTimeToLive() {
		return cacheMaxTimeToLive;
	}

	/**
	 * Returns the configured min time to live of the cached DNS resource records.
	 *
	 * @return the configured min time to live of the cached DNS resource records
	 */
	public Duration cacheMinTimeToLive() {
		return cacheMinTimeToLive;
	}

	/**
	 * Returns the configured time to live of the cache for the failed DNS queries.
	 *
	 * @return the configured time to live of the cache for the failed DNS queries
	 */
	public Duration cacheNegativeTimeToLive() {
		return cacheNegativeTimeToLive;
	}

	/**
	 * Returns {@code true} if an optional record inclusion is disabled.
	 *
	 * @return {@code true} if an optional record inclusion is disabled
	 */
	public boolean isDisableOptionalRecord() {
		return disableOptionalRecord;
	}

	/**
	 * Returns {@code true} if recursion desired is disabled.
	 *
	 * @return {@code true} if recursion desired is disabled
	 */
	public boolean isDisableRecursionDesired() {
		return disableRecursionDesired;
	}

	/**
	 * Returns {@code true} if prefer native event loop and channel factory (e.g. epoll or kqueue).
	 *
	 * @return {@code true} if prefer native event loop and channel factory (e.g. epoll or kqueue)
	 */
	public boolean isPreferNative() {
		return preferNative;
	}

	/**
	 * Returns true if {@link RoundRobinDnsAddressResolverGroup} is in use.
	 *
	 * @return true if {@link RoundRobinDnsAddressResolverGroup} is in use
	 */
	public boolean isRoundRobinSelection() {
		return roundRobinSelection;
	}

	/**
	 * Returns {@code true} if this resolver should generate the detailed trace information.
	 *
	 * @return {@code true} if this resolver should generate the detailed trace information
	 */
	public boolean isTrace() {
		return trace;
	}

	/**
	 * Returns the configured {@link LoopResources} or null.
	 *
	 * @return the configured {@link LoopResources} or null
	 */
	@Nullable
	public LoopResources loopResources() {
		return loopResources;
	}

	/**
	 * Returns the configured capacity of the datagram packet buffer.
	 *
	 * @return the configured capacity of the datagram packet buffer.
	 */
	public int maxPayloadSize() {
		return maxPayloadSize;
	}

	/**
	 * Returns the configured maximum allowed number of DNS queries to send when resolving a host name.
	 *
	 * @return the configured maximum allowed number of DNS queries to send when resolving a host name
	 */
	public int maxQueriesPerResolve() {
		return maxQueriesPerResolve;
	}

	/**
	 * Returns the number of dots which must appear in a name before an initial absolute query is made.
	 *
	 * @return the number of dots which must appear in a name before an initial absolute query is made
	 */
	public int ndots() {
		return ndots;
	}

	/**
	 * Returns the configured timeout of each DNS query performed by this resolver.
	 *
	 * @return the configured timeout of each DNS query performed by this resolver
	 */
	public Duration queryTimeout() {
		return queryTimeout;
	}

	/**
	 * Returns the configured list of the protocol families of the address resolved or null.
	 *
	 * @return the configured list of the protocol families of the address resolved or null
	 */
	@Nullable
	public ResolvedAddressTypes resolvedAddressTypes() {
		return resolvedAddressTypes;
	}

	/**
	 * Returns the configured list of search domains of the resolver or null.
	 *
	 * @return the configured list of search domains of the resolver or null
	 */
	@Nullable
	public Iterable<String> searchDomains() {
		return searchDomains;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof NameResolverProvider)) {
			return false;
		}
		NameResolverProvider that = (NameResolverProvider) o;
		return disableRecursionDesired == that.disableRecursionDesired &&
				disableOptionalRecord == that.disableOptionalRecord &&
				maxPayloadSize == that.maxPayloadSize &&
				maxQueriesPerResolve == that.maxQueriesPerResolve &&
				ndots == that.ndots &&
				preferNative == that.preferNative &&
				roundRobinSelection == that.roundRobinSelection &&
				trace == that.trace &&
				cacheMaxTimeToLive.equals(that.cacheMaxTimeToLive) &&
				cacheMinTimeToLive.equals(that.cacheMinTimeToLive) &&
				cacheNegativeTimeToLive.equals(that.cacheNegativeTimeToLive) &&
				Objects.equals(loopResources, that.loopResources) &&
				queryTimeout.equals(that.queryTimeout) &&
				resolvedAddressTypes == that.resolvedAddressTypes &&
				Objects.equals(searchDomains, that.searchDomains);
	}

	@Override
	public int hashCode() {
		return Objects.hash(cacheMaxTimeToLive, cacheMinTimeToLive, cacheNegativeTimeToLive, disableRecursionDesired,
				disableOptionalRecord, loopResources, maxPayloadSize, maxQueriesPerResolve, ndots, preferNative,
				queryTimeout, resolvedAddressTypes, roundRobinSelection, searchDomains, trace);
	}

	/**
	 * Provides a new {@link DnsAddressResolverGroup}.
	 *
	 * @param defaultLoopResources the default {@link LoopResources} when {@link LoopResources} is not specified
	 * @return a new {@link DnsAddressResolverGroup}
	 */
	public DnsAddressResolverGroup newNameResolverGroup(LoopResources defaultLoopResources) {
		LoopResources loop = loopResources == null ? defaultLoopResources : loopResources;
		EventLoopGroup group = loop.onClient(preferNative);
		DnsNameResolverBuilder builder = new DnsNameResolverBuilder()
				.ttl(Math.toIntExact(cacheMinTimeToLive.getSeconds()), Math.toIntExact(cacheMaxTimeToLive.getSeconds()))
				.negativeTtl(Math.toIntExact(cacheNegativeTimeToLive.getSeconds()))
				.optResourceEnabled(!disableOptionalRecord)
				.recursionDesired(!disableRecursionDesired)
				.maxPayloadSize(maxPayloadSize)
				.maxQueriesPerResolve(maxQueriesPerResolve)
				.ndots(ndots)
				.queryTimeoutMillis(queryTimeout.toMillis())
				.traceEnabled(trace)
				.eventLoop(group.next())
				.channelFactory(() -> loop.onChannel(DatagramChannel.class, group))
				.socketChannelFactory(() -> loop.onChannel(SocketChannel.class, group));
		if (resolvedAddressTypes != null) {
			builder.resolvedAddressTypes(resolvedAddressTypes);
		}
		if (searchDomains != null) {
			builder.searchDomains(searchDomains);
		}
		return roundRobinSelection ? new RoundRobinDnsAddressResolverGroup(builder) : new DnsAddressResolverGroup(builder);
	}

	final Duration cacheMaxTimeToLive;
	final Duration cacheMinTimeToLive;
	final Duration cacheNegativeTimeToLive;
	final boolean disableRecursionDesired;
	final boolean disableOptionalRecord;
	final LoopResources loopResources;
	final int maxPayloadSize;
	final int maxQueriesPerResolve;
	final int ndots;
	final boolean preferNative;
	final Duration queryTimeout;
	final ResolvedAddressTypes resolvedAddressTypes;
	final boolean roundRobinSelection;
	final Iterable<String> searchDomains;
	final boolean trace;

	NameResolverProvider(Build build) {
		this.cacheMaxTimeToLive = build.cacheMaxTimeToLive;
		this.cacheMinTimeToLive = build.cacheMinTimeToLive;
		this.cacheNegativeTimeToLive = build.cacheNegativeTimeToLive;
		this.disableOptionalRecord = build.disableOptionalRecord;
		this.disableRecursionDesired = build.disableRecursionDesired;
		this.loopResources = build.loopResources;
		this.maxPayloadSize = build.maxPayloadSize;
		this.maxQueriesPerResolve = build.maxQueriesPerResolve;
		this.ndots = build.ndots;
		this.preferNative = build.preferNative;
		this.queryTimeout = build.queryTimeout;
		this.resolvedAddressTypes = build.resolvedAddressTypes;
		this.roundRobinSelection = build.roundRobinSelection;
		this.searchDomains = build.searchDomains;
		this.trace = build.trace;
	}

	static final class Build implements NameResolverSpec {
		static final Duration DEFAULT_CACHE_MAX_TIME_TO_LIVE = Duration.ofSeconds(Integer.MAX_VALUE);
		static final Duration DEFAULT_CACHE_MIN_TIME_TO_LIVE = Duration.ofSeconds(0);
		static final Duration DEFAULT_CACHE_NEGATIVE_TIME_TO_LIVE = Duration.ofSeconds(0);
		static final int DEFAULT_MAX_PAYLOAD_SIZE = 4096;
		static final int DEFAULT_MAX_QUERIES_PER_RESOLVE = 16;
		static final int DEFAULT_NDOTS = -1;
		static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(5);

		Duration cacheMaxTimeToLive = DEFAULT_CACHE_MAX_TIME_TO_LIVE;
		Duration cacheMinTimeToLive = DEFAULT_CACHE_MIN_TIME_TO_LIVE;
		Duration cacheNegativeTimeToLive = DEFAULT_CACHE_NEGATIVE_TIME_TO_LIVE;
		boolean disableOptionalRecord;
		boolean disableRecursionDesired;
		LoopResources loopResources;
		int maxPayloadSize = DEFAULT_MAX_PAYLOAD_SIZE;
		int maxQueriesPerResolve = DEFAULT_MAX_QUERIES_PER_RESOLVE;
		int ndots = DEFAULT_NDOTS;
		boolean preferNative = LoopResources.DEFAULT_NATIVE;
		Duration queryTimeout = DEFAULT_QUERY_TIMEOUT;
		ResolvedAddressTypes resolvedAddressTypes;
		boolean roundRobinSelection;
		Iterable<String> searchDomains;
		boolean trace;

		@Override
		public NameResolverSpec cacheMaxTimeToLive(Duration cacheMaxTimeToLive) {
			this.cacheMaxTimeToLive = Objects.requireNonNull(cacheMaxTimeToLive);
			return this;
		}

		@Override
		public NameResolverSpec cacheMinTimeToLive(Duration cacheMinTimeToLive) {
			this.cacheMinTimeToLive = Objects.requireNonNull(cacheMinTimeToLive);
			return this;
		}

		@Override
		public NameResolverSpec cacheNegativeTimeToLive(Duration cacheNegativeTimeToLive) {
			this.cacheNegativeTimeToLive = Objects.requireNonNull(cacheNegativeTimeToLive);
			return this;
		}

		@Override
		public NameResolverSpec disableOptionalRecord(boolean disable) {
			this.disableOptionalRecord = disable;
			return this;
		}

		@Override
		public NameResolverSpec disableRecursionDesired(boolean disable) {
			this.disableRecursionDesired = disable;
			return this;
		}

		@Override
		public NameResolverSpec maxPayloadSize(int maxPayloadSize) {
			if (maxPayloadSize < 1) {
				throw new IllegalArgumentException("maxPayloadSize must be positive");
			}
			this.maxPayloadSize = maxPayloadSize;
			return this;
		}

		@Override
		public NameResolverSpec maxQueriesPerResolve(int maxQueriesPerResolve) {
			if (maxQueriesPerResolve < 1) {
				throw new IllegalArgumentException("maxQueriesPerResolve must be positive");
			}
			this.maxQueriesPerResolve = maxQueriesPerResolve;
			return this;
		}

		@Override
		public NameResolverSpec ndots(int ndots) {
			if (ndots < -1) {
				throw new IllegalArgumentException("ndots must be greater or equal to -1");
			}
			this.ndots = ndots;
			return this;
		}

		@Override
		public NameResolverSpec queryTimeout(Duration queryTimeout) {
			this.queryTimeout = Objects.requireNonNull(queryTimeout, "queryTimeout");
			return this;
		}

		@Override
		public NameResolverSpec resolvedAddressTypes(ResolvedAddressTypes resolvedAddressTypes) {
			this.resolvedAddressTypes = Objects.requireNonNull(resolvedAddressTypes);
			return this;
		}

		@Override
		public NameResolverSpec roundRobinSelection(boolean enable) {
			this.roundRobinSelection = enable;
			return this;
		}

		@Override
		public NameResolverSpec runOn(EventLoopGroup eventLoopGroup) {
			Objects.requireNonNull(eventLoopGroup, "eventLoopGroup");
			return runOn(preferNative -> eventLoopGroup);
		}

		@Override
		public NameResolverSpec runOn(LoopResources loopResources) {
			Objects.requireNonNull(loopResources, "loopResources");
			return runOn(loopResources, LoopResources.DEFAULT_NATIVE);
		}

		@Override
		public NameResolverSpec runOn(LoopResources loopResources, boolean preferNative) {
			Objects.requireNonNull(loopResources, "loopResources");
			this.loopResources = loopResources;
			this.preferNative = preferNative;
			return this;
		}

		@Override
		public NameResolverSpec searchDomains(List<String> searchDomains) {
			this.searchDomains = Objects.requireNonNull(searchDomains, "searchDomains");
			return this;
		}

		@Override
		public NameResolverSpec trace(boolean enable) {
			this.trace = enable;
			return this;
		}

		@Override
		public NameResolverProvider build() {
			return new NameResolverProvider(this);
		}
	}
}
