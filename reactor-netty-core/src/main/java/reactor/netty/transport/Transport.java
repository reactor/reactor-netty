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

import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableChannel;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.internal.util.Metrics;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.netty.resources.LoopResources;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

/**
 * An immutable transport builder for clients and servers.
 *
 * @param <T> Transport implementation
 * @param <C> Transport Config implementation
 * @author Stephane Maldini
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public abstract class Transport<T extends Transport<T, C>, C extends TransportConfig> {

	/**
	 * Update the given attribute key or remove it if the value is {@literal null}.
	 *
	 * @param key the {@link AttributeKey} key
	 * @param value the {@link AttributeKey} value
	 * @param <A> the attribute type
	 * @return a new {@link Transport} reference
	 */
	public <A> T attr(AttributeKey<A> key, @Nullable A value) {
		Objects.requireNonNull(key, "key");
		T dup = duplicate();
		dup.configuration().attrs = TransportConfig.updateMap(configuration().attrs, key, value);
		return dup;
	}

	/**
	 * Set a new local address to which this transport should bind on subscribe.
	 *
	 * @param bindAddressSupplier A supplier of the address to bind to.
	 * @return a new {@link Transport}
	 */
	public T bindAddress(Supplier<? extends SocketAddress> bindAddressSupplier) {
		Objects.requireNonNull(bindAddressSupplier, "bindAddressSupplier");
		T dup = duplicate();
		dup.configuration().bindAddress = bindAddressSupplier;
		return dup;
	}

	/**
	 * Provide a {@link ChannelGroup} to hold all active connected channels.
	 *
	 * <p><strong>Graceful Shutdown:</strong>
	 * <p>When a {@code ChannelGroup} is set, calls  to {@link DisposableChannel#disposeNow()}
	 * and {@link DisposableChannel#disposeNow(Duration)} not only stop accepting new requests
	 * but also additionally wait for all active requests, in the {@code ChannelGroup}, to
	 * complete, within the given timeout.
	 *
	 * @param channelGroup a {@link ChannelGroup}
	 * @return a new {@link Transport} reference
	 */
	public T channelGroup(ChannelGroup channelGroup) {
		Objects.requireNonNull(channelGroup, "channelGroup");
		T dup = duplicate();
		dup.configuration().channelGroup = channelGroup;
		return dup;
	}

	/**
	 * Return a {@link TransportConfig}.
	 *
	 * @return a {@link TransportConfig}
	 */
	public abstract C configuration();

	/**
	 * Configure the channel pipeline while initializing the channel.
	 *
	 * @param doOnChannelInit configure the channel pipeline while initializing the channel
	 * @return a new {@link Transport} reference
	 */
	public T doOnChannelInit(ChannelPipelineConfigurer doOnChannelInit) {
		Objects.requireNonNull(doOnChannelInit, "doOnChannelInit");
		T dup = duplicate();
		ChannelPipelineConfigurer current = configuration().doOnChannelInit;
		dup.configuration().doOnChannelInit = current == null ? doOnChannelInit : current.then(doOnChannelInit);
		return dup;
	}

	/**
	 * Whether to enable metrics to be collected and registered in Micrometer's
	 * {@link io.micrometer.core.instrument.Metrics#globalRegistry globalRegistry}.
	 * Applications can separately register their own
	 * {@link io.micrometer.core.instrument.config.MeterFilter filters}.
	 * For example, to put an upper bound on the number of tags produced:
	 * <pre class="code">
	 * MeterFilter filter = ... ;
	 * Metrics.globalRegistry.config().meterFilter(MeterFilter.maximumAllowableTags(prefix, 100, filter));
	 * </pre>
	 * <p>By default this is not enabled.
	 *
	 * @param enable true enables metrics collection; false disables it
	 * @return a new {@link Transport} reference
	 */
	protected T metrics(boolean enable) {
		if (enable) {
			if (!Metrics.isMicrometerAvailable()) {
				throw new UnsupportedOperationException(
					"To enable metrics, you must add the dependency `io.micrometer:micrometer-core`" +
						" to the class path first");
			}
			T dup = duplicate();
			dup.configuration().metricsRecorder(() -> configuration().defaultMetricsRecorder());
			return dup;
		}
		else if (configuration().metricsRecorder != null) {
			T dup = duplicate();
			dup.configuration().metricsRecorder(null);
			return dup;
		}
		else {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
	}

	/**
	 * Specifies whether the metrics are enabled on the {@link Transport}.
	 * All generated metrics are provided to the specified recorder
	 * which is only instantiated if metrics are being enabled (the instantiation is not lazy,
	 * but happens immediately, while configuring the {@link Transport}).
	 *
	 * @param enable if true enables the metrics on the {@link Transport}.
	 * @param recorder a supplier for the {@link ChannelMetricsRecorder}
	 * @return a new {@link Transport} reference
	 */
	public T metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder) {
		if (enable) {
			T dup = duplicate();
			dup.configuration().metricsRecorder(recorder);
			return dup;
		}
		else if (configuration().metricsRecorder != null) {
			T dup = duplicate();
			dup.configuration().metricsRecorder(null);
			return dup;
		}
		else {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
	}

	/**
	 * Set or add the given {@link ConnectionObserver} to observe the connection state changes.
	 *
	 * @param observer the {@link ConnectionObserver} to be set or add
	 * @return a new {@link Transport} reference
	 */
	public T observe(ConnectionObserver observer) {
		Objects.requireNonNull(observer, "observer");
		T dup = duplicate();
		ConnectionObserver current = configuration().observer;
		dup.configuration().observer = current == null ? observer : current.then(observer);
		return dup;
	}

	/**
	 * Update the given option key or remove it if the value is {@literal null}.
	 * Note: Setting {@link ChannelOption#AUTO_READ} option will be ignored. It is configured to be {@code false}.
	 *
	 * @param key the {@link ChannelOption} key
	 * @param value the {@link ChannelOption} value or null
	 * @param <O> the option type
	 * @return a new {@link Transport} reference
	 */
	@SuppressWarnings("ReferenceEquality")
	public <O> T option(ChannelOption<O> key, @Nullable O value) {
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
		dup.configuration().options = TransportConfig.updateMap(configuration().options, key, value);
		return dup;
	}

	/**
	 * Run IO loops on the given {@link EventLoopGroup}.
	 *
	 * @param eventLoopGroup an eventLoopGroup to share
	 * @return a new {@link Transport} reference
	 */
	public T runOn(EventLoopGroup eventLoopGroup) {
		return runOn(new EventLoopGroupLoopResources(eventLoopGroup));
	}

	/**
	 * Run IO loops on a supplied {@link EventLoopGroup} from the {@link LoopResources} container.
	 * Will prefer native (epoll/kqueue) implementation if available
	 * unless the environment property {@code reactor.netty.native} is set to {@code false}.
	 *
	 * @param channelResources a {@link LoopResources} accepting native runtime expectation and
	 * returning an eventLoopGroup
	 * @return a new {@link Transport} reference
	 */
	public T runOn(LoopResources channelResources) {
		Objects.requireNonNull(channelResources, "channelResources");
		return runOn(channelResources, LoopResources.DEFAULT_NATIVE);
	}

	/**
	 * Run IO loops on a supplied {@link EventLoopGroup} from the {@link LoopResources} container.
	 *
	 * @param loopResources a new loop resources
	 * @param preferNative should prefer running on epoll, kqueue or similar instead of java NIO
	 * @return a new {@link Transport} reference
	 */
	public T runOn(LoopResources loopResources, boolean preferNative) {
		Objects.requireNonNull(loopResources, "loopResources");
		T dup = duplicate();
		TransportConfig c = dup.configuration();
		c.loopResources = loopResources;
		c.preferNative = preferNative;
		return dup;
	}

	/**
	 * Apply or remove a wire logger configuration using {@link Transport} category (logger),
	 * {@code DEBUG} logger level and {@link AdvancedByteBufFormat#HEX_DUMP} for {@link ByteBuf} format,
	 * which means both events and content will be logged and the content will be in hex format.
	 *
	 * @param enable specifies whether the wire logger configuration will be added to the pipeline
	 * @return a new {@link Transport} reference
	 */
	public T wiretap(boolean enable) {
		if (enable) {
			T dup = duplicate();
			dup.configuration().loggingHandler = configuration().defaultLoggingHandler();
			return dup;
		}
		else if (configuration().loggingHandler != null) {
			T dup = duplicate();
			dup.configuration().loggingHandler = null;
			return dup;
		}
		else {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
	}

	/**
	 * Apply a wire logger configuration using the specified category (logger),
	 * {@code DEBUG} logger level and {@link AdvancedByteBufFormat#HEX_DUMP} for {@link ByteBuf} format,
	 * which means both events and content will be logged and the content will be in hex format.
	 *
	 * @param category the logger category
	 * @return a new {@link Transport} reference
	 */
	public T wiretap(String category) {
		Objects.requireNonNull(category, "category");
		return wiretap(category, LogLevel.DEBUG);
	}

	/**
	 * Apply a wire logger configuration using the specified category (logger),
	 * logger level and {@link AdvancedByteBufFormat#HEX_DUMP} for {@link ByteBuf} format,
	 * which means both events and content will be logged and the content will be in hex format.
	 *
	 * @param category the logger category
	 * @param level the logger level
	 * @return a new {@link Transport} reference
	 */
	public T wiretap(String category, LogLevel level) {
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(level, "level");
		return wiretap(category, level, AdvancedByteBufFormat.HEX_DUMP);
	}

	/**
	 * Apply a wire logger configuration using the specified category (logger),
	 * logger level and {@link ByteBuf} format.
	 * Depending on the format:
	 * <ul>
	 *     <li>{@link AdvancedByteBufFormat#SIMPLE} - only the events will be logged</li>
	 *     <li>{@link AdvancedByteBufFormat#HEX_DUMP} - both events and content will be logged,
	 *     with content in hex format</li>
	 *     <li>{@link AdvancedByteBufFormat#TEXTUAL} - both events and content will be logged,
	 *     with content in plain text format</li>
	 * </ul>
	 * When {@link AdvancedByteBufFormat#TEXTUAL} is specified, {@link Charset#defaultCharset()} will be used.
	 *
	 * @param category the logger category
	 * @param level the logger level
	 * @param format the {@link ByteBuf} format
	 * @return a new {@link Transport} reference
	 */
	public final T wiretap(String category, LogLevel level, AdvancedByteBufFormat format) {
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(format, "format");
		return wiretap(category, level, format, Charset.defaultCharset());
	}

	/**
	 * Apply a wire logger configuration using the specific category (logger),
	 * logger level, {@link ByteBuf} format and charset.
	 * The charset is relevant in case of {@link AdvancedByteBufFormat#TEXTUAL}
	 * and a different charset than {@link Charset#defaultCharset()} is required.
	 * Depending on the format:
	 * <ul>
	 *     <li>{@link AdvancedByteBufFormat#SIMPLE} - only the events will be logged</li>
	 *     <li>{@link AdvancedByteBufFormat#HEX_DUMP} - both events and content will be logged,
	 *     with content in hex format</li>
	 *     <li>{@link AdvancedByteBufFormat#TEXTUAL} - both events and content will be logged,
	 *     with content in plain text format</li>
	 * </ul>
	 *
	 * @param category the logger category
	 * @param level    the logger level
	 * @param format   the {@link ByteBuf} format
	 * @param charset  the charset
	 * @return a new {@link Transport} reference
	 */
	public final T wiretap(String category, LogLevel level, AdvancedByteBufFormat format, Charset charset) {
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(format, "format");
		Objects.requireNonNull(charset, "charset");
		LoggingHandler loggingHandler = format.toLoggingHandler(category, level, charset);
		if (loggingHandler.equals(configuration().loggingHandler)) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().loggingHandler = loggingHandler;
		return dup;
	}

	/**
	 * Return a new {@link Transport} inheriting the current configuration.
	 * This is a shallow copy.
	 *
	 * @return a new {@link Transport} inheriting the current configuration
	 */
	protected abstract T duplicate();

	static final Logger log = Loggers.getLogger(Transport.class);

	static final class EventLoopGroupLoopResources implements LoopResources {

		final EventLoopGroup eventLoopGroup;

		EventLoopGroupLoopResources(EventLoopGroup eventLoopGroup) {
			Objects.requireNonNull(eventLoopGroup, "eventLoopGroup");
			this.eventLoopGroup = eventLoopGroup;
		}

		@Override
		public EventLoopGroup onServer(boolean useNative) {
			return eventLoopGroup;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			EventLoopGroupLoopResources that = (EventLoopGroupLoopResources) o;
			return Objects.equals(eventLoopGroup, that.eventLoopGroup);
		}

		@Override
		public int hashCode() {
			int result = 1;
			result = 31 * result + Objects.hashCode(eventLoopGroup);
			return result;
		}
	}
}