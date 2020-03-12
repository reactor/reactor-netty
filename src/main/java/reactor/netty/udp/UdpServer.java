/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.udp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.resources.LoopResources;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.Metrics;

import static reactor.netty.ReactorNetty.format;

/**
 * A UdpServer allows to build in a safe immutable way a UDP server that is materialized
 * and connecting when {@link #bind(Bootstrap)} is ultimately called.
 * <p>
 * <p> Internally, materialization happens in two phases, first {@link #configure()} is
 * called to retrieve a ready to use {@link Bootstrap} then {@link #bind(Bootstrap)}
 * is called.
 * <p>
 * <p> Example:
 * <pre>
 * {@code
 * UdpServer.create()
 *          .doOnBind(startMetrics)
 *          .doOnBound(startedMetrics)
 *          .doOnUnbind(stopMetrics)
 *          .host("127.0.0.1")
 *          .port(1234)
 *          .bind()
 *          .block()
 * }
 *
 * @author Stephane Maldini
 */
public abstract class UdpServer {

	/**
	 * Prepare a {@link UdpServer}
	 *
	 * @return a {@link UdpServer}
	 */
	public static UdpServer create() {
		return UdpServerBind.INSTANCE;
	}

	/**
	 * The address to which this server should bind on subscribe.
	 *
	 * @param bindingAddressSupplier A supplier of the address to bind to.
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer addressSupplier(Supplier<? extends SocketAddress> bindingAddressSupplier) {
		Objects.requireNonNull(bindingAddressSupplier, "bindingAddressSupplier");
		return bootstrap(b -> b.localAddress(bindingAddressSupplier.get()));
	}

	/**
	 * Inject default attribute to the future child {@link Channel} connections. They
	 * will be available via {@link Channel#attr(AttributeKey)}.
	 *
	 * @param key the attribute key
	 * @param value the attribute value
	 * @param <T> the attribute type
	 *
	 * @return a new {@link UdpServer}
	 *
	 * @see Bootstrap#attr(AttributeKey, Object)
	 */
	public final <T> UdpServer attr(AttributeKey<T> key, T value) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(value, "value");
		return bootstrap(b -> b.attr(key, value));
	}

	/**
	 * Apply {@link Bootstrap} configuration given mapper taking currently configured one
	 * and returning a new one to be ultimately used for socket binding. <p> Configuration
	 * will apply during {@link #configure()} phase.
	 *
	 * @param bootstrapMapper A bootstrap mapping function to update configuration and return an
	 * enriched bootstrap.
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer bootstrap(Function<? super Bootstrap, ? extends Bootstrap> bootstrapMapper) {
		return new UdpServerBootstrap(this, bootstrapMapper);
	}

	/**
	 * Bind the {@link UdpServer} and return a {@link Mono} of {@link Connection}. If
	 * {@link Mono} is cancelled, the underlying binding will be aborted. Once the {@link
	 * Connection} has been emitted and is not necessary anymore, disposing main server
	 * loop must be done by the user via {@link Connection#dispose()}.
	 *
	 * If update configuration phase fails, a {@link Mono#error(Throwable)} will be returned
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	public final Mono<? extends Connection> bind() {
		Bootstrap b;
		try{
			b = configure();
		}
		catch (Throwable t){
			Exceptions.throwIfJvmFatal(t);
			return Mono.error(t);
		}
		return bind(b);
	}

	/**
	 * Start a Server in a blocking fashion, and wait for it to finish initializing. The
	 * returned {@link Connection} offers simple server API, including to {@link
	 * Connection#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @param timeout max startup timeout
	 *
	 * @return a {@link Connection}
	 */
	public final Connection bindNow(Duration timeout) {
		try {
			return Objects.requireNonNull(bind().block(timeout), "aborted");
		}
		catch (IllegalStateException e) {
			if (e.getMessage().contains("blocking read")) {
				throw new IllegalStateException("UdpServer couldn't be started within "
						+ timeout.toMillis() + "ms");
			}
			throw e;
		}
	}

	/**
	 * Setup a callback called when {@link io.netty.channel.Channel} is about to
	 * bind.
	 *
	 * @param doOnBind a consumer observing server start event
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer doOnBind(Consumer<? super Bootstrap> doOnBind) {
		Objects.requireNonNull(doOnBind, "doOnBind");
		return new UdpServerDoOn(this, doOnBind, null, null);

	}

	/**
	 * Setup a callback called when {@link io.netty.channel.Channel} is
	 * bound.
	 *
	 * @param doOnBound a consumer observing server started event
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer doOnBound(Consumer<? super Connection> doOnBound) {
		Objects.requireNonNull(doOnBound, "doOnBound");
		return new UdpServerDoOn(this, null, doOnBound, null);
	}

	/**
	 * Setup a callback called when {@link io.netty.channel.Channel} is
	 * unbound.
	 *
	 * @param doOnUnbound a consumer observing server stop event
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer doOnUnbound(Consumer<? super Connection> doOnUnbound) {
		Objects.requireNonNull(doOnUnbound, "doOnUnbound");
		return new UdpServerDoOn(this, null, null, doOnUnbound);
	}

	/**
	 * Setup all lifecycle callbacks called on or after {@link io.netty.channel.Channel}
	 * has been bound and after it has been unbound.
	 *
	 * @param onBind a consumer observing server start event
	 * @param onBound a consumer observing server started event
	 * @param onUnbound a consumer observing server stop event
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer doOnLifecycle(Consumer<? super Bootstrap> onBind,
			Consumer<? super Connection> onBound,
			Consumer<? super Connection> onUnbound) {
		Objects.requireNonNull(onBind, "onBind");
		Objects.requireNonNull(onBound, "onBound");
		Objects.requireNonNull(onUnbound, "onUnbound");
		return new UdpServerDoOn(this, onBind, onBound, onUnbound);
	}

	/**
	 * Attach an IO handler to react on connected client
	 *
	 * @param handler an IO handler that can dispose underlying connection when {@link
	 * Publisher} terminates.
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer handle(BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return doOnBound(c -> {
			if (log.isDebugEnabled()) {
				log.debug(format(c.channel(), "Handler is being applied: {}"), handler);
			}

			Mono.fromDirect(handler.apply((UdpInbound) c, (UdpOutbound) c))
			    .subscribe(c.disposeSubscriber());
		});
	}

	/**
	 * The host to which this server should bind.
	 *
	 * @param host The host to bind to.
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer host(String host) {
		Objects.requireNonNull(host, "host");
		return bootstrap(b -> b.localAddress(host, getPort(b)));
	}

	/**
	 * Setup all lifecycle callbacks called on or after {@link io.netty.channel.Channel}
	 * has been connected and after it has been disconnected.
	 *
	 * @param observer a consumer observing state changes
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer observe(ConnectionObserver observer) {
		return new UdpServerObserve(this, observer);
	}

	/**
	 * Set a {@link ChannelOption} value for low level connection settings like {@code SO_TIMEOUT}
	 * or {@code SO_KEEPALIVE}. This will apply to each new channel from remote peer.
	 *
	 * @param key the option key
	 * @param value the option value
	 * @param <T> the option type
	 *
	 * @return new {@link UdpServer}
	 *
	 * @see Bootstrap#option(ChannelOption, Object)
	 */
	public final <T> UdpServer option(ChannelOption<T> key, T value) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(value, "value");
		return bootstrap(b -> b.option(key, value));
	}

	/**
	 * The port to which this server should bind.
	 *
	 * @param port The port to bind to.
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer port(int port) {
		return bootstrap(b -> b.localAddress(getHost(b), port));
	}

	/**
	 * Run IO loops on the given {@link EventLoopGroup}.
	 *
	 * @param eventLoopGroup an eventLoopGroup to share
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer runOn(EventLoopGroup eventLoopGroup) {
		Objects.requireNonNull(eventLoopGroup, "eventLoopGroup");
		return runOn(preferNative -> eventLoopGroup);
	}

	/**
	 * Run IO loops on a supplied {@link EventLoopGroup} from the {@link LoopResources}
	 * container. Will prefer native (epoll/kqueue) implementation if available unless the
	 * environment property {@code reactor.netty.native} is set to {@code false}.
	 *
	 * @param channelResources a {@link LoopResources} accepting native runtime
	 * expectation and returning an eventLoopGroup
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer runOn(LoopResources channelResources) {
		return runOn(channelResources, LoopResources.DEFAULT_NATIVE);
	}

	/**
	 * Run IO loops on a supplied {@link EventLoopGroup} from the {@link LoopResources}
	 * container.
	 *
	 * @param channelResources a {@link LoopResources} accepting native runtime
	 * expectation and returning an eventLoopGroup.
	 * @param preferNative Should the connector prefer native (epoll/kqueue) if available.
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer runOn(LoopResources channelResources, boolean preferNative) {
		return new UdpServerRunOn(this, channelResources, preferNative, null);
	}

	/**
	 * Run IO loops on a supplied {@link EventLoopGroup} from the {@link LoopResources}
	 * container.
	 *
	 * @param channelResources a {@link LoopResources} accepting native runtime
	 * expectation and returning an eventLoopGroup.
	 * @param family a specific {@link InternetProtocolFamily} to run with
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer runOn(LoopResources channelResources, InternetProtocolFamily family) {
		return new UdpServerRunOn(this, channelResources, false, family);
	}

	/**
	 * Whether to enable metrics to be collected and registered in Micrometer's
	 * {@link io.micrometer.core.instrument.Metrics#globalRegistry globalRegistry}
	 * under the name {@link reactor.netty.Metrics#UDP_SERVER_PREFIX}. Applications can
	 * separately register their own
	 * {@link io.micrometer.core.instrument.config.MeterFilter filters} associated with this name.
	 * For example, to put an upper bound on the number of tags produced:
	 * <pre class="code">
	 * MeterFilter filter = ... ;
	 * Metrics.globalRegistry.config().meterFilter(MeterFilter.maximumAllowableTags(UDP_SERVER_PREFIX, 100, filter));
	 * </pre>
	 * <p>By default this is not enabled.
	 *
	 * @param metricsEnabled true enables metrics collection; false disables it
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer metrics(boolean metricsEnabled) {
		if (metricsEnabled) {
			if (!Metrics.isInstrumentationAvailable()) {
				throw new UnsupportedOperationException(
						"To enable metrics, you must add the dependency `io.micrometer:micrometer-core`" +
								" to the class path first");
			}

			return bootstrap(b -> BootstrapHandlers.updateMetricsSupport(b, getOrCreateMetricsRecorder()));
		}
		else {
			return bootstrap(BootstrapHandlers::removeMetricsSupport);
		}
	}

	/**
	 * Specifies whether the metrics are enabled on the {@link UdpServer}.
	 * All generated metrics are provided to the specified recorder.
	 *
	 * @param metricsEnabled if true enables the metrics on the server.
	 * @param recorder the {@link ChannelMetricsRecorder}
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer metrics(boolean metricsEnabled, ChannelMetricsRecorder recorder) {
		if (metricsEnabled) {
			Objects.requireNonNull(recorder, "recorder");
			return bootstrap(b -> BootstrapHandlers.updateMetricsSupport(b, recorder));
		}
		else {
			return bootstrap(BootstrapHandlers::removeMetricsSupport);
		}
	}

	/**
	 * Apply or remove a wire logger configuration using {@link UdpServer} category
	 * and {@code DEBUG} logger level
	 *
	 * @param enable Specifies whether the wire logger configuration will be added to
	 *               the pipeline
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer wiretap(boolean enable) {
		if (enable) {
			return bootstrap(b -> BootstrapHandlers.updateLogSupport(b, LOGGING_HANDLER));
		}
		else {
			return bootstrap(b -> BootstrapHandlers.removeConfiguration(b, NettyPipeline.LoggingHandler));
		}
	}

	/**
	 * Apply a wire logger configuration using the specified category
	 * and {@code DEBUG} logger level
	 *
	 * @param category the logger category
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer wiretap(String category) {
		return wiretap(category, LogLevel.DEBUG);
	}

	/**
	 * Apply a wire logger configuration using the specified category
	 * and logger level
	 *
	 * @param category the logger category
	 * @param level the logger level
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer wiretap(String category, LogLevel level) {
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(level, "level");
		return bootstrap(b -> BootstrapHandlers.updateLogSupport(b,
				new LoggingHandler(category, level)));
	}

	/**
	 * Materialize a Bootstrap from the parent {@link UdpServer} chain to use with {@link
	 * #bind(Bootstrap)} or separately
	 *
	 * @return a configured {@link Bootstrap}
	 */
	protected Bootstrap configure() {
		return DEFAULT_BOOTSTRAP.clone();
	}

	/**
	 * Bind the {@link UdpServer} and return a {@link Mono} of {@link Connection}
	 *
	 * @param b the {@link Bootstrap} to bind
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	protected abstract Mono<? extends Connection> bind(Bootstrap b);

	final AtomicReference<MicrometerChannelMetricsRecorder> channelMetricsRecorder = new AtomicReference<>();
	final MicrometerChannelMetricsRecorder getOrCreateMetricsRecorder() {
		MicrometerChannelMetricsRecorder recorder = channelMetricsRecorder.get();
		if (recorder == null) {
			channelMetricsRecorder.compareAndSet(null,
					new MicrometerChannelMetricsRecorder(reactor.netty.Metrics.UDP_SERVER_PREFIX, "udp"));
			recorder = getOrCreateMetricsRecorder();
		}
		return recorder;
	}

	/**
	 * The default port for reactor-netty servers. Defaults to 12012 but can be tuned via
	 * the {@code PORT} <b>environment variable</b>.
	 */
	static final int DEFAULT_PORT =
			System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) :
					12012;

	static final Bootstrap DEFAULT_BOOTSTRAP =
			new Bootstrap().option(ChannelOption.AUTO_READ, false)
			               .localAddress(NetUtil.LOCALHOST, DEFAULT_PORT);

	static {
		BootstrapHandlers.channelOperationFactory(DEFAULT_BOOTSTRAP, (ch, c, msg) -> new UdpOperations(ch, c));
	}

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(UdpServer.class);
	static final Logger         log             = Loggers.getLogger(UdpServer.class);

	static String getHost(Bootstrap b) {
		if (b.config()
			 .localAddress() instanceof InetSocketAddress) {
			return ((InetSocketAddress) b.config()
			                             .localAddress()).getHostString();
		}
		return NetUtil.LOCALHOST.getHostAddress();
	}

	static int getPort(Bootstrap b) {
		if (b.config()
			 .localAddress() instanceof InetSocketAddress) {
			return ((InetSocketAddress) b.config()
			                             .localAddress()).getPort();
		}
		return DEFAULT_PORT;
	}
}
