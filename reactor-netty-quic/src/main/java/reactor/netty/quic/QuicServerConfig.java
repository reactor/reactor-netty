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
import io.netty.incubator.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.incubator.codec.quic.QuicConnectionIdGenerator;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicTokenHandler;
import io.netty.util.AttributeKey;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static reactor.netty.ReactorNetty.format;

/**
 * Encapsulate all necessary configuration for QUIC server transport. The public API is read-only.
 *
 * @author Violeta Georgieva
 */
public final class QuicServerConfig extends QuicTransportConfig<QuicServerConfig> {

	/**
	 * Name prefix that will be used for the QUIC server's metrics
	 * registered in Micrometer's global registry
	 */
	public static final String QUIC_SERVER_PREFIX = "reactor.netty.quic.server";

	static final QuicConnectionIdGenerator DEFAULT_CONNECTION_ID_ADDRESS_GENERATOR =
			QuicConnectionIdGenerator.randomGenerator();

	QuicConnectionIdGenerator        connectionIdAddressGenerator;
	Consumer<? super QuicConnection> doOnConnection;
	QuicTokenHandler                 tokenHandler;

	QuicServerConfig(
			Map<ChannelOption<?>, ?> options,
			Map<ChannelOption<?>, ?> streamOptions,
			Supplier<? extends SocketAddress> bindAddress) {
		super(options, streamOptions, bindAddress);
		this.connectionIdAddressGenerator = DEFAULT_CONNECTION_ID_ADDRESS_GENERATOR;
	}

	QuicServerConfig(QuicServerConfig parent) {
		super(parent);
		this.connectionIdAddressGenerator = parent.connectionIdAddressGenerator;
		this.doOnConnection = parent.doOnConnection;
		this.tokenHandler = parent.tokenHandler;
	}

	/**
	 * Return the configured {@link QuicConnectionIdGenerator} or the default.
	 *
	 * @return the configured {@link QuicConnectionIdGenerator} or the default
	 */
	public QuicConnectionIdGenerator connectionIdAddressGenerator() {
		return connectionIdAddressGenerator;
	}

	/**
	 * Return the configured callback or null
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public Consumer<? super QuicConnection> doOnConnection() {
		return doOnConnection;
	}

	/**
	 * Return the configured {@link QuicTokenHandler} or null.
	 *
	 * @return the configured {@link QuicTokenHandler} or null
	 */
	@Nullable
	public QuicTokenHandler tokenHandler() {
		return tokenHandler;
	}

	@Override
	protected ConnectionObserver defaultConnectionObserver() {
		if (channelGroup() == null && doOnConnection() == null) {
			return super.defaultConnectionObserver();
		}
		return super.defaultConnectionObserver()
				.then(new QuicServerDoOnConnection(channelGroup(), doOnConnection()));
	}

	@Override
	protected LoggingHandler defaultLoggingHandler() {
		return LOGGING_HANDLER;
	}

	@Override
	protected ChannelMetricsRecorder defaultMetricsRecorder() {
		// TODO where we want metrics on QUIC channel or on QUIC stream
		return MicrometerQuicServerMetricsRecorder.INSTANCE;
	}

	@Override
	protected ChannelInitializer<Channel> parentChannelInitializer() {
		return new ParentChannelInitializer(this);
	}

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(QuicServer.class);

	static final class MicrometerQuicServerMetricsRecorder extends MicrometerChannelMetricsRecorder {

		static final MicrometerQuicServerMetricsRecorder INSTANCE = new MicrometerQuicServerMetricsRecorder();

		MicrometerQuicServerMetricsRecorder() {
			super(QUIC_SERVER_PREFIX, "quic");
		}
	}

	static final class ParentChannelInitializer extends ChannelInitializer<Channel> {

		final long                           ackDelayExponent;
		final boolean                        activeMigration;
		final Map<AttributeKey<?>, ?>        attributes;
		final QuicCongestionControlAlgorithm congestionControlAlgorithm;
		final QuicConnectionIdGenerator      connectionIdAddressGenerator;
		final boolean                        grease;
		final boolean                        hystart;
		final Duration                       idleTimeout;
		final QuicInitialSettingsSpec        initialSettings;
		final int                            localConnectionIdLength;
		final ChannelHandler                 loggingHandler;
		final Duration                       maxAckDelay;
		final long                           maxRecvUdpPayloadSize;
		final long                           maxSendUdpPayloadSize;
		final Map<ChannelOption<?>, ?>       options;
		final ChannelInitializer<Channel>    quicChannelInitializer;
		final int                            recvQueueLen;
		final int                            sendQueueLen;
		final Map<AttributeKey<?>, ?>        streamAttrs;
		final ConnectionObserver             streamObserver;
		final Map<ChannelOption<?>, ?>       streamOptions;
		final QuicSslContext                 sslContext;
		final QuicTokenHandler               tokenHandler;

