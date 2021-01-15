/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.quic;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.logging.LoggingHandler;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.incubator.codec.quic.QuicSslContext;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Encapsulate all necessary configuration for QUIC client transport. The public API is read-only.
 *
 * @author Violeta Georgieva
 */
public final class QuicClientConfig extends QuicTransportConfig<QuicClientConfig> {

	/**
	 * Name prefix that will be used for the QUIC client's metrics
	 * registered in Micrometer's global registry
	 */
	public static final String QUIC_CLIENT_PREFIX = "reactor.netty.quic.client";

	Consumer<? super QuicClientConfig> doOnConnect;
	Consumer<? super QuicConnection>   doOnConnected;
	Consumer<? super QuicConnection>   doOnDisconnected;
	Supplier<? extends SocketAddress>  remoteAddress;

	QuicClientConfig(
			Map<ChannelOption<?>, ?> options,
			Map<ChannelOption<?>, ?> streamOptions,
			Supplier<? extends SocketAddress> bindAddress,
			Supplier<? extends SocketAddress> remoteAddress) {
		super(options, streamOptions, bindAddress);
		this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
	}

	QuicClientConfig(QuicClientConfig parent) {
		super(parent);
		this.doOnConnect = parent.doOnConnect;
		this.doOnConnected = parent.doOnConnected;
		this.doOnDisconnected = parent.doOnDisconnected;
		this.remoteAddress = parent.remoteAddress;
	}

	/**
	 * Return the configured callback or null
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super QuicClientConfig> doOnConnect() {
		return doOnConnect;
	}

	/**
	 * Return the configured callback or null
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super QuicConnection> doOnConnected() {
		return doOnConnected;
	}

	/**
	 * Return the configured callback or null
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super QuicConnection> doOnDisconnected() {
		return doOnDisconnected;
	}

	/**
	 * Return the remote configured {@link SocketAddress}
	 *
	 * @return the remote configured {@link SocketAddress}
	 */
	public final Supplier<? extends SocketAddress> remoteAddress() {
		return remoteAddress;
	}

	@Override
	protected ConnectionObserver defaultConnectionObserver() {
		if (channelGroup() == null && doOnConnected() == null && doOnDisconnected() == null) {
			return super.defaultConnectionObserver();
		}
		return super.defaultConnectionObserver()
		            .then(new QuicClientDoOn(channelGroup(), doOnConnected(), doOnDisconnected()));
	}

	@Override
	protected LoggingHandler defaultLoggingHandler() {
		return LOGGING_HANDLER;
	}

	@Override
	protected ChannelMetricsRecorder defaultMetricsRecorder() {
		// TODO where we want metrics on QUIC channel or on QUIC stream
		return MicrometerQuicClientMetricsRecorder.INSTANCE;
	}

	@Override
	protected ChannelInitializer<Channel> parentChannelInitializer() {
		return new ParentChannelInitializer(this);
	}

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(QuicClient.class);

	static final class MicrometerQuicClientMetricsRecorder extends MicrometerChannelMetricsRecorder {

		static final MicrometerQuicClientMetricsRecorder INSTANCE = new MicrometerQuicClientMetricsRecorder();

		MicrometerQuicClientMetricsRecorder() {
			super(QUIC_CLIENT_PREFIX, "quic");
		}
	}

	static final class ParentChannelInitializer extends ChannelInitializer<Channel> {

		final long                           ackDelayExponent;
		final boolean                        activeMigration;
		final QuicCongestionControlAlgorithm congestionControlAlgorithm;
		final boolean                        grease;
		final boolean                        hystart;
		final Duration                       idleTimeout;
		final QuicInitialSettingsSpec        initialSettings;
		final int                            localConnectionIdLength;
		final ChannelHandler                 loggingHandler;
		final Duration                       maxAckDelay;
		final long                           maxRecvUdpPayloadSize;
		final long                           maxSendUdpPayloadSize;
		final int                            recvQueueLen;
		final int                            sendQueueLen;
		final QuicSslContext                 sslContext;

