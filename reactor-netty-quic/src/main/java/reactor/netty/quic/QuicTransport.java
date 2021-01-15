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

import io.netty.channel.ChannelOption;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicChannelBootstrap;
import io.netty.incubator.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslEngine;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamChannelBootstrap;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.transport.Transport;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * A generic QUIC {@link Transport}
 *
 * @param <T> {@link QuicTransport} implementation
 * @param <CONF> {@link QuicTransportConfig} implementation
 * @author Violeta Georgieva
 */
abstract class QuicTransport<T extends Transport<T, CONF>, CONF extends QuicTransportConfig<CONF>>
		extends Transport<T, CONF> {

	/**
	 * Set the delay exponent used for ACKs.
	 * See
	 * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_ack_delay_exponent">
	 *     set_ack_delay_exponent</a>.
	 * Default to 3.
	 *
	 * @param ackDelayExponent the delay exponent used for ACKs.
	 * @return a {@link QuicTransport} reference
	 */
	public final T ackDelayExponent(long ackDelayExponent) {
		if (ackDelayExponent < 0) {
			throw new IllegalArgumentException("ackDelayExponent must be positive or zero");
		}
		if (ackDelayExponent == configuration().ackDelayExponent) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().ackDelayExponent = ackDelayExponent;
		return dup;
	}

	/**
	 * Enable/disable active migration.
	 * See
	 * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_disable_active_migration">
	 *     set_disable_active_migration</a>.
	 * Default to {@code true}.
	 *
	 * @param enable {@code true} if migration should be enabled, {@code false} otherwise
	 * @return a {@link QuicTransport} reference
	 */
	public final T activeMigration(boolean enable) {
		if (enable == configuration().activeMigration) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().activeMigration = enable;
		return dup;
	}

	/**
	 * Set the congestion control algorithm to use.
	 * Default to {@link QuicCongestionControlAlgorithm#CUBIC}.
	 *
	 * @param congestionControlAlgorithm the {@link QuicCongestionControlAlgorithm} to use.
	 * @return a {@link QuicTransport} reference
	 */
	public final T congestionControlAlgorithm(QuicCongestionControlAlgorithm congestionControlAlgorithm) {
		Objects.requireNonNull(congestionControlAlgorithm, "congestionControlAlgorithm");
		if (congestionControlAlgorithm == configuration().congestionControlAlgorithm) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().congestionControlAlgorithm = congestionControlAlgorithm;
		return dup;
	}

	/**
	 * If configured this will enable <a href="https://tools.ietf.org/html/draft-ietf-quic-datagram-01">
	 *     Datagram support.</a>
	 *
	 * @param recvQueueLen the RECV queue length.
	 * @param sendQueueLen the SEND queue length.
	 * @return a {@link QuicTransport} reference
	 */
	public final T datagram(int recvQueueLen, int sendQueueLen) {
		if (recvQueueLen < 1) {
			throw new IllegalArgumentException("recvQueueLen must be positive");
		}
		if (sendQueueLen < 1) {
			throw new IllegalArgumentException("sendQueueLen must be positive");
		}
		if (recvQueueLen == configuration().recvQueueLen && sendQueueLen == configuration().sendQueueLen) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().recvQueueLen = recvQueueLen;
		dup.configuration().sendQueueLen = sendQueueLen;
		return dup;
	}

	/**
	 * Set or add a callback called when {@link QuicTransport} is about to start listening for incoming traffic.
	 *
	 * @param doOnBind a consumer observing connected events
	 * @return a {@link QuicTransport} reference
	 */
	public final T doOnBind(Consumer<? super CONF> doOnBind) {
		Objects.requireNonNull(doOnBind, "doOnBind");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<CONF> current = (Consumer<CONF>) dup.configuration().doOnBind;
		dup.configuration().doOnBind = current == null ? doOnBind : current.andThen(doOnBind);
		return dup;
	}

	/**
	 * Set or add a callback called after {@link QuicTransport} has been started.
	 *
	 * @param doOnBound a consumer observing connected events
	 * @return a {@link QuicTransport} reference
	 */
	public final T doOnBound(Consumer<? super Connection> doOnBound) {
		Objects.requireNonNull(doOnBound, "doOnBound");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<Connection> current = (Consumer<Connection>) dup.configuration().doOnBound;
		dup.configuration().doOnBound = current == null ? doOnBound : current.andThen(doOnBound);
		return dup;
	}

	/**
	 * Set or add a callback called after {@link QuicTransport} has been shutdown.
	 *
	 * @param doOnUnbound a consumer observing unbound events
	 * @return a {@link QuicTransport} reference
	 */
	public final T doOnUnbound(Consumer<? super Connection> doOnUnbound) {
		Objects.requireNonNull(doOnUnbound, "doOnUnbound");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<Connection> current = (Consumer<Connection>) dup.configuration().doOnUnbound;
		dup.configuration().doOnUnbound = current == null ? doOnUnbound : current.andThen(doOnUnbound);
		return dup;
	}

	/**
	 * Set if <a href="https://tools.ietf.org/html/draft-thomson-quic-bit-grease-00">greasing</a> should be enabled
	 * or not.
	 * Default to {@code true}.
	 *
	 * @param enable {@code true} if enabled, {@code false} otherwise.
	 * @return a {@link QuicTransport} reference
	 */
	public final T grease(boolean enable) {
		if (enable == configuration().grease) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().grease = enable;
		return dup;
	}

	/**
	 * Attach an IO handler to react on incoming stream.
	 * <p>Note: If an IO handler is not specified the incoming streams will be closed automatically.
	 *
	 * @param streamHandler an IO handler that can dispose underlying connection when {@link Publisher} terminates.
	 * @return a {@link QuicTransport} reference
	 */
	public final T handleStream(
			BiFunction<? super QuicInbound, ? super QuicOutbound, ? extends Publisher<Void>> streamHandler) {
		Objects.requireNonNull(streamHandler, "streamHandler");
		T dup = duplicate();
		dup.configuration().streamHandler = streamHandler;
		return dup;
	}

	/**
	 * Enable/disable Hystart.
	 * See
	 * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.enable_hystart">
	 *     enable_hystart</a>.
	 * Default to {@code true}.
	 *
	 * @param enable {@code true} if Hystart should be enabled
	 * @return a {@link QuicTransport} reference
	 */
	public final T hystart(boolean enable) {
		if (enable == configuration().hystart) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().hystart = enable;
		return dup;
	}

	/**
	 * Set the maximum idle timeout (resolution: ms)
	 * See <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_max_idle_timeout">
	 *     set_max_idle_timeout</a>.
	 * <p>By default {@code idleTimeout} is not specified.
	 *
	 * @param idleTimeout the maximum idle timeout (resolution: ms)
	 * @return a {@link QuicTransport} reference
	 */
	public final T idleTimeout(Duration idleTimeout) {
		Objects.requireNonNull(idleTimeout, "idleTimeout");
		if (idleTimeout.equals(configuration().idleTimeout)) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().idleTimeout = idleTimeout;
		return dup;
	}

	/**
	 * Configure QUIC initial settings
	 *
	 * @param initialSettings configures QUIC initial settings
	 * @return a {@link QuicTransport} reference
	 */
	public final T initialSettings(Consumer<QuicInitialSettingsSpec.Builder> initialSettings) {
		Objects.requireNonNull(initialSettings, "initialSettings");
		QuicInitialSettingsSpec.Build builder = new QuicInitialSettingsSpec.Build();
		initialSettings.accept(builder);
		QuicInitialSettingsSpec settings = builder.build();
		if (settings.equals(configuration().initialSettings)) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().initialSettings = settings;
		return dup;
	}

	/**
	 * Sets the local connection id length that is used.
	 * Default 20, which is also the maximum that is supported.
	 *
	 * @param localConnectionIdLength the length of local generated connections ids
	 * @return a {@link QuicTransport} reference
	 */
	public final T localConnectionIdLength(int localConnectionIdLength) {
		if (localConnectionIdLength < 0 || localConnectionIdLength > 20) {
			throw new IllegalArgumentException("localConnectionIdLength must be between zero and 20");
		}
		if (localConnectionIdLength == configuration().localConnectionIdLength) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().localConnectionIdLength = localConnectionIdLength;
		return dup;
	}

	/**
	 * Set max ACK delay (resolution: ms).
	 * See
	 * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_max_ack_delay">
	 *     set_max_ack_delay</a>.
	 * Default to 25 ms.
	 *
	 * @param maxAckDelay the max ACK delay (resolution: ms)
	 * @return a {@link QuicTransport} reference
	 */
	public final T maxAckDelay(Duration maxAckDelay) {
		Objects.requireNonNull(maxAckDelay, "maxAckDelay");
		if (maxAckDelay.equals(configuration().maxAckDelay)) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().maxAckDelay = maxAckDelay;
		return dup;
	}

	/**
	 * See <a href="https://github.com/cloudflare/quiche/blob/35e38d987c1e53ef2bd5f23b754c50162b5adac8/src/lib.rs#L662">
	 *     set_max_recv_udp_payload_size</a>.
	 *
	 * The default value is 65527.
	 *
	 * @param maxRecvUdpPayloadSize the maximum payload size that is advertised to the remote peer.
	 * @return a {@link QuicTransport} reference
	 */
	public final T maxRecvUdpPayloadSize(long maxRecvUdpPayloadSize) {
		if (maxRecvUdpPayloadSize < 0) {
			throw new IllegalArgumentException("maxUdpPayloadSize must be positive or zero");
		}
		if (maxRecvUdpPayloadSize == configuration().maxRecvUdpPayloadSize) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().maxRecvUdpPayloadSize = maxRecvUdpPayloadSize;
		return dup;
	}

	/**
	 * See <a href="https://github.com/cloudflare/quiche/blob/35e38d987c1e53ef2bd5f23b754c50162b5adac8/src/lib.rs#L669">
	 *     set_max_send_udp_payload_size</a>.
	 *
	 * The default and minimum value is 1200.
	 *
	 * @param maxSendUdpPayloadSize the maximum payload size that is advertised to the remote peer.
	 * @return a {@link QuicTransport} reference
	 */
	public final T maxSendUdpPayloadSize(long maxSendUdpPayloadSize) {
		if (maxSendUdpPayloadSize < 0) {
			throw new IllegalArgumentException("maxUdpPayloadSize must be positive or zero");
		}
		if (maxSendUdpPayloadSize == configuration().maxSendUdpPayloadSize) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().maxSendUdpPayloadSize = maxSendUdpPayloadSize;
		return dup;
	}

	/**
	 * The {@link QuicSslContext} that will be used to create {@link QuicSslEngine}s for {@link QuicChannel}s.
	 *
	 * @param sslContext the {@link QuicSslContext}
	 * @return a {@link QuicTransport} reference
	 */
	public final T secure(QuicSslContext sslContext) {
		Objects.requireNonNull(sslContext, "sslContext");
		T dup = duplicate();
		dup.configuration().sslContext = sslContext;
		return dup;
	}

	/**
	 * Injects default attribute to the future {@link QuicStreamChannel}. It
	 * will be available via {@link QuicStreamChannel#attr(AttributeKey)}.
	 * If the {@code value} is {@code null}, the attribute of the specified {@code key}
	 * is removed.
	 *
	 * @param key the attribute key
	 * @param value the attribute value - null to remove a key
	 * @param <A> the attribute type
	 * @return a {@link QuicTransport} reference
	 * @see QuicStreamChannelBootstrap#attr(AttributeKey, Object)
	 * @see QuicChannelBootstrap#streamAttr(AttributeKey, Object)
	 */
	public final <A> T streamAttr(AttributeKey<A> key, @Nullable A value) {
		Objects.requireNonNull(key, "key");
		T dup = duplicate();
		dup.configuration().streamAttrs = QuicTransportConfig.updateMap(configuration().streamAttrs, key, value);
		return dup;
	}

	/**
	 * Set or add the given {@link ConnectionObserver} for each stream
	 *
	 * @param observer the {@link ConnectionObserver} addition
	 * @return a {@link QuicTransport} reference
	 */
	public T streamObserve(ConnectionObserver observer) {
		Objects.requireNonNull(observer, "observer");
		T dup = duplicate();
		ConnectionObserver current = configuration().streamObserver;
		dup.configuration().streamObserver = current == null ? observer : current.then(observer);
		return dup;
	}

	/**
	 * Injects default options to the future {@link QuicStreamChannel}. It
	 * will be available via {@link QuicStreamChannel#config()}.
	 * If the {@code value} is {@code null}, the attribute of the specified {@code key}
	 * is removed.
	 *
	 * @param key the option key
	 * @param value the option value - null to remove a key
	 * @param <A> the option type
	 * @return a {@link QuicTransport} reference
	 * @see QuicStreamChannelBootstrap#option(ChannelOption, Object)
	 * @see QuicChannelBootstrap#streamOption(ChannelOption, Object)
	 */
	@SuppressWarnings("ReferenceEquality")
	public final <A> T streamOption(ChannelOption<A> key, @Nullable A value) {
		Objects.requireNonNull(key, "key");
		// Reference comparison is deliberate
		if (ChannelOption.AUTO_READ == key) {
			if (value instanceof Boolean && Boolean.TRUE.equals(value)) {
				log.error("ChannelOption.AUTO_READ is configured to be [false], it cannot be set to [true]");
			}
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().streamOptions = QuicTransportConfig.updateMap(configuration().streamOptions, key, value);
		return dup;
	}

	/**
	 * Based on the actual configuration, returns a {@link Mono} that triggers:
	 * <ul>
	 *     <li>an initialization of the event loop group</li>
	 *     <li>loads the necessary native libraries for the transport</li>
	 * </ul>
	 * By default, when method is not used, the {@code bind operation} absorbs the extra time needed to load resources.
	 *
	 * @return a {@link Mono} representing the completion of the warmup
	 */
	public final Mono<Void> warmup() {
		return Mono.fromRunnable(() -> configuration().eventLoopGroup());
	}

	static final Logger log = Loggers.getLogger(QuicTransport.class);
}
