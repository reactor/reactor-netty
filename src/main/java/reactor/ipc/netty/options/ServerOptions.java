/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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
 */
public class ServerOptions extends NettyOptions<ServerBootstrap, ServerOptions> {

	/**
	 * Create a new server builder
	 * @return a new server builder
	 */
	public static ServerOptions create() {
		return new ServerOptions();
	}

	static void defaultServerOptions(ServerBootstrap bootstrap) {
		bootstrap.localAddress(LOCALHOST_AUTO_PORT)
		         .option(ChannelOption.SO_REUSEADDR, true)
		         .option(ChannelOption.SO_BACKLOG, 1000)
		         .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
		         .childOption(ChannelOption.SO_RCVBUF, 1024 * 1024)
		         .childOption(ChannelOption.SO_SNDBUF, 1024 * 1024)
		         .childOption(ChannelOption.AUTO_READ, false)
		         .childOption(ChannelOption.SO_KEEPALIVE, true)
		         .childOption(ChannelOption.SO_LINGER, 0)
		         .childOption(ChannelOption.TCP_NODELAY, true)
		         .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000);
	}

	/**
	 * Build a new {@link ServerBootstrap}.
	 */
	protected ServerOptions() {
		this(new ServerBootstrap());
	}

	/**
	 * Apply common option via super constructor then apply
	 * {@link #defaultServerOptions(ServerBootstrap)}
	 * to the passed bootstrap.
	 *
	 * @param serverBootstrap the server bootstrap reference to use
	 */
	protected ServerOptions(ServerBootstrap serverBootstrap) {
		super(serverBootstrap);
		defaultServerOptions(serverBootstrap);
	}

	/**
	 * Deep-copy all references from the passed options into this new
	 * {@link ServerOptions} instance.
	 *
	 * @param options the source options to duplicate
	 */
	protected ServerOptions(ServerOptions options) {
		super(options);
	}

	/**
	 * Attribute default attribute to the future {@link Channel} connection. They will
	 * be available via {@link reactor.ipc.netty.NettyInbound#attr(AttributeKey)}.
	 *
	 * @param key the attribute key
	 * @param value the attribute value
	 * @param <T> the attribute type
	 * @return this builder
	 *
	 * @see ServerBootstrap#childAttr(AttributeKey, Object)
	 */
	@Override
	public <T> ServerOptions attr(AttributeKey<T> key, T value) {
		bootstrapTemplate.childAttr(key, value);
		return this;
	}

	@Override
	public ServerOptions duplicate() {
		return new ServerOptions(this);
	}

	@Override
	public ServerBootstrap get() {
		ServerBootstrap b = super.get();
		groupAndChannel(b);
		return b;
	}

	/**
	 * The port on which this server should listen, assuming it should bind to all available addresses.
	 *
	 * @param port The port to listen on.
	 * @return {@literal this}
	 */
	public ServerOptions listen(int port) {
		return listen(new InetSocketAddress(port));
	}

	/**
	 * The host on which this server should listen, port will be resolved on bind.
	 *
	 * @param host The host to bind to.
	 *
	 * @return {@literal this}
	 */
	public ServerOptions listen(String host) {
		if (null == host) {
			host = "localhost";
		}
		return listen(new InetSocketAddress(host, 0));
	}

	/**
	 * The host and port on which this server should listen.
	 *
	 * @param host The host to bind to.
	 * @param port The port to listen on.
	 * @return {@literal this}
	 */
	public ServerOptions listen(String host, int port) {
		if (null == host) {
			host = "localhost";
		}
		return listen(new InetSocketAddress(host, port));
	}

	/**
	 * The {@link InetSocketAddress} on which this server should listen.
	 *
	 * @param listenAddress the listen address
	 * @return {@literal this}
	 */
	public ServerOptions listen(InetSocketAddress listenAddress) {
		Objects.requireNonNull(listenAddress, "listenAddress");
		bootstrapTemplate.localAddress(listenAddress);
		return this;
	}

	/**
	 * Set a {@link ChannelOption} value for low level connection settings like
	 * SO_TIMEOUT or SO_KEEPALIVE. This will apply to each new channel from remote
	 * peer.
	 *
	 * @param key the option key
	 * @param <T> the option type
	 *
	 * @return {@code this}
	 * @see ServerBootstrap#childOption(ChannelOption, Object)
	 */
	@Override
	public <T> ServerOptions option(ChannelOption<T> key, T value) {
		bootstrapTemplate.childOption(key, value);
		return this;
	}

	/**
	 * Attribute default attribute to the future {@link Channel} connection. They will
	 * be available via {@link reactor.ipc.netty.NettyInbound#attr(AttributeKey)}.
	 *
	 * @param key the attribute key
	 * @param value the attribute value
	 * @param <T> the attribute type
	 * @return this builder
	 *
	 * @see ServerBootstrap#attr(AttributeKey, Object)
	 */
	public <T> ServerOptions selectorAttr(AttributeKey<T> key, T value) {
		bootstrapTemplate.childAttr(key, value);
		return this;
	}

	/**
	 * Set a {@link ChannelOption} value for low level selector channel settings like
	 * SO_TIMEOUT or SO_KEEPALIVE. This will apply to each new channel from remote
	 * peer.
	 *
	 * @param key the option key
	 * @param <T> the option type
	 *
	 * @return {@code this}
	 *
	 * @see ServerBootstrap#childOption(ChannelOption, Object)
	 */
	public <T> ServerOptions selectorOption(ChannelOption<T> key, T value) {
		bootstrapTemplate.childOption(key, value);
		return this;
	}

	/**
	 * Enable SSL service with a self-signed certificate
	 *
	 * @return {@code this}
	 */
	public ServerOptions sslSelfSigned() {
		return sslSelfSigned(c -> {
		});
	}

	/**
	 * Enable SSL service with a self-signed certificate and allows extra
	 * parameterization of the self signed {@link SslContextBuilder}. The builder is
	 * then used to invoke {@link #sslContext(SslContext)}.
	 *
	 * @param configurator the builder callback to setup the self-signed {@link SslContextBuilder}
	 *
	 * @return {@code this}
	 */
	public ServerOptions sslSelfSigned(Consumer<? super SslContextBuilder> configurator) {
		Objects.requireNonNull(configurator, "configurator");
		SelfSignedCertificate ssc;
		try {
			ssc = new SelfSignedCertificate();
			SslContextBuilder builder =
					SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey());
			configurator.accept(builder);
			return sslContext(builder.build());
		}
		catch (Exception sslException) {
			throw Exceptions.bubble(sslException);
		}
	}

	final void groupAndChannel(ServerBootstrap bootstrap) {
		LoopResources loops =
				Objects.requireNonNull(this.loopResources, "loopResources");

		boolean useNative = preferNative && !(sslContext instanceof JdkSslContext);
		final EventLoopGroup selectorGroup = loops.onServerSelect(useNative);
		final EventLoopGroup elg = loops.onServer(useNative);

		bootstrap.group(selectorGroup, elg)
		         .channel(loops.onServerChannel(elg));
	}

	final static InetSocketAddress LOCALHOST_AUTO_PORT = new InetSocketAddress(0);
}
