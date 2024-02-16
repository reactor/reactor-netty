/*
 * Copyright (c) 2020-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultHostsFileEntriesResolver;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsCache;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.LoggingDnsQueryLifeCycleObserverFactory;
import io.netty.resolver.dns.RoundRobinDnsAddressResolverGroup;
import io.netty.util.concurrent.Future;
import reactor.netty.resources.LoopResources;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A {@link NameResolverProvider} will produce {@link DnsAddressResolverGroup}.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class NameResolverProvider {

	public interface NameResolverSpec {

		/**
		 * Set a new local address supplier that supply the address to bind to.
		 * By default, the host is configured for any local address, and the system picks up an ephemeral port.
		 *
		 * @param bindAddressSupplier A supplier of local address to bind to
		 * @return {@code this}
		 * @since 1.0.14
		 */
		NameResolverSpec bindAddressSupplier(Supplier<? extends SocketAddress> bindAddressSupplier);

		/**
		 * Build a new {@link NameResolverProvider}.
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
		 * If {@code true}, the resolver notifies the returned {@link Future} as
		 * soon as all queries for the preferred address type are complete.
		 * If {@code false}, the resolver notifies the returned {@link Future} when
		 * all possible address types are complete.
		 * This configuration is applicable for {@link DnsNameResolver#resolveAll(String)}.
		 * By default, this is enabled.
		 *
		 * @param enable {@code true} to enable, {@code false} to disable
		 * @return {@code this}
		 */
		NameResolverSpec completeOncePreferredResolved(boolean enable);

		/**
		 * Disables the automatic inclusion of an optional record that tries to hint the remote DNS server about
		 * how much data the resolver can read per response. By default, this is enabled.
		 *
		 * @param disable true if an optional record is not included
		 * @return {@code this}
		 */
		NameResolverSpec disableOptionalRecord(boolean disable);

		/**
		 * Specifies whether this resolver has to send a DNS query with the recursion desired (RD) flag set.
		 * By default, this is enabled.
		 *
		 * @param disable true if RD flag is not set
		 * @return {@code this}
		 */
		NameResolverSpec disableRecursionDesired(boolean disable);

		/**
		 * Sets the custom function to build the {@link DnsAddressResolverGroup} given a {@link DnsNameResolverBuilder}.
		 *
		 * @param dnsAddressResolverGroupProvider the {@link DnsAddressResolverGroup} provider function
		 * @return {@code this}
		 * @since 1.1.6
		 */
		NameResolverSpec dnsAddressResolverGroupProvider(
				Function<DnsNameResolverBuilder, DnsAddressResolverGroup> dnsAddressResolverGroupProvider);

		/**
		 * Specifies a custom {@link HostsFileEntriesResolver} to be used for hosts file entries.
		 * Default to {@link DefaultHostsFileEntriesResolver}.
		 *
		 * @param hostsFileEntriesResolver the {@link HostsFileEntriesResolver} to be used for hosts file entries
		 * @return {@code this}
		 * @since 1.0.12
		 */
		NameResolverSpec hostsFileEntriesResolver(HostsFileEntriesResolver hostsFileEntriesResolver);

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
		 * Sets the resolve cache to use for DNS resolution.
		 *
		 * @param resolveCache the resolution DNS cache
		 * @return {@code this}
		 * @since 1.0.27
		 */
		NameResolverSpec resolveCache(DnsCache resolveCache);

		/**
		 * Sets the list of the protocol families of the address resolved.
		 *
		 * @param resolvedAddressTypes the address types
		 * @return {@code this}
		 */
		NameResolverSpec resolvedAddressTypes(ResolvedAddressTypes resolvedAddressTypes);

		/**
		 * Specifies whether this resolver will also fallback to TCP if a timeout is detected.
		 * By default, the resolver will only try to use TCP if the response is marked as truncated.
		 *
		 * @param enable if true this resolver will also fallback to TCP if a timeout is detected,
		 * if false the resolver will only try to use TCP if the response is marked as truncated.
		 * @return {@code this}
		 * @since 1.1.17
		 */
		NameResolverSpec retryTcpOnTimeout(boolean enable);

		/**
		 * Enables an {@link AddressResolverGroup} of {@link DnsNameResolver}s that supports random selection
		 * of destination addresses if multiple are provided by the nameserver.
		 * See {@link RoundRobinDnsAddressResolverGroup}.
		 * Default to {@link DnsAddressResolverGroup}.
		 *
		 * @param enable {@code true} to enable, {@code false} to disable
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
		 * By default, the effective search domain list will be populated using
		 * the system DNS search domains.
		 *
		 * @param searchDomains the search domains
		 * @return {@code this}
		 */
		NameResolverSpec searchDomains(List<String> searchDomains);

		/**
		 * Sets a specific category and log level to be used by this resolver when generating a detailed trace
		 * information in case of resolution failure.
		 *
		 * @param category the logger category
		 * @param level the logger level
		 * @return {@code this}
		 */
		NameResolverSpec trace(String category, LogLevel level);
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
	 * Returns the configured supplier of local address to bind to or null.
	 *
	 * @return the configured supplier of local address to bind to or null
	 * @since 1.0.14
	 */
	@Nullable
	public Supplier<? extends SocketAddress> bindAddressSupplier() {
		return bindAddressSupplier;
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
	 * Returns the configured custom provider of {@link DnsAddressResolverGroup} or null.
	 *
	 * @return the configured custom provider of {@link DnsAddressResolverGroup} or null
	 * @since 1.1.6
	 */
	@Nullable
	public Function<DnsNameResolverBuilder, DnsAddressResolverGroup> dnsAddressResolverGroupProvider() {
		return dnsAddressResolverGroupProvider;
	}

	/**
	 * Returns the configured custom {@link HostsFileEntriesResolver} to be used for hosts file entries or null.
	 *
	 * @return the configured custom {@link HostsFileEntriesResolver} to be used for hosts file entries or null
	 * @since 1.0.12
	 */
	@Nullable
	public HostsFileEntriesResolver hostsFileEntriesResolver() {
		return hostsFileEntriesResolver;
	}

	/**
	 * Returns {@code true} if the resolver notifies the returned {@link Future} as
	 * soon as all queries for the preferred address type are complete.
	 *
	 * @return {@code true} if the resolver notifies the returned {@link Future} as
	 * soon as all queries for the preferred address type are complete
	 */
	public boolean isCompleteOncePreferredResolved() {
		return completeOncePreferredResolved;
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
	 * Returns {@code true} if the resolver will also fallback to TCP if a timeout is detected.
	 *
	 * @return {@code true} if the resolver will also fallback to TCP if a timeout is detected
	 * @since 1.1.17
	 */
	public boolean isRetryTcpOnTimeout() {
		return retryTcpOnTimeout;
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
	 * Returns the configured DNS resolver cache or null.
	 *
	 * @return the configured DNS resolver cache or null
	 * @since 1.0.27
	 */
	@Nullable
	public DnsCache resolveCache() {
		return resolveCache;
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
	@SuppressWarnings("UndefinedEquals")
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof NameResolverProvider)) {
			return false;
		}
		NameResolverProvider that = (NameResolverProvider) o;
		return Objects.equals(bindAddressSupplier, that.bindAddressSupplier) &&
				cacheMaxTimeToLive.equals(that.cacheMaxTimeToLive) &&
				cacheMinTimeToLive.equals(that.cacheMinTimeToLive) &&
				cacheNegativeTimeToLive.equals(that.cacheNegativeTimeToLive) &&
				completeOncePreferredResolved == that.completeOncePreferredResolved &&
				disableOptionalRecord == that.disableOptionalRecord &&
				disableRecursionDesired == that.disableRecursionDesired &&
				Objects.equals(dnsAddressResolverGroupProvider, that.dnsAddressResolverGroupProvider) &&
				Objects.equals(loggingFactory, that.loggingFactory) &&
				Objects.equals(loopResources, that.loopResources) &&
				maxPayloadSize == that.maxPayloadSize &&
				maxQueriesPerResolve == that.maxQueriesPerResolve &&
				ndots == that.ndots &&
				preferNative == that.preferNative &&
				queryTimeout.equals(that.queryTimeout) &&
				Objects.equals(resolveCache, that.resolveCache) &&
				resolvedAddressTypes == that.resolvedAddressTypes &&
				retryTcpOnTimeout == that.retryTcpOnTimeout &&
				roundRobinSelection == that.roundRobinSelection &&
				// searchDomains is List so Objects.equals is OK
				Objects.equals(searchDomains, that.searchDomains);
	}

	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + Objects.hashCode(bindAddressSupplier);
		result = 31 * result + Objects.hashCode(cacheMaxTimeToLive);
		result = 31 * result + Objects.hashCode(cacheMinTimeToLive);
		result = 31 * result + Objects.hashCode(cacheNegativeTimeToLive);
		result = 31 * result + Boolean.hashCode(completeOncePreferredResolved);
		result = 31 * result + Boolean.hashCode(disableOptionalRecord);
		result = 31 * result + Boolean.hashCode(disableRecursionDesired);
		result = 31 * result + Objects.hashCode(dnsAddressResolverGroupProvider);
		result = 31 * result + Objects.hashCode(loggingFactory);
		result = 31 * result + Objects.hashCode(loopResources);
		result = 31 * result + maxPayloadSize;
		result = 31 * result + maxQueriesPerResolve;
		result = 31 * result + ndots;
		result = 31 * result + Boolean.hashCode(preferNative);
		result = 31 * result + Objects.hashCode(queryTimeout);
		result = 31 * result + Objects.hashCode(resolveCache);
		result = 31 * result + Objects.hashCode(resolvedAddressTypes);
		result = 31 * result + Boolean.hashCode(retryTcpOnTimeout);
		result = 31 * result + Boolean.hashCode(roundRobinSelection);
		result = 31 * result + Objects.hashCode(searchDomains);
		return result;
	}

	/**
	 * Provides a new {@link DnsAddressResolverGroup}.
	 *
	 * @param defaultLoopResources the default {@link LoopResources} when {@link LoopResources} is not specified
	 * @return a new {@link DnsAddressResolverGroup}
	 */
	public DnsAddressResolverGroup newNameResolverGroup(LoopResources defaultLoopResources, boolean defaultPreferNative) {
		Objects.requireNonNull(defaultLoopResources, "defaultLoopResources");
		LoopResources loop;
		EventLoopGroup group;
		if (loopResources == null) {
			loop = defaultLoopResources;
			group = loop.onClient(defaultPreferNative);
		}
		else {
			loop = loopResources;
			group = loop.onClient(preferNative);
		}
		DnsNameResolverBuilder builder = new DnsNameResolverBuilder()
				.ttl(Math.toIntExact(cacheMinTimeToLive.getSeconds()), Math.toIntExact(cacheMaxTimeToLive.getSeconds()))
				.negativeTtl(Math.toIntExact(cacheNegativeTimeToLive.getSeconds()))
				.completeOncePreferredResolved(completeOncePreferredResolved)
				.optResourceEnabled(!disableOptionalRecord)
				.recursionDesired(!disableRecursionDesired)
				.maxPayloadSize(maxPayloadSize)
				.maxQueriesPerResolve(maxQueriesPerResolve)
				.ndots(ndots)
				.queryTimeoutMillis(queryTimeout.toMillis())
				.eventLoop(group.next())
				.channelFactory(() -> loop.onChannel(DatagramChannel.class, group))
				.socketChannelFactory(() -> loop.onChannel(SocketChannel.class, group), retryTcpOnTimeout);
		if (bindAddressSupplier != null) {
			// There is no check for bindAddressSupplier.get() == null
			// This is deliberate, when null value is provided Netty will use the default behaviour
			builder.localAddress(bindAddressSupplier.get());
		}
		if (hostsFileEntriesResolver != null) {
			builder.hostsFileEntriesResolver(hostsFileEntriesResolver);
		}
		if (loggingFactory != null) {
			builder.dnsQueryLifecycleObserverFactory(loggingFactory);
		}
		if (resolveCache != null) {
			builder.resolveCache(resolveCache);
		}
		if (resolvedAddressTypes != null) {
			builder.resolvedAddressTypes(resolvedAddressTypes);
		}
		if (searchDomains != null) {
			builder.searchDomains(searchDomains);
		}
		if (dnsAddressResolverGroupProvider != null) {
			return dnsAddressResolverGroupProvider.apply(builder);
		}
		return roundRobinSelection ? new RoundRobinDnsAddressResolverGroup(builder) : new DnsAddressResolverGroup(builder);
	}

	final Supplier<? extends SocketAddress> bindAddressSupplier;
	final Duration cacheMaxTimeToLive;
	final Duration cacheMinTimeToLive;
	final Duration cacheNegativeTimeToLive;
	final boolean completeOncePreferredResolved;
	final boolean disableOptionalRecord;
	final boolean disableRecursionDesired;
	final Function<DnsNameResolverBuilder, DnsAddressResolverGroup> dnsAddressResolverGroupProvider;
	final HostsFileEntriesResolver hostsFileEntriesResolver;
	final DnsQueryLifecycleObserverFactory loggingFactory;
	final LoopResources loopResources;
	final int maxPayloadSize;
	final int maxQueriesPerResolve;
	final int ndots;
	final boolean preferNative;
	final Duration queryTimeout;
	final DnsCache resolveCache;
	final ResolvedAddressTypes resolvedAddressTypes;
	final boolean retryTcpOnTimeout;
	final boolean roundRobinSelection;
	final Iterable<String> searchDomains;

	NameResolverProvider(Build build) {
		this.bindAddressSupplier = build.bindAddressSupplier;
		this.cacheMaxTimeToLive = build.cacheMaxTimeToLive;
		this.cacheMinTimeToLive = build.cacheMinTimeToLive;
		this.cacheNegativeTimeToLive = build.cacheNegativeTimeToLive;
		this.completeOncePreferredResolved = build.completeOncePreferredResolved;
		this.disableOptionalRecord = build.disableOptionalRecord;
		this.disableRecursionDesired = build.disableRecursionDesired;
		this.dnsAddressResolverGroupProvider = build.dnsAddressResolverGroupProvider;
		this.hostsFileEntriesResolver = build.hostsFileEntriesResolver;
		this.loggingFactory = build.loggingFactory;
		this.loopResources = build.loopResources;
		this.maxPayloadSize = build.maxPayloadSize;
		this.maxQueriesPerResolve = build.maxQueriesPerResolve;
		this.ndots = build.ndots;
		this.preferNative = build.preferNative;
		this.queryTimeout = build.queryTimeout;
		this.resolveCache = build.resolveCache;
		this.resolvedAddressTypes = build.resolvedAddressTypes;
		this.retryTcpOnTimeout = build.retryTcpOnTimeout;
		this.roundRobinSelection = build.roundRobinSelection;
		this.searchDomains = build.searchDomains;
	}

	static final class Build implements NameResolverSpec {
		static final Duration DEFAULT_CACHE_MAX_TIME_TO_LIVE = Duration.ofSeconds(Integer.MAX_VALUE);
		static final Duration DEFAULT_CACHE_MIN_TIME_TO_LIVE = Duration.ofSeconds(0);
		static final Duration DEFAULT_CACHE_NEGATIVE_TIME_TO_LIVE = Duration.ofSeconds(0);
		static final boolean DEFAULT_COMPLETE_ONCE_PREFERRED_RESOLVED = true;
		static final int DEFAULT_MAX_PAYLOAD_SIZE = 4096;
		static final int DEFAULT_MAX_QUERIES_PER_RESOLVE = 16;
		static final int DEFAULT_NDOTS = -1;
		static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(5);

		Supplier<? extends SocketAddress> bindAddressSupplier;
		Duration cacheMaxTimeToLive = DEFAULT_CACHE_MAX_TIME_TO_LIVE;
		Duration cacheMinTimeToLive = DEFAULT_CACHE_MIN_TIME_TO_LIVE;
		Duration cacheNegativeTimeToLive = DEFAULT_CACHE_NEGATIVE_TIME_TO_LIVE;
		boolean completeOncePreferredResolved = DEFAULT_COMPLETE_ONCE_PREFERRED_RESOLVED;
		boolean disableOptionalRecord;
		boolean disableRecursionDesired;
		Function<DnsNameResolverBuilder, DnsAddressResolverGroup> dnsAddressResolverGroupProvider;
		HostsFileEntriesResolver hostsFileEntriesResolver;
		DnsQueryLifecycleObserverFactory loggingFactory;
		LoopResources loopResources;
		int maxPayloadSize = DEFAULT_MAX_PAYLOAD_SIZE;
		int maxQueriesPerResolve = DEFAULT_MAX_QUERIES_PER_RESOLVE;
		int ndots = DEFAULT_NDOTS;
		boolean preferNative = LoopResources.DEFAULT_NATIVE;
		Duration queryTimeout = DEFAULT_QUERY_TIMEOUT;
		DnsCache resolveCache;
		ResolvedAddressTypes resolvedAddressTypes;
		boolean retryTcpOnTimeout;
		boolean roundRobinSelection;
		Iterable<String> searchDomains;

		@Override
		public NameResolverSpec bindAddressSupplier(Supplier<? extends SocketAddress> bindAddressSupplier) {
			// If the default behaviour for bindAddress is the desired behaviour, one can provide a Supplier that returns null
			Objects.requireNonNull(bindAddressSupplier, "bindAddressSupplier");
			this.bindAddressSupplier = bindAddressSupplier;
			return this;
		}

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
		public NameResolverSpec completeOncePreferredResolved(boolean enable) {
			this.completeOncePreferredResolved = enable;
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
		public NameResolverSpec dnsAddressResolverGroupProvider(
				Function<DnsNameResolverBuilder, DnsAddressResolverGroup> dnsAddressResolverGroupProvider) {
			this.dnsAddressResolverGroupProvider = Objects.requireNonNull(dnsAddressResolverGroupProvider);
			return this;
		}

		@Override
		public NameResolverSpec hostsFileEntriesResolver(HostsFileEntriesResolver hostsFileEntriesResolver) {
			this.hostsFileEntriesResolver = Objects.requireNonNull(hostsFileEntriesResolver);
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
		public NameResolverSpec resolveCache(DnsCache resolveCache) {
			this.resolveCache = Objects.requireNonNull(resolveCache);
			return this;
		}

		@Override
		public NameResolverSpec resolvedAddressTypes(ResolvedAddressTypes resolvedAddressTypes) {
			this.resolvedAddressTypes = Objects.requireNonNull(resolvedAddressTypes);
			return this;
		}

		@Override
		public NameResolverSpec retryTcpOnTimeout(boolean enable) {
			this.retryTcpOnTimeout = enable;
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
		public NameResolverSpec trace(String category, LogLevel level) {
			Objects.requireNonNull(category, "category");
			Objects.requireNonNull(level, "level");
			this.loggingFactory = new LoggingDnsQueryLifeCycleObserverFactory(category, level);
			return this;
		}

		@Override
		public NameResolverProvider build() {
			return new NameResolverProvider(this);
		}
	}
}
