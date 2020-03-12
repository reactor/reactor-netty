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

package reactor.netty.tcp;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.Metrics;

import static reactor.netty.ReactorNetty.format;

/**
 * A TcpClient allows to build in a safe immutable way a TCP client that
 * is materialized and connecting when {@link #connect(Bootstrap)} is ultimately called.
 *
 * <p> Internally, materialization happens in two phases, first {@link #configure()} is
 * called to retrieve a ready to use {@link Bootstrap} then {@link #connect(Bootstrap)}
 * is called.
 *
 * <p> Example:
 * <pre>
 * {@code
 * TcpClient.create()
 *          .doOnConnect(connectMetrics)
 *          .doOnConnected(connectedMetrics)
 *          .doOnDisconnected(disconnectedMetrics)
 *          .host("127.0.0.1")
 *          .port(1234)
 *          .secure()
 *          .connect()
 *          .block()
 * }
 *
 * @author Stephane Maldini
 */
public abstract class TcpClient {

	/**
	 * Prepare a pooled {@link TcpClient}
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient create() {
		return create(TcpResources.get());
	}


	/**
	 * Prepare a {@link TcpClient}
	 *
	 * @param provider a {@link ConnectionProvider} to acquire connections
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient create(ConnectionProvider provider) {
		return new TcpClientConnect(provider);
	}

	/**
	 * Prepare a non pooled {@link TcpClient}
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient newConnection() {
		return TcpClientConnect.INSTANCE;
	}

	/**
	 * The address to which this client should connect for each subscribe.
	 *
	 * @param connectAddressSupplier A supplier of the address to connect to.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient addressSupplier(Supplier<? extends SocketAddress> connectAddressSupplier) {
		SocketAddress lazy = TcpUtils.lazyAddress(connectAddressSupplier);
		return bootstrap(b -> b.remoteAddress(lazy));
	}

	/**
	 * Inject default attribute to the future {@link Channel} connection. They will be
	 * available via {@link Channel#attr(AttributeKey)}.
	 * If the {@code value} is {@code null}, the attribute of the specified {@code key}
	 * is removed.
	 *
	 * @param key the attribute key
	 * @param value the attribute value
	 * @param <T> the attribute type
	 *
	 * @return a new {@link TcpClient}
	 *
	 * @see Bootstrap#attr(AttributeKey, Object)
	 */
	public final  <T> TcpClient attr(AttributeKey<T> key, @Nullable T value) {
		Objects.requireNonNull(key, "key");
		return bootstrap(b -> b.attr(key, value));
	}

	/**
	 * Apply {@link Bootstrap} configuration given mapper taking currently configured one
	 * and returning a new one to be ultimately used for socket binding.
	 * <p> Configuration will apply during {@link #configure()} phase.
	 *
	 *
	 * @param bootstrapMapper A bootstrap mapping function to update configuration and return an
	 * enriched bootstrap.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient bootstrap(Function<? super Bootstrap, ? extends Bootstrap> bootstrapMapper) {
		return new TcpClientBootstrap(this, bootstrapMapper);
	}

	/**
	 * Materialize a Bootstrap from the parent {@link TcpClient} chain to use with {@link
	 * #connect(Bootstrap)} or separately
	 *
	 * @return a configured {@link Bootstrap}
	 */
	public Bootstrap configure() {
		return DEFAULT_BOOTSTRAP.clone();
	}

