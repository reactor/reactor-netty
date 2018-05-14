/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.tcp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.NetUtil;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
final class TcpUtils {

	static Bootstrap updateProxySupport(Bootstrap b, ProxyProvider proxyOptions) {
		BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.ProxyHandler,
				(listener, channel) -> {
			if (proxyOptions.shouldProxy(b.config()
					.remoteAddress())) {

				channel.pipeline()
						.addFirst(NettyPipeline.ProxyHandler,
								proxyOptions.newProxyHandler());

				if (log.isDebugEnabled()) {
					channel.pipeline()
							.addFirst(new LoggingHandler("reactor.ipc.netty.proxy"));
				}
			}
		});

		if (b.config().resolver() == DefaultAddressResolverGroup.INSTANCE) {
			return b.resolver(NoopAddressResolverGroup.INSTANCE);
		}
		return b;
	}

	@SuppressWarnings("unchecked")
	static void convertLazyRemoteAddress(Bootstrap b) {
		SocketAddress remote = b.config().remoteAddress();

		Objects.requireNonNull(remote, "Remote Address not configured");

		if (remote instanceof Supplier) {
			Supplier<? extends SocketAddress> lazyRemote =
					(Supplier<? extends SocketAddress>) remote;

			b.remoteAddress(Objects.requireNonNull(lazyRemote.get(), "address supplier returned null"));
		}
	}

	@SuppressWarnings("unchecked")
	static void convertLazyLocalAddress(ServerBootstrap b) {
		SocketAddress local = b.config().localAddress();

		Objects.requireNonNull(local, "Remote Address not configured");

		if (local instanceof Supplier) {
			Supplier<? extends SocketAddress> lazyLocal =
					(Supplier<? extends SocketAddress>) local;

			b.localAddress(Objects.requireNonNull(lazyLocal.get(), "address supplier returned  null"));
		}

		if (local instanceof InetSocketAddress) {
			InetSocketAddress localInet = (InetSocketAddress)local;

			if (localInet.isUnresolved()){
				b.localAddress(InetSocketAddressUtil.createResolved(localInet.getHostName(), localInet.getPort()));
			}

		}
	}

	static ServerBootstrap updateSslSupport(ServerBootstrap b,
											SslProvider sslProvider) {

		BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.SslHandler,
				new SslSupportConsumer(sslProvider, b));

		return b;
	}

	static Bootstrap updateSslSupport(Bootstrap b,
									  SslProvider sslProvider) {

		BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.SslHandler,
				new SslSupportConsumer(sslProvider, b));

		return b;
	}

	@Nullable
	static SslContext findSslContext(Bootstrap b) {
		SslSupportConsumer c =
				BootstrapHandlers.findConfiguration(SslSupportConsumer.class,
						b.config().handler());

		return c != null ? c.sslProvider.getSslContext() : null;
	}

	@Nullable
	static SslContext findSslContext(ServerBootstrap b) {
		SslSupportConsumer c =
				BootstrapHandlers.findConfiguration(SslSupportConsumer.class,
						b.config().childHandler());

		return c != null ? c.sslProvider.getSslContext() : null;
	}

	static Bootstrap removeProxySupport(Bootstrap b) {
		BootstrapHandlers.removeConfiguration(b, NettyPipeline.ProxyHandler);
		return b;
	}

	static Bootstrap removeSslSupport(Bootstrap b) {
		BootstrapHandlers.removeConfiguration(b, NettyPipeline.SslHandler);
		return b;
	}

	static ServerBootstrap removeSslSupport(ServerBootstrap b) {
		BootstrapHandlers.removeConfiguration(b, NettyPipeline.SslHandler);
		return b;
	}

	static Bootstrap updateHost(Bootstrap b, String host) {
		return b.remoteAddress(_updateHost(b.config().remoteAddress(), host));
	}

	static ServerBootstrap updateHost(ServerBootstrap b, String host) {
		return b.localAddress(_updateHost(b.config().localAddress(), host));
	}

	static SocketAddress _updateHost(@Nullable SocketAddress address, String host) {
		if(address == null || !(address instanceof InetSocketAddress)) {
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
		if(address == null || !(address instanceof InetSocketAddress)) {
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

	static final class SslSupportConsumer
			implements BiConsumer<ConnectionObserver, Channel> {
		final SslProvider sslProvider;

		final InetSocketAddress sniInfo;

		SslSupportConsumer(SslProvider sslProvider,
						   AbstractBootstrap b) {
			this.sslProvider = sslProvider;

			if (b instanceof Bootstrap) {
				SocketAddress sniInfo = ((Bootstrap) b).config().remoteAddress();

				if (sniInfo instanceof InetSocketAddress) {
					this.sniInfo = (InetSocketAddress) sniInfo;
				}
				else {
					this.sniInfo = null;
				}

			}
			else {
				sniInfo = null;
			}
		}
		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			SslHandler sslHandler;

			if (sniInfo != null) {
				sslHandler = sslProvider.getSslContext().newHandler(channel.alloc(),
						sniInfo.getHostString(),
						sniInfo.getPort());

				if (log.isDebugEnabled()) {
					log.debug("SSL enabled using engine {} and SNI {}",
							sslHandler.engine().getClass().getSimpleName(),
							sniInfo);
				}
			}
			else {
				sslHandler = sslProvider.getSslContext().newHandler(channel.alloc());

				if (log.isDebugEnabled()) {
					log.debug("SSL enabled using engine {}",
							sslHandler.engine().getClass().getSimpleName());
				}
			}

			sslProvider.configure(sslHandler);

			if (channel.pipeline()
					.get(NettyPipeline.ProxyHandler) != null) {
				channel.pipeline()
						.addAfter(NettyPipeline.ProxyHandler,
								NettyPipeline.SslHandler,
								sslHandler);
			}
			else {
				channel.pipeline()
						.addFirst(NettyPipeline.SslHandler, sslHandler);
			}

			if (channel.pipeline()
					.get(NettyPipeline.LoggingHandler) != null) {
				channel.pipeline()
						.addAfter(NettyPipeline.LoggingHandler,
								NettyPipeline.SslReader,
								new SslReadHandler());
			}
			else {
				channel.pipeline()
						.addAfter(NettyPipeline.SslHandler,
								NettyPipeline.SslReader,
								new SslReadHandler());
			}
		}

	}

	static final class SslReadHandler extends ChannelInboundHandlerAdapter {

		boolean handshakeDone;

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			ctx.read(); //consume handshake
		}

		@Override
		public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
			if (!handshakeDone) {
				ctx.read(); /* continue consuming. */
			}
			super.channelReadComplete(ctx);
		}
		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
				throws Exception {
			if (evt instanceof SslHandshakeCompletionEvent) {
				handshakeDone = true;
				if (ctx.pipeline()
						.context(this) != null) {
					ctx.pipeline()
							.remove(this);
				}
				SslHandshakeCompletionEvent handshake = (SslHandshakeCompletionEvent) evt;
				if (handshake.isSuccess()) {
					ctx.fireChannelActive();
				}
				else {
					ctx.fireExceptionCaught(handshake.cause());
				}
			}
			super.userEventTriggered(ctx, evt);
		}

	}

	static final class SocketAddressSupplier extends SocketAddress implements
	                                                               Supplier<SocketAddress> {
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
			(ch, c, msg) -> new ChannelOperations<>(ch, c).bind();

	static final Consumer<SslProvider.SslContextSpec> SSL_DEFAULT_SPEC =
			sslProviderBuilder -> sslProviderBuilder.sslContext(SslProvider.DEFAULT_SERVER_CONTEXT);
}