		ParentChannelInitializer(QuicClientConfig config) {
			this.ackDelayExponent = config.ackDelayExponent;
			this.activeMigration = config.activeMigration;
			this.congestionControlAlgorithm = config.congestionControlAlgorithm;
			this.grease = config.grease;
			this.hystart = config.hystart;
			this.idleTimeout = config.idleTimeout;
			this.initialSettings = config.initialSettings;
			this.localConnectionIdLength = config.localConnectionIdLength;
			this.loggingHandler = config.loggingHandler();
			this.maxAckDelay = config.maxAckDelay;
			this.maxRecvUdpPayloadSize = config.maxRecvUdpPayloadSize;
			this.maxSendUdpPayloadSize = config.maxSendUdpPayloadSize;
			this.recvQueueLen = config.recvQueueLen;
			this.sendQueueLen = config.sendQueueLen;
			this.sslContext = config.sslContext;
		}

		@Override
		protected void initChannel(Channel channel) {
			QuicClientCodecBuilder quicClientCodecBuilder = new QuicClientCodecBuilder();
			quicClientCodecBuilder.ackDelayExponent(ackDelayExponent)
					.activeMigration(activeMigration)
					.congestionControlAlgorithm(congestionControlAlgorithm)
					.grease(grease)
					.hystart(hystart)
					.initialMaxData(initialSettings.maxData)
					.initialMaxStreamDataBidirectionalLocal(initialSettings.maxStreamDataBidirectionalLocal)
					.initialMaxStreamDataBidirectionalRemote(initialSettings.maxStreamDataBidirectionalRemote)
					.initialMaxStreamDataUnidirectional(initialSettings.maxStreamDataUnidirectional)
					.initialMaxStreamsBidirectional(initialSettings.maxStreamsBidirectional)
					.initialMaxStreamsUnidirectional(initialSettings.maxStreamsUnidirectional)
					.localConnectionIdLength(localConnectionIdLength)
					.maxAckDelay(maxAckDelay.toMillis(), TimeUnit.MILLISECONDS)
					.maxRecvUdpPayloadSize(maxRecvUdpPayloadSize)
					.maxSendUdpPayloadSize(maxSendUdpPayloadSize)
					.sslContext(sslContext);

			if (recvQueueLen > 0 && sendQueueLen > 0) {
				quicClientCodecBuilder.datagram(recvQueueLen, sendQueueLen);
			}

			if (idleTimeout != null) {
				quicClientCodecBuilder.maxIdleTimeout(idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
			}

			if (loggingHandler != null) {
				channel.pipeline().addLast(loggingHandler);
			}
			channel.pipeline().addLast(quicClientCodecBuilder.build());
		}
	}

	static final class QuicClientDoOn implements ConnectionObserver {

		final ChannelGroup                     channelGroup;
		final Consumer<? super QuicConnection> doOnConnected;
		final Consumer<? super QuicConnection> doOnDisconnected;

		QuicClientDoOn(
				@Nullable ChannelGroup channelGroup,
				@Nullable Consumer<? super QuicConnection> doOnConnected,
				@Nullable Consumer<? super QuicConnection> doOnDisconnected) {
			this.channelGroup = channelGroup;
			this.doOnConnected = doOnConnected;
			this.doOnDisconnected = doOnDisconnected;
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (channelGroup != null && newState == State.CONNECTED) {
				channelGroup.add(connection.channel());
				return;
			}
			if (doOnConnected != null && newState == State.CONFIGURED) {
				doOnConnected.accept((QuicConnection) connection);
				return;
			}
			if (doOnDisconnected != null) {
				if (newState == State.DISCONNECTING) {
					connection.onDispose(() -> doOnDisconnected.accept((QuicConnection) connection));
				}
				else if (newState == State.RELEASED) {
					doOnDisconnected.accept((QuicConnection) connection);
				}
			}
		}
	}
}