	/**
	 * Bind the {@link TcpClient} and return a {@link Mono} of {@link Connection}. If
	 * {@link Mono} is cancelled, the underlying connection will be aborted. Once the
	 * {@link Connection} has been emitted and is not necessary anymore, disposing must be
	 * done by the user via {@link Connection#dispose()}.
	 *
	 * If update configuration phase fails, a {@link Mono#error(Throwable)} will be returned
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	public final Mono<? extends Connection> connect() {
		Bootstrap b;
		try {
			b = configure();
		}
		catch (Throwable t) {
			Exceptions.throwIfJvmFatal(t);
			return Mono.error(t);
		}
		return connect(b);
	}

	/**
	 * Connect the {@link TcpClient} and return a {@link Mono} of {@link Connection}
	 *
	 * @param b the {@link Bootstrap} to connect
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	public abstract Mono<? extends Connection> connect(Bootstrap b);

	/**
	 * Block the {@link TcpClient} and return a {@link Connection}. Disposing must be
	 * done by the user via {@link Connection#dispose()}. The max connection
	 * timeout is 45 seconds.
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	public final Connection connectNow() {
		return connectNow(Duration.ofSeconds(45));
	}

	/**
	 * Block the {@link TcpClient} and return a {@link Connection}. Disposing must be
	 * done by the user via {@link Connection#dispose()}.
	 *
	 * @param timeout connect timeout
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	public final Connection connectNow(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		try {
			return Objects.requireNonNull(connect().block(timeout), "aborted");
		}
		catch (IllegalStateException e) {
			if (e.getMessage().contains("blocking read")) {
				throw new IllegalStateException("TcpClient couldn't be started within "
						+ timeout.toMillis() + "ms");
			}
			throw e;
		}
	}

	/**
	 * Setup a callback called when {@link Channel} is about to connect.
	 *
	 * @param doOnConnect a runnable observing connected events
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient doOnConnect(Consumer<? super Bootstrap> doOnConnect) {
		Objects.requireNonNull(doOnConnect, "doOnConnect");
		return new TcpClientDoOn(this, doOnConnect, null, null);
	}

	/**
	 * Setup a callback called after {@link Channel} has been connected.
	 *
	 * @param doOnConnected a consumer observing connected events
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient doOnConnected(Consumer<? super Connection> doOnConnected) {
		Objects.requireNonNull(doOnConnected, "doOnConnected");
		return new TcpClientDoOn(this, null, doOnConnected, null);
	}

	/**
	 * Setup a callback called after {@link Channel} has been disconnected.
	 *
	 * @param doOnDisconnected a consumer observing disconnected events
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient doOnDisconnected(Consumer<? super Connection> doOnDisconnected) {
		Objects.requireNonNull(doOnDisconnected, "doOnDisconnected");
		return new TcpClientDoOn(this, null, null, doOnDisconnected);
	}

	/**
	 * Setup all lifecycle callbacks called on or after {@link io.netty.channel.Channel}
	 * has been connected and after it has been disconnected.
	 *
	 * @param doOnConnect a consumer observing client start event
	 * @param doOnConnected a consumer observing client started event
	 * @param doOnDisconnected a consumer observing client stop event
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient doOnLifecycle(Consumer<? super Bootstrap> doOnConnect,
			Consumer<? super Connection> doOnConnected,
			Consumer<? super Connection> doOnDisconnected) {
		Objects.requireNonNull(doOnConnect, "doOnConnect");
		Objects.requireNonNull(doOnConnected, "doOnConnected");
		Objects.requireNonNull(doOnDisconnected, "doOnDisconnected");
		return new TcpClientDoOn(this, doOnConnect, doOnConnected, doOnDisconnected);
	}

	/**
	 * The host to which this client should connect.
	 *
	 * @param host The host to connect to.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient host(String host) {
		Objects.requireNonNull(host, "host");
		return bootstrap(b -> TcpUtils.updateHost(b, host));
	}

	/**
	 * Attach an IO handler to react on connected client
	 *
	 * @param handler an IO handler that can dispose underlying connection when {@link
	 * Publisher} terminates.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient handle(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return doOnConnected(c -> {
			if (log.isDebugEnabled()) {
				log.debug(format(c.channel(), "Handler is being applied: {}"), handler);
			}

			Mono.fromDirect(handler.apply((NettyInbound) c, (NettyOutbound) c))
			    .subscribe(c.disposeSubscriber());
		});
	}

	/**
	 * Return true if that {@link TcpClient} is configured with a proxy
	 *
	 * @return true if that {@link TcpClient} is configured with a proxy
	 */
	public final boolean hasProxy(){
		return proxyProvider() != null;
	}

	/**
	 * Return true if that {@link TcpClient} secured via SSL transport
	 *
	 * @return true if that {@link TcpClient} secured via SSL transport
	 */
	public final boolean isSecure(){
		return sslProvider() != null;
	}


	/**
	 * Remove any previously applied Proxy configuration customization
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient noProxy() {
		return new TcpClientUnproxy(this);
	}

	/**
	 * Remove any previously applied SSL configuration customization
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient noSSL() {
		return new TcpClientUnsecure(this);
	}

	/**
	 * Setup all lifecycle callbacks called on or after {@link io.netty.channel.Channel}
	 * has been connected and after it has been disconnected.
	 *
	 * @param observer a consumer observing state changes
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient observe(ConnectionObserver observer) {
		return new TcpClientObserve(this, observer);
	}

	/**
	 * Set a {@link ChannelOption} value for low level connection settings like {@code SO_TIMEOUT}
	 * or {@code SO_KEEPALIVE}. This will apply to each new channel from remote peer.
	 * Use a value of {@code null} to remove a previous set {@link ChannelOption}.
	 *
	 * @param key the option key
	 * @param value the option value
	 * @param <T> the option type
	 *
	 * @return new {@link TcpClient}
	 *
	 * @see Bootstrap#option(ChannelOption, Object)
	 */
	public final <T> TcpClient option(ChannelOption<T> key, @Nullable T value) {
		Objects.requireNonNull(key, "key");
		return bootstrap(b -> b.option(key, value));
	}

	/**
	 * The port to which this client should connect.
	 *
	 * @param port The port to connect to.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient port(int port) {
		return bootstrap(b -> TcpUtils.updatePort(b, port));
	}

	/**
	 * Apply a proxy configuration
	 *
	 * @param proxyOptions the proxy configuration callback
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient proxy(Consumer<? super ProxyProvider.TypeSpec> proxyOptions) {
		return new TcpClientProxy(this, proxyOptions);
	}


	/**
	 * Return the current {@link ProxyProvider} if any
	 *
	 * @return the current {@link ProxyProvider} if any
	 */
	@Nullable
	public ProxyProvider proxyProvider() {
		return null;
	}

	/**
	 * Assign an {@link AddressResolverGroup}.
	 *
	 * @param resolver the new {@link AddressResolverGroup}
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient resolver(AddressResolverGroup<?> resolver) {
		Objects.requireNonNull(resolver, "resolver");
		return bootstrap(b -> b.resolver(resolver));
	}

	/**
	 * Run IO loops on the given {@link EventLoopGroup}.
	 *
	 * @param eventLoopGroup an eventLoopGroup to share
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient runOn(EventLoopGroup eventLoopGroup) {
		Objects.requireNonNull(eventLoopGroup, "eventLoopGroup");
		return runOn(preferNative -> eventLoopGroup);
	}

	/**
	 * Run IO loops on a supplied {@link EventLoopGroup} from the
	 * {@link LoopResources} container. Will prefer native (epoll/kqueue) implementation if
	 * available unless the environment property {@code reactor.netty.native} is set
	 * to {@code false}.
	 *
	 * @param channelResources a {@link LoopResources} accepting native runtime expectation and
	 * returning an eventLoopGroup
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient runOn(LoopResources channelResources) {
		return runOn(channelResources, LoopResources.DEFAULT_NATIVE);
	}

	/**
	 * Run IO loops on a supplied {@link EventLoopGroup} from the
	 * {@link LoopResources} container.
	 *
	 * @param channelResources a {@link LoopResources} accepting native runtime expectation and
	 * returning an eventLoopGroup.
	 * @param preferNative Should the connector prefer native (epoll/kqueue) if available.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient runOn(LoopResources channelResources, boolean preferNative) {
		return new TcpClientRunOn(this, channelResources, preferNative);
	}

	/**
	 * Enable default sslContext support. The default {@link SslContext} will be
	 * assigned to
	 * with a default value of {@code 10} seconds handshake timeout unless
	 * the environment property {@code reactor.netty.tcp.sslHandshakeTimeout} is set.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient secure() {
		return new TcpClientSecure(this, null);
	}

	/**
	 * Apply an SSL configuration customization via the passed builder. The builder
	 * will produce the {@link SslContext} to be passed to with a default value of
	 * {@code 10} seconds handshake timeout unless the environment property {@code
	 * reactor.netty.tcp.sslHandshakeTimeout} is set.
	 *
	 * @param sslProviderBuilder builder callback for further customization of SslContext.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		return TcpClientSecure.secure(this, sslProviderBuilder);
	}

	/**
	 * Apply an SSL configuration via the passed {@link SslProvider}.
	 *
	 * @param sslProvider The provider to set when configuring SSL
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient secure(SslProvider sslProvider) {
		return new TcpClientSecure(this, sslProvider);
	}

	/**
	 * Return the current {@link SslProvider} if that {@link TcpClient} secured via SSL
	 * transport or null
	 *
	 * @return the current {@link SslProvider} if that {@link TcpClient} secured via SSL
	 * transport or null
	 */
	@Nullable
	public SslProvider sslProvider(){
		return null;
	}

	/**
	 * Whether to enable metrics to be collected and registered in Micrometer's
	 * {@link io.micrometer.core.instrument.Metrics#globalRegistry globalRegistry}
	 * under the name {@link reactor.netty.Metrics#TCP_CLIENT_PREFIX}. Applications can
	 * separately register their own
	 * {@link io.micrometer.core.instrument.config.MeterFilter filters} associated with this name.
	 * For example, to put an upper bound on the number of tags produced:
	 * <pre class="code">
	 * MeterFilter filter = ... ;
	 * Metrics.globalRegistry.config().meterFilter(MeterFilter.maximumAllowableTags(TCP_CLIENT_PREFIX, 100, filter));
	 * </pre>
	 * <p>By default this is not enabled.
	 *
	 * @param metricsEnabled true enables metrics collection; false disables it
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient metrics(boolean metricsEnabled) {
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
	 * Specifies whether the metrics are enabled on the {@link TcpClient}.
	 * All generated metrics are provided to the specified recorder.
	 *
	 * @param metricsEnabled if true enables the metrics on the client.
	 * @param recorder the {@link ChannelMetricsRecorder}
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient metrics(boolean metricsEnabled, ChannelMetricsRecorder recorder) {
		if (metricsEnabled) {
			Objects.requireNonNull(recorder, "recorder");
			return bootstrap(b -> BootstrapHandlers.updateMetricsSupport(b, recorder));
		}
		else {
			return bootstrap(BootstrapHandlers::removeMetricsSupport);
		}
	}

	/**
	 * Apply or remove a wire logger configuration using {@link TcpClient} category
	 * and {@code DEBUG} logger level
	 *
	 * @param enable Specifies whether the wire logger configuration will be added to
	 *               the pipeline
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient wiretap(boolean enable) {
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
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient wiretap(String category) {
		return wiretap(category, LogLevel.DEBUG);
	}

	/**
	 * Apply a wire logger configuration using the specified category
	 * and logger level
	 *
	 * @param category the logger category
	 * @param level the logger level
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient wiretap(String category, LogLevel level) {
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(level, "level");
		return bootstrap(b -> BootstrapHandlers.updateLogSupport(b, category, level));
	}

	final AtomicReference<MicrometerChannelMetricsRecorder> channelMetricsRecorder = new AtomicReference<>();
	final MicrometerChannelMetricsRecorder getOrCreateMetricsRecorder() {
		MicrometerChannelMetricsRecorder recorder = channelMetricsRecorder.get();
		if (recorder == null) {
			channelMetricsRecorder.compareAndSet(null,
					new MicrometerChannelMetricsRecorder(reactor.netty.Metrics.TCP_CLIENT_PREFIX, "tcp"));
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
			               .remoteAddress(InetSocketAddressUtil.createUnresolved(NetUtil.LOCALHOST.getHostAddress(), DEFAULT_PORT));

	static {
		BootstrapHandlers.channelOperationFactory(DEFAULT_BOOTSTRAP, TcpUtils.TCP_OPS);
	}
	static final LoggingHandler        LOGGING_HANDLER   = new LoggingHandler(TcpClient.class);
	static final Logger                log               = Loggers.getLogger(TcpClient.class);
}
