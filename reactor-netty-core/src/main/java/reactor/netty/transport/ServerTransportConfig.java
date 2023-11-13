/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.net.SocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.unix.ServerDomainSocketChannel;
import io.netty.util.AttributeKey;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.util.annotation.Nullable;

import static reactor.netty.ReactorNetty.format;

/**
 * Encapsulate all necessary configuration for server transport. The public API is read-only.
 *
 * @param <CONF> Configuration implementation
 * @author Stephane Maldini
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public abstract class ServerTransportConfig<CONF extends TransportConfig> extends TransportConfig {

	/**
	 * Return the read-only default channel attributes for each remote connection.
	 *
	 * @return the read-only default channel attributes for each remote connection
	 */
	public final Map<AttributeKey<?>, ?> childAttributes() {
		if (childAttrs == null) {
			return Collections.emptyMap();
		}
		return Collections.unmodifiableMap(childAttrs);
	}

	/**
	 * Return the configured {@link ConnectionObserver} if any or
	 * {@link ConnectionObserver#emptyListener()} for each remote connection.
	 *
	 * @return the configured {@link ConnectionObserver} if any or
	 * {@link ConnectionObserver#emptyListener()} for each remote connection
	 */
	public final ConnectionObserver childObserver() {
		return childObserver;
	}

	/**
	 * Return the read-only {@link ChannelOption} map for each remote connection.
	 *
	 * @return the read-only {@link ChannelOption} map for each remote connection
	 */
	public final Map<ChannelOption<?>, ?> childOptions() {
		if (childOptions == null) {
			return Collections.emptyMap();
		}
		return Collections.unmodifiableMap(childOptions);
	}

	/**
	 * Return the configured callback or null.
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super CONF> doOnBind() {
		return doOnBind;
	}

	/**
	 * Return the configured callback or null.
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super DisposableServer> doOnBound() {
		return doOnBound;
	}

	/**
	 * Return the configured callback or null.
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super Connection> doOnConnection() {
		return doOnConnection;
	}

	/**
	 * Return the configured callback or null.
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super DisposableServer> doOnUnbound() {
		return doOnUnbound;
	}


	// Protected/Package private write API

	Map<AttributeKey<?>, ?>            childAttrs;
	ConnectionObserver                 childObserver;
	Map<ChannelOption<?>, ?>           childOptions;
	Consumer<? super CONF>             doOnBind;
	Consumer<? super DisposableServer> doOnBound;
	Consumer<? super Connection>       doOnConnection;
	Consumer<? super DisposableServer> doOnUnbound;

	/**
	 * Default ServerTransportConfig with options.
	 *
	 * @param options default options for the selector
	 * @param childOptions default options for each connected channel
	 * @param bindAddress the local address
	 */
	protected ServerTransportConfig(Map<ChannelOption<?>, ?> options, Map<ChannelOption<?>, ?> childOptions,
				Supplier<? extends SocketAddress> bindAddress) {
		super(options, bindAddress);
		this.childAttrs = Collections.emptyMap();
		this.childObserver = ConnectionObserver.emptyListener();
		this.childOptions = Objects.requireNonNull(childOptions, "childOptions");
	}

	protected ServerTransportConfig(ServerTransportConfig<CONF> parent) {
		super(parent);
		this.childAttrs = parent.childAttrs;
		this.childObserver = parent.childObserver;
		this.childOptions = parent.childOptions;
		this.doOnBind = parent.doOnBind;
		this.doOnBound = parent.doOnBound;
		this.doOnConnection = parent.doOnConnection;
		this.doOnUnbound = parent.doOnUnbound;
	}

	@Override
	protected Class<? extends Channel> channelType(boolean isDomainSocket) {
		return isDomainSocket ? ServerDomainSocketChannel.class : ServerSocketChannel.class;
	}

	/**
	 * Return the configured child lifecycle {@link ConnectionObserver} if any or {@link ConnectionObserver#emptyListener()}.
	 *
	 * @return the configured child lifecycle {@link ConnectionObserver} if any or {@link ConnectionObserver#emptyListener()}
	 */
	protected ConnectionObserver defaultChildObserver() {
		if (channelGroup() == null && doOnConnection() == null) {
			return ConnectionObserver.emptyListener();
		}
		else {
			return new ServerTransportDoOnConnection(channelGroup(), doOnConnection());
		}
	}

	@Override
	protected ConnectionObserver defaultConnectionObserver() {
		if (doOnBound() == null && doOnUnbound() == null) {
			return ConnectionObserver.emptyListener();
		}
		return new ServerTransportDoOn(doOnBound(), doOnUnbound());
	}

	@Override
	protected ChannelPipelineConfigurer defaultOnChannelInit() {
		return ChannelPipelineConfigurer.emptyConfigurer();
	}

	@Override
	protected final EventLoopGroup eventLoopGroup() {
		return loopResources().onServerSelect(isPreferNative());
	}

	/**
	 * Return the configured {@link EventLoopGroup} used for the remote connection.
	 *
	 * @return the configured {@link EventLoopGroup} used for the remote connection.
	 */
	final EventLoopGroup childEventLoopGroup() {
		return loopResources().onServer(isPreferNative());
	}

	static final class ServerTransportDoOn implements ConnectionObserver {

		final Consumer<? super DisposableServer> doOnBound;
		final Consumer<? super DisposableServer> doOnUnbound;

		ServerTransportDoOn(@Nullable Consumer<? super DisposableServer> doOnBound,
				@Nullable Consumer<? super DisposableServer> doOnUnbound) {
			this.doOnBound = doOnBound;
			this.doOnUnbound = doOnUnbound;
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == State.CONNECTED) {
				if (doOnBound != null) {
					doOnBound.accept((DisposableServer) connection);
				}
				if (doOnUnbound != null) {
					connection.channel()
					          .closeFuture()
					          .addListener(f -> doOnUnbound.accept((DisposableServer) connection));
				}
			}
		}
	}

	static final class ServerTransportDoOnConnection implements ConnectionObserver {

		final ChannelGroup                 channelGroup;
		final Consumer<? super Connection> doOnConnection;

		ServerTransportDoOnConnection(@Nullable ChannelGroup channelGroup, @Nullable Consumer<? super Connection> doOnConnection) {
			this.channelGroup = channelGroup;
			this.doOnConnection = doOnConnection;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void onStateChange(Connection connection, State newState) {
			if (channelGroup != null && newState == State.CONNECTED) {
				channelGroup.add(connection.channel());
				return;
			}
			if (doOnConnection != null && newState == State.CONFIGURED) {
				try {
					doOnConnection.accept(connection);
				}
				catch (Throwable t) {
					log.error(format(connection.channel(), ""), t);
					//"FutureReturnValueIgnored" this is deliberate
					connection.channel().close();
				}
			}
		}
	}
}