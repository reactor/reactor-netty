/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.options;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.Consumer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AttributeKey;
import reactor.core.Exceptions;
import reactor.ipc.netty.resources.LoopResources;

/**
 * Encapsulates configuration options for server connectors.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public class ServerOptions extends NettyOptions<ServerBootstrap, ServerOptions> {

	/**
	 * Creates a builder for {@link ServerOptions ServerOptions}
	 *
	 * @param <BUILDER> A ServerOptions.Builder subclass
	 * @return a new ServerOptions builder
	 */
	public static <BUILDER extends ServerOptions.Builder<BUILDER>> ServerOptions.Builder<BUILDER> builder() {
		return builder(new ServerBootstrap());
	}

	/**
	 * Creates a builder for {@link ServerOptions ServerOptions}
	 *
	 * @param serverBootstrap the server bootstrap reference to use
	 * @param <BUILDER> A ServerOptions.Builder subclass
	 * @return a new ServerOptions builder
	 */
	public static <BUILDER extends ServerOptions.Builder<BUILDER>> ServerOptions.Builder<BUILDER> builder(ServerBootstrap serverBootstrap) {
		return new ServerOptions.Builder<>(serverBootstrap);
	}

	private final SocketAddress localAddress;

	/**
	 * Build a new {@link ServerOptions}.
	 */
	protected ServerOptions(ServerOptions.Builder<?> builder) {
		super(builder);
		if (Objects.isNull(builder.listenAddress)) {
			if (Objects.isNull(builder.host)) {
				this.localAddress = new InetSocketAddress(builder.port);
			}
			else {
				this.localAddress = InetSocketAddressUtil.createResolved(builder.host, builder.port);
			}
		}
		else {
			this.localAddress = builder.listenAddress instanceof InetSocketAddress
					? InetSocketAddressUtil.replaceWithResolved((InetSocketAddress) builder.listenAddress)
					: builder.listenAddress;
		}
	}

	@Override
	public ServerOptions duplicate() {
		return builder().from(this).build();
	}

	@Override
	public ServerBootstrap get() {
		ServerBootstrap b = super.get();
		groupAndChannel(b);
		return b;
	}

	@Override
	public final SocketAddress getAddress() {
		return localAddress;
	}

	final void groupAndChannel(ServerBootstrap bootstrap) {
		LoopResources loops =
				Objects.requireNonNull(getLoopResources(), "loopResources");

		boolean useNative = preferNative() && !(sslContext() instanceof JdkSslContext);
		final EventLoopGroup selectorGroup = loops.onServerSelect(useNative);
		final EventLoopGroup elg = loops.onServer(useNative);

		bootstrap.group(selectorGroup, elg)
		         .channel(loops.onServerChannel(elg));
	}

	@Override
	public String asSimpleString() {
		return "listening on " + this.getAddress();
	}

	@Override
	public String asDetailedString() {
		return "address=" + getAddress() + ", " + super.asDetailedString();
	}

	@Override
	public String toString() {
		return "ServerOptions{" + asDetailedString() + "}";
	}

	public static class Builder<BUILDER extends Builder<BUILDER>>
			extends NettyOptions.Builder<ServerBootstrap, ServerOptions, BUILDER> {
		private String host;
		private int port;
		private SocketAddress listenAddress;

		/**
		 * Apply common option via super class then apply
		 * {@link #defaultServerOptions(ServerBootstrap)}
		 * to the passed bootstrap.
		 *
		 * @param serverBootstrap the server bootstrap reference to use
		 */
		protected Builder(ServerBootstrap serverBootstrap) {
			super(serverBootstrap);
			defaultServerOptions(serverBootstrap);
		}

		private final void defaultServerOptions(ServerBootstrap bootstrap) {
			bootstrap.option(ChannelOption.SO_REUSEADDR, true)
			         .option(ChannelOption.SO_BACKLOG, 1000)
			         .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
			         .childOption(ChannelOption.SO_RCVBUF, 1024 * 1024)
			         .childOption(ChannelOption.SO_SNDBUF, 1024 * 1024)
			         .childOption(ChannelOption.AUTO_READ, false)
			         .childOption(ChannelOption.SO_KEEPALIVE, true)
			         .childOption(ChannelOption.TCP_NODELAY, true)
			         .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000);
		}

		/**
		 * Attribute default attribute to the future {@link Channel} connection. They will
		 * be available via {@link Channel#attr(AttributeKey)}.
		 *
		 * @param key the attribute key
		 * @param value the attribute value
		 * @param <T> the attribute type
		 * @return {@code this}
		 * @see ServerBootstrap#childAttr(AttributeKey, Object)
		 */
		@Override
		public final <T> BUILDER attr(AttributeKey<T> key, T value) {
			this.bootstrapTemplate.childAttr(key, value);
			return get();
		}

		/**
		 * Set a {@link ChannelOption} value for low level connection settings like
		 * SO_TIMEOUT or SO_KEEPALIVE. This will apply to each new channel from remote
		 * peer.
		 *
		 * @param key the option key
		 * @param <T> the option type
		 * @return {@code this}
		 * @see ServerBootstrap#childOption(ChannelOption, Object)
		 */
		@Override
		public final <T> BUILDER option(ChannelOption<T> key, T value) {
			this.bootstrapTemplate.childOption(key, value);
			return get();
		}

		/**
		 * Attribute default attribute to the future {@link Channel} connection. They will
		 * be available via {@link Channel#attr(AttributeKey)}.
		 *
		 * @param key the attribute key
		 * @param value the attribute value
		 * @param <T> the attribute type
		 * @return this builder
		 * @see ServerBootstrap#childAttr(AttributeKey, Object)
		 */
		public final <T> BUILDER selectorAttr(AttributeKey<T> key, T value) {
			attr(key, value);
			return get();
		}

		/**
		 * Set a {@link ChannelOption} value for low level selector channel settings like
		 * SO_TIMEOUT or SO_KEEPALIVE. This will apply to each new channel from remote
		 * peer.
		 *
		 * @param key the option key
		 * @param value the option value
		 * @param <T> the option type
		 * @return {@code this}
		 * @see ServerBootstrap#childOption(ChannelOption, Object)
		 */
		public final <T> BUILDER selectorOption(ChannelOption<T> key, T value) {
			option(key, value);
			return get();
		}

		/**
		 * The host on which this server should listen.
		 *
		 * @param host The host to bind to.
		 * @return {@code this}
		 */
		public final BUILDER host(String host) {
			if (Objects.isNull(host)) {
				this.host = "localhost";
			}
			else {
				this.host = host;
			}
			return get();
		}

		/**
		 * The port on which this server should listen, assuming it should bind to all available addresses.
		 *
		 * @param port The port to listen on.
		 * @return {@code this}
		 */
		public final BUILDER port(int port) {
			this.port = Objects.requireNonNull(port, "port");
			return get();
		}

		/**
		 * The {@link InetSocketAddress} on which this server should listen.
		 *
		 * @param listenAddress the listen address
		 * @return {@code this}
		 */
		public final BUILDER listenAddress(SocketAddress listenAddress) {
			this.listenAddress = Objects.requireNonNull(listenAddress, "listenAddress");
			return get();
		}

		/**
		 * Enable SSL service with a self-signed certificate
		 *
		 * @return {@code this}
		 */
		public final BUILDER sslSelfSigned() {
			return sslSelfSigned(c -> {});
		}

		/**
		 * Enable SSL service with a self-signed certificate and allows extra
		 * parameterization of the self signed {@link SslContextBuilder}. The builder is
		 * then used to invoke {@link #sslContext(SslContext)}.
		 *
		 * @param configurator the builder callback to setup the self-signed {@link SslContextBuilder}
		 * @return {@code this}
		 */
		public final BUILDER sslSelfSigned(Consumer<? super SslContextBuilder> configurator) {
			Objects.requireNonNull(configurator, "configurator");
			SelfSignedCertificate ssc;
			try {
				ssc = new SelfSignedCertificate();
				SslContextBuilder builder =
						SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey());
				configurator.accept(builder);
				sslContext(builder.build());
				return get();
			}
			catch (Exception sslException) {
				throw Exceptions.bubble(sslException);
			}
		}

		@Override
		public final BUILDER from(ServerOptions options) {
			super.from(options);
			this.listenAddress = options.localAddress;
			return get();
		}

		@Override
		@SuppressWarnings("unchecked")
		public BUILDER get() {
			return (BUILDER) this;
		}

		public ServerOptions build() {
			return new ServerOptions(this);
		}
	}
}
