/*
 * Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.udp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.unix.DomainDatagramChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.jspecify.annotations.Nullable;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.TransportConfig;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Encapsulate all necessary configuration for UDP server transport. The public API is read-only.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class UdpServerConfig extends TransportConfig {

	@Override
	public ChannelOperations.OnSetup channelOperationsProvider() {
		return DEFAULT_OPS;
	}

	/**
	 * Return the configured callback or null.
	 *
	 * @return the configured callback or null
	 */
	public final @Nullable Consumer<? super UdpServerConfig> doOnBind() {
		return doOnBind;
	}

	/**
	 * Return the configured callback or null.
	 *
	 * @return the configured callback or null
	 */
	public final @Nullable Consumer<? super Connection> doOnBound() {
		return doOnBound;
	}

	/**
	 * Return the configured callback or null.
	 *
	 * @return the configured callback or null
	 */
	public final @Nullable Consumer<? super Connection> doOnUnbound() {
		return doOnUnbound;
	}

	/**
	 * Return the configured {@link InternetProtocolFamily} to run with or null.
	 *
	 * @return the configured {@link InternetProtocolFamily} to run with or null
	 * @deprecated as of 1.3.0. Prefer {@link #socketFamily()}. This method will be removed in version 1.4.0.
	 */
	@Deprecated
	public final @Nullable InternetProtocolFamily family() {
		return family;
	}

	/**
	 * Return the configured {@link SocketProtocolFamily} to run with or null.
	 *
	 * @return the configured {@link SocketProtocolFamily} to run with or null
	 * @since 1.3.0
	 */
	public final @Nullable SocketProtocolFamily socketFamily() {
		return socketFamily;
	}


	// Protected/Package private write API

	@Nullable Consumer<? super UdpServerConfig> doOnBind;
	@Nullable Consumer<? super Connection>      doOnBound;
	@Nullable Consumer<? super Connection>      doOnUnbound;
	@SuppressWarnings("deprecation")
	@Nullable InternetProtocolFamily            family;
	@Nullable SocketProtocolFamily              socketFamily;

	UdpServerConfig(Map<ChannelOption<?>, ?> options, Supplier<? extends SocketAddress> bindAddress) {
		super(options, bindAddress);
	}

	UdpServerConfig(UdpServerConfig parent) {
		super(parent);
		this.doOnBind = parent.doOnBind;
		this.doOnBound = parent.doOnBound;
		this.doOnUnbound = parent.doOnUnbound;
		this.family = parent.family;
		this.socketFamily = parent.socketFamily;
	}

	@Override
	protected Class<? extends Channel> channelType(boolean isDomainSocket) {
		return isDomainSocket ? DomainDatagramChannel.class : DatagramChannel.class;
	}

	@Override
	protected ChannelFactory<? extends Channel> connectionFactory(EventLoopGroup elg, boolean isDomainSocket) {
		if (isPreferNative()) {
			return super.connectionFactory(elg, isDomainSocket);
		}
		else {
			return () -> new NioDatagramChannel(socketFamily());
		}
	}

	@Override
	protected ConnectionObserver defaultConnectionObserver() {
		if (channelGroup() == null && doOnBound() == null && doOnUnbound() == null) {
			return ConnectionObserver.emptyListener();
		}
		return new UdpServerDoOn(channelGroup(), doOnBound(), doOnUnbound());
	}

	@Override
	protected LoggingHandler defaultLoggingHandler() {
		return LOGGING_HANDLER;
	}

	@Override
	protected LoopResources defaultLoopResources() {
		return UdpResources.get();
	}

	@Override
	protected ChannelMetricsRecorder defaultMetricsRecorder() {
		return MicrometerUdpServerMetricsRecorder.INSTANCE;
	}

	@Override
	protected ChannelPipelineConfigurer defaultOnChannelInit() {
		return ChannelPipelineConfigurer.emptyConfigurer();
	}

	@Override
	protected EventLoopGroup eventLoopGroup() {
		return loopResources().onClient(isPreferNative());
	}

	static final ChannelOperations.OnSetup DEFAULT_OPS = (ch, c, msg) -> new UdpOperations(ch, c);

	static final LoggingHandler LOGGING_HANDLER =
			AdvancedByteBufFormat.HEX_DUMP
					.toLoggingHandler(UdpServer.class.getName(), LogLevel.DEBUG, Charset.defaultCharset());

	static final class MicrometerUdpServerMetricsRecorder extends MicrometerChannelMetricsRecorder {

		static final MicrometerUdpServerMetricsRecorder INSTANCE = new MicrometerUdpServerMetricsRecorder();

		MicrometerUdpServerMetricsRecorder() {
			super(reactor.netty.Metrics.UDP_SERVER_PREFIX, "udp");
		}
	}

	static final class UdpServerDoOn implements ConnectionObserver {

		final @Nullable ChannelGroup                 channelGroup;
		final @Nullable Consumer<? super Connection> doOnBound;
		final @Nullable Consumer<? super Connection> doOnUnbound;

		UdpServerDoOn(@Nullable ChannelGroup channelGroup,
				@Nullable Consumer<? super Connection> doOnBound,
				@Nullable Consumer<? super Connection> doOnUnbound) {
			this.channelGroup = channelGroup;
			this.doOnBound = doOnBound;
			this.doOnUnbound = doOnUnbound;
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (channelGroup != null && newState == State.CONNECTED) {
				channelGroup.add(connection.channel());
				return;
			}
			if (doOnBound != null && newState == State.CONFIGURED) {
				doOnBound.accept(connection);
				return;
			}
			if (doOnUnbound != null && newState == State.DISCONNECTING) {
				connection.onDispose(() -> doOnUnbound.accept(connection));
			}
		}
	}
}
