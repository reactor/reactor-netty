/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
package reactor.netty.tcp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.NetUtil;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
final class TcpUtils {

	static Bootstrap updateProxySupport(Bootstrap b, ProxyProvider proxyOptions) {
		BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.ProxyHandler,
				new ProxyProvider.DeferredProxySupport(proxyOptions));

		if (b.config().resolver() == DefaultAddressResolverGroup.INSTANCE) {
			return b.resolver(NoopAddressResolverGroup.INSTANCE);
		}
		return b;
	}

	static Bootstrap removeProxySupport(Bootstrap b) {
		return BootstrapHandlers.removeConfiguration(b, NettyPipeline.ProxyHandler);
	}

	static ServerBootstrap updateMetricsSupport(ServerBootstrap b, @Nullable String name) {
		return BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.TcpMetricsHandler,
				new MetricsSupportConsumer(name == null ? SERVER_METRICS_NAME : name, true));
	}

	static Bootstrap updateMetricsSupport(Bootstrap b, @Nullable String name) {
		return BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.TcpMetricsHandler,
				new DeferredMetricsSupport(name == null ? CLIENT_METRICS_NAME : name, false));
	}

	static ServerBootstrap removeMetricsSupport(ServerBootstrap b) {
		return BootstrapHandlers.removeConfiguration(b, NettyPipeline.TcpMetricsHandler);
	}

	static Bootstrap removeMetricsSupport(Bootstrap b) {
		return BootstrapHandlers.removeConfiguration(b, NettyPipeline.TcpMetricsHandler);
	}

	static Bootstrap updateHost(Bootstrap b, String host) {
		return b.remoteAddress(_updateHost(b.config().remoteAddress(), host));
	}

	static ServerBootstrap updateHost(ServerBootstrap b, String host) {
		return b.localAddress(_updateHost(b.config().localAddress(), host));
	}

	static SocketAddress _updateHost(@Nullable SocketAddress address, String host) {
		if(!(address instanceof InetSocketAddress)) {
			return InetSocketAddressUtil.createUnresolved(host, 0);
		}

		InetSocketAddress inet = (InetSocketAddress)address;

		return InetSocketAddressUtil.createUnresolved(host, inet.getPort());
	}

	static Bootstrap updatePort(Bootstrap b, int port) {
		return b.remoteAddress(_updatePort(b.config().remoteAddress(), port));
	}

	static ServerBootstrap updatePort(ServerBootstrap b, int port) {
		return b.localAddress(_updatePort(b.config().localAddress(), port));
	}

	static SocketAddress _updatePort(@Nullable SocketAddress address, int port) {
		if(!(address instanceof InetSocketAddress)) {
			return InetSocketAddressUtil.createUnresolved(NetUtil.LOCALHOST.getHostAddress(), port);
		}

		InetSocketAddress inet = (InetSocketAddress)address;

		InetAddress addr = inet.getAddress();

		String host = addr == null ? inet.getHostName() : addr.getHostAddress();

		return InetSocketAddressUtil.createUnresolved(host, port);
	}

	static SocketAddressSupplier lazyAddress(Supplier<? extends SocketAddress> supplier) {
		return new SocketAddressSupplier(supplier);
	}

	static final class SocketAddressSupplier extends SocketAddress implements Supplier<SocketAddress> {
		final Supplier<? extends SocketAddress> supplier;

		SocketAddressSupplier(Supplier<? extends SocketAddress> supplier) {
			this.supplier = Objects.requireNonNull(supplier, "Lazy address supplier must not be null");
		}

		@Override
		public SocketAddress get() {
			return supplier.get();
		}
	}

	static final Logger log = Loggers.getLogger(TcpUtils.class);

	static final ChannelOperations.OnSetup TCP_OPS =
			(ch, c, msg) -> new ChannelOperations<>(ch, c);


	static final String SERVER_METRICS_NAME = "reactor.netty.tcp.server";

	static final String CLIENT_METRICS_NAME = "reactor.netty.tcp.client";

	static final class DeferredMetricsSupport
			implements Function<Bootstrap, BiConsumer<ConnectionObserver, Channel>> {

		final String name;

		final boolean onServer;

		DeferredMetricsSupport(String name, boolean onServer) {
			this.name = name;
			this.onServer = onServer;
		}

		@Override
		public BiConsumer<ConnectionObserver, Channel> apply(Bootstrap bootstrap) {
			String address;
			SocketAddress socketAddress = bootstrap.config().remoteAddress();
			if (socketAddress instanceof InetSocketAddress) {
				address = ((InetSocketAddress) socketAddress).getHostString();
			}
			else {
				address = socketAddress.toString();
			}
			return new MetricsSupportConsumer(name, address, onServer);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			DeferredMetricsSupport that = (DeferredMetricsSupport) o;
			return onServer == that.onServer && name.equals(that.name);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, onServer);
		}
	}

	static final class MetricsSupportConsumer
			implements BiConsumer<ConnectionObserver, Channel> {

		final String name;

		final String remoteAddress;

		final boolean onServer;

		MetricsSupportConsumer(String name, boolean onServer) {
			this(name, null, onServer);
		}

		MetricsSupportConsumer(String name, @Nullable String remoteAddress, boolean onServer) {
			this.name = name;
			this.remoteAddress = remoteAddress;
			this.onServer = onServer;
		}

		@Override
		public void accept(ConnectionObserver connectionObserver, Channel channel) {
			//TODO check all other handlers and add this always as first
			//SSL, Proxy, ProxyProtocol
			//TODO or behind the proxy?
			String address = remoteAddress;
			if (address == null) {
				SocketAddress socketAddress = channel.remoteAddress();
				if (socketAddress instanceof InetSocketAddress) {
					address = ((InetSocketAddress) socketAddress).getHostString();
				}
				else {
					address = socketAddress.toString();
				}
			}

			channel.pipeline()
			       .addFirst(NettyPipeline.TcpMetricsHandler,
			                 new TcpMetricsHandler(name,
			                                       //Check the remote address is it on the proxy or not
			                                       address,
			                                       onServer));
		}
	}
}