		ParentChannelInitializer(QuicServerConfig config) {
			this.ackDelayExponent = config.ackDelayExponent;
			this.activeMigration = config.activeMigration;
			this.attributes = config.attributes();
			this.congestionControlAlgorithm = config.congestionControlAlgorithm;
			this.connectionIdAddressGenerator = config.connectionIdAddressGenerator;
			this.grease = config.grease;
			this.hystart = config.hystart;
			this.idleTimeout = config.idleTimeout;
			this.initialSettings = config.initialSettings;
			this.localConnectionIdLength = config.localConnectionIdLength;
			this.loggingHandler = config.loggingHandler();
			this.maxAckDelay = config.maxAckDelay;
			this.maxRecvUdpPayloadSize = config.maxRecvUdpPayloadSize;
			this.maxSendUdpPayloadSize = config.maxSendUdpPayloadSize;
			this.options = config.options();
			ConnectionObserver observer = config.defaultConnectionObserver()
					.then(config.connectionObserver());
			this.quicChannelInitializer = config.channelInitializer(observer, null, true);
			this.recvQueueLen = config.recvQueueLen;
			this.sendQueueLen = config.sendQueueLen;
			this.streamAttrs = config.streamAttrs;
			this.streamObserver = config.streamObserver.then(new QuicStreamChannelObserver(config.streamHandler));
			this.streamOptions = config.streamOptions;
			this.sslContext = config.sslContext;
			this.tokenHandler = config.tokenHandler;
		}

		@Override
		protected void initChannel(Channel channel) {
			QuicServerCodecBuilder quicServerCodecBuilder = new QuicServerCodecBuilder();
			quicServerCodecBuilder.ackDelayExponent(ackDelayExponent)
					.activeMigration(activeMigration)
					.congestionControlAlgorithm(congestionControlAlgorithm)
					.connectionIdAddressGenerator(connectionIdAddressGenerator)
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
				quicServerCodecBuilder.datagram(recvQueueLen, sendQueueLen);
			}

			if (idleTimeout != null) {
				quicServerCodecBuilder.maxIdleTimeout(idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
			}

			if (tokenHandler != null) {
				quicServerCodecBuilder.tokenHandler(tokenHandler);
			}

			attributes(quicServerCodecBuilder, attributes);
			channelOptions(quicServerCodecBuilder, options);
			streamAttributes(quicServerCodecBuilder, streamAttrs);
			streamChannelOptions(quicServerCodecBuilder, streamOptions);

			quicServerCodecBuilder
					.handler(quicChannelInitializer)
					.streamHandler(QuicTransportConfig.streamChannelInitializer(loggingHandler, streamObserver, true));

			if (loggingHandler != null) {
				channel.pipeline().addLast(loggingHandler);
			}
			channel.pipeline().addLast(quicServerCodecBuilder.build());
		}

		@SuppressWarnings("unchecked")
		static void attributes(QuicServerCodecBuilder quicServerCodecBuilder, Map<AttributeKey<?>, ?> attrs) {
			for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
				quicServerCodecBuilder.attr((AttributeKey<Object>) e.getKey(), e.getValue());
			}
		}

		@SuppressWarnings("unchecked")
		static void channelOptions(QuicServerCodecBuilder quicServerCodecBuilder, Map<ChannelOption<?>, ?> options) {
			for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
				quicServerCodecBuilder.option((ChannelOption<Object>) e.getKey(), e.getValue());
			}
		}

		@SuppressWarnings("unchecked")
		static void streamAttributes(QuicServerCodecBuilder quicServerCodecBuilder, Map<AttributeKey<?>, ?> attrs) {
			for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
				quicServerCodecBuilder.streamAttr((AttributeKey<Object>) e.getKey(), e.getValue());
			}
		}

		@SuppressWarnings("unchecked")
		static void streamChannelOptions(QuicServerCodecBuilder quicServerCodecBuilder, Map<ChannelOption<?>, ?> options) {
			for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
				quicServerCodecBuilder.streamOption((ChannelOption<Object>) e.getKey(), e.getValue());
			}
		}
	}

	static final class QuicServerDoOnConnection implements ConnectionObserver {

		final ChannelGroup                     channelGroup;
		final Consumer<? super QuicConnection> doOnConnection;

		QuicServerDoOnConnection(
				@Nullable ChannelGroup channelGroup,
				@Nullable Consumer<? super QuicConnection> doOnConnection) {
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
					doOnConnection.accept((QuicConnection) connection);
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
