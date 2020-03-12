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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.resources.LoopResources;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.Metrics;

/**
 * A TcpServer allows to build in a safe immutable way a TCP server that is materialized
 * and connecting when {@link #bind(ServerBootstrap)} is ultimately called.
 * <p> Internally, materialization happens in two phases:</p>
 * <ul>
 * <li>first {@link #configure()} is called to retrieve a ready to use {@link ServerBootstrap}</li>
 * <li>then {@link #bind(ServerBootstrap)} is called.</li>
 * </ul>
 * <p> Example:</p>
 * <pre>
 * {@code
 * TcpServer.create()
 *          .doOnBind(startMetrics)
 *          .doOnBound(startedMetrics)
 *          .doOnUnbound(stopMetrics)
 *          .host("127.0.0.1")
 *          .port(1234)
 *          .bind()
 *          .block()
 * }
 * </pre>
 *
 * @author Stephane Maldini
 */
public abstract class TcpServer {

	/**
	 * Prepare a {@link TcpServer}
	 *
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create() {
		return TcpServerBind.INSTANCE;
	}

	/**
	 * The address to which this server should bind on subscribe.
	 *
	 * @param bindingAddressSupplier A supplier of the address to bind to.
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer addressSupplier(Supplier<? extends SocketAddress> bindingAddressSupplier) {
		Objects.requireNonNull(bindingAddressSupplier, "bindingAddressSupplier");
		return bootstrap(b -> b.localAddress(bindingAddressSupplier.get()));
	}

	/**
	 * Injects default attribute to the future child {@link Channel} connections. It
	 * will be available via {@link Channel#attr(AttributeKey)}.
	 * If the {@code value} is {@code null}, the attribute of the specified {@code key}
	 * is removed.
	 *
	 * @param key the attribute key
	 * @param value the attribute value
	 * @param <T> the attribute type
	 *
	 * @return a new {@link TcpServer}
	 *
	 * @see ServerBootstrap#childAttr(AttributeKey, Object)
	 */
	public final <T> TcpServer attr(AttributeKey<T> key, @Nullable T value) {
		Objects.requireNonNull(key, "key");
		return bootstrap(b -> b.childAttr(key, value));
	}

	/**
	 * Applies {@link ServerBootstrap} configuration given mapper taking currently configured one
	 * and returning a new one to be ultimately used for socket binding.
	 * <p> Configuration will apply during {@link #configure()} phase.</p>
	 *
	 * @param bootstrapMapper A bootstrap mapping function to update configuration and return an
	 * enriched bootstrap.
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer bootstrap(Function<? super ServerBootstrap, ? extends ServerBootstrap> bootstrapMapper) {
		return new TcpServerBootstrap(this, bootstrapMapper);
	}

	/**
	 * Binds the {@link TcpServer} and returns a {@link Mono} of {@link DisposableServer}. If
	 * {@link Mono} is cancelled, the underlying binding will be aborted. Once the {@link
	 * DisposableServer} has been emitted and is not necessary anymore, disposing the main server
	 * loop must be done by the user via {@link DisposableServer#dispose()}.
	 *
	 * If updateConfiguration phase fails, a {@link Mono#error(Throwable)} will be returned;
	 *
	 * @return a {@link Mono} of {@link DisposableServer}
	 */
	public final Mono<? extends DisposableServer> bind() {
		ServerBootstrap b;
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
	 * Binds the {@link TcpServer} and returns a {@link Mono} of {@link DisposableServer}
	 *
	 * @param b the {@link ServerBootstrap} to bind
	 *
	 * @return a {@link Mono} of {@link DisposableServer}
	 */
	public abstract Mono<? extends DisposableServer> bind(ServerBootstrap b);

	/**
	 * Starts the server in a blocking fashion, and waits for it to finish initializing
	 * or the startup timeout expires (the startup timeout is {@code 45} seconds). The
	 * returned {@link DisposableServer} offers simple server API, including to {@link
	 * DisposableServer#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @return a {@link DisposableServer}
	 */
	public final DisposableServer bindNow() {
		return bindNow(Duration.ofSeconds(45));
	}

	/**
	 * Start the server in a blocking fashion, and wait for it to finish initializing
	 * or the provided startup timeout expires. The returned {@link DisposableServer}
	 * offers simple server API, including to {@link DisposableServer#disposeNow()}
	 * shut it down in a blocking fashion.
	 *
	 * @param timeout max startup timeout
	 *
	 * @return a {@link DisposableServer}
	 */
	public final DisposableServer bindNow(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		try {
			return Objects.requireNonNull(bind().block(timeout), "aborted");
		}
		catch (IllegalStateException e) {
			if (e.getMessage().contains("blocking read")) {
				throw new IllegalStateException("HttpServer couldn't be started within "
						+ timeout.toMillis() + "ms");
			}
			throw e;
		}
	}

	/**
	 * Start the server in a fully blocking fashion, not only waiting for it to initialize
	 * but also blocking during the full lifecycle of the server. Since most
	 * servers will be long-lived, this is more adapted to running a server out of a main
	 * method, only allowing shutdown of the servers through {@code sigkill}.
	 * <p>
	 * Note: {@link Runtime#addShutdownHook(Thread) JVM shutdown hook} is added by
	 * this method in order to properly disconnect the server upon receiving a
	 * {@code sigkill} signal.</p>
	 *
	 * @param timeout a timeout for server shutdown
	 * @param onStart an optional callback on server start
	 */
	public final void bindUntilJavaShutdown(Duration timeout, @Nullable Consumer<DisposableServer> onStart) {

		Objects.requireNonNull(timeout, "timeout");
		DisposableServer facade = bindNow();

		Objects.requireNonNull(facade, "facade");

		if (onStart != null) {
			onStart.accept(facade);
		}

		Runtime.getRuntime()
		       .addShutdownHook(new Thread(() -> facade.disposeNow(timeout)));

		facade.onDispose()
		      .block();
	}


	/**
	 * Materializes a {@link ServerBootstrap} from the parent {@link TcpServer} chain to use with
	 * {@link #bind(ServerBootstrap)} or separately
	 *
	 * @return a configured {@link ServerBootstrap}
	 */
	public abstract ServerBootstrap configure();

	/**
	 * Setups a callback called when {@link io.netty.channel.ServerChannel} is about to
	 * bind.
	 *
	 * @param doOnBind a consumer observing server start event
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer doOnBind(Consumer<? super ServerBootstrap> doOnBind) {
		Objects.requireNonNull(doOnBind, "doOnBind");
		return new TcpServerDoOn(this, doOnBind, null, null);

	}

	/**
	 * Setups a callback called when {@link io.netty.channel.ServerChannel} is
	 * bound.
	 *
	 * @param doOnBound a consumer observing server started event
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer doOnBound(Consumer<? super DisposableServer> doOnBound) {
		Objects.requireNonNull(doOnBound, "doOnBound");
		return new TcpServerDoOn(this, null, doOnBound, null);
	}

	/**
	 * Setups a callback called when a remote client is connected
	 *
	 * @param doOnConnection a consumer observing connected clients
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer doOnConnection(Consumer<? super Connection> doOnConnection) {
		return new TcpServerDoOnConnection(this, doOnConnection);
	}

	/**
	 * Setups a callback called when {@link io.netty.channel.ServerChannel} is
	 * unbound.
	 *
	 * @param doOnUnbind a consumer observing server stop event
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer doOnUnbound(Consumer<? super DisposableServer> doOnUnbind) {
		Objects.requireNonNull(doOnUnbind, "doOnUnbound");
		return new TcpServerDoOn(this, null, null, doOnUnbind);
	}

	/**
	 * Setups all lifecycle callbacks called on or after {@link io.netty.channel.Channel}
	 * has been bound and after it has been unbound.
	 *
	 * @param onBind a consumer observing server start event
	 * @param onBound a consumer observing server started event
	 * @param onUnbound a consumer observing server stop event
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer doOnLifecycle(Consumer<? super ServerBootstrap> onBind,
			Consumer<? super DisposableServer> onBound,
			Consumer<? super DisposableServer> onUnbound) {
		Objects.requireNonNull(onBind, "onBind");
		Objects.requireNonNull(onBound, "onBound");
		Objects.requireNonNull(onUnbound, "onUnbound");
		return new TcpServerDoOn(this, onBind, onBound, onUnbound);
	}

	/**
	 * Attaches an I/O handler to react on a connected client
	 *
	 * @param handler an I/O handler that can dispose underlying connection when
	 * {@link Publisher} terminates.
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer handle(BiFunction<? super NettyInbound, ? super
			NettyOutbound, ? extends Publisher<Void>> handler) {
		return new TcpServerHandle(this, handler);
	}

	/**
	 * The host to which this server should bind.
	 * By default the server will listen on any local address.
	 *
	 * @param host The host to bind to.
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer host(String host) {
		Objects.requireNonNull(host, "host");
		return bootstrap(b -> TcpUtils.updateHost(b, host));
	}

	/**
	 * Returns true if that {@link TcpServer} secured via SSL transport
	 *
	 * @return true if that {@link TcpServer} secured via SSL transport
	 */
	public final boolean isSecure(){
		return sslProvider() != null;
	}

	/**
	 * Removes any previously applied SSL configuration customization
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer noSSL() {
		return new TcpServerUnsecure(this);
	}

	/**
	 * Setups all lifecycle callbacks called on or after {@link io.netty.channel.Channel}
	 * has been connected and after it has been disconnected.
	 *
	 * @param observer a consumer observing state changes
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer observe(ConnectionObserver observer) {
		return new TcpServerObserve(this, observer);
	}

	/**
	 * Sets a {@link ChannelOption} value for low level connection settings like {@code SO_TIMEOUT}
	 * or {@code SO_KEEPALIVE}. This will apply to each new channel from remote peer.
	 * Use a value of {@code null} to remove a previous set {@link ChannelOption}.
	 *
	 * @param key the option key
	 * @param value the option value
	 * @param <T> the option type
	 *
	 * @return new {@link TcpServer}
	 *
	 * @see ServerBootstrap#childOption(ChannelOption, Object)
	 */
	public final <T> TcpServer option(ChannelOption<T> key, @Nullable T value) {
		Objects.requireNonNull(key, "key");
		return bootstrap(b -> b.childOption(key, value));
	}

	/**
	 * The port to which this server should bind.
	 * By default the system will pick up an ephemeral port in the {@link #bind()} operation:
	 *
	 * @param port The port to bind to.
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer port(int port) {
		return bootstrap(b -> TcpUtils.updatePort(b, port));
	}

	/**
	 * Runs I/O loops on the given {@link EventLoopGroup}.
	 *
	 * @param eventLoopGroup an eventLoopGroup to share
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer runOn(EventLoopGroup eventLoopGroup) {
		Objects.requireNonNull(eventLoopGroup, "eventLoopGroup");
		return runOn(preferNative -> eventLoopGroup);
	}

	/**
	 * Runs I/O loops on a supplied {@link EventLoopGroup} from the {@link LoopResources}
	 * container. Will prefer native (epoll/kqueue) implementation if available unless the
	 * environment property {@code reactor.netty.native} is set to {@code false}.
	 *
	 * @param channelResources a {@link LoopResources} accepting native runtime
	 * expectation and returning an eventLoopGroup
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer runOn(LoopResources channelResources) {
		return runOn(channelResources, LoopResources.DEFAULT_NATIVE);
	}

	/**
	 * Runs I/O loops on a supplied {@link EventLoopGroup} from the {@link LoopResources}
	 * container.
	 *
	 * @param channelResources a {@link LoopResources} accepting native runtime
	 * expectation and returning an eventLoopGroup.
	 * @param preferNative Should the connector prefer native (epoll/kqueue) if available.
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer runOn(LoopResources channelResources, boolean preferNative) {
		return new TcpServerRunOn(this, channelResources, preferNative);
	}

	/**
	 * Apply an SSL configuration customization via the passed builder. The builder
	 * will produce the {@link SslContext} to be passed to with a default value of
	 * {@code 10} seconds handshake timeout unless the environment property {@code
	 * reactor.netty.tcp.sslHandshakeTimeout} is set.
	 *
	 * If {@link SelfSignedCertificate} needs to be used, the sample below can be
	 * used. Note that {@link SelfSignedCertificate} should not be used in production.
	 * <pre>
	 * {@code
	 *     SelfSignedCertificate cert = new SelfSignedCertificate();
	 *     SslContextBuilder sslContextBuilder =
	 *             SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
	 *     secure(sslContextSpec -> sslContextSpec.sslContext(sslContextBuilder));
	 * }
	 * </pre>
	 *
	 * @param sslProviderBuilder builder callback for further customization of SslContext.
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		return TcpServerSecure.secure(this, sslProviderBuilder);
	}

	/**
	 * Applies an SSL configuration via the passed {@link SslProvider}.
	 *
	 * If {@link SelfSignedCertificate} needs to be used, the sample below can be
	 * used. Note that {@link SelfSignedCertificate} should not be used in production.
	 * <pre>
	 * {@code
	 *     SelfSignedCertificate cert = new SelfSignedCertificate();
	 *     SslContextBuilder sslContextBuilder =
	 *             SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
	 *     secure(sslContextSpec -> sslContextSpec.sslContext(sslContextBuilder));
	 * }
	 * </pre>
	 *
	 * @param sslProvider The provider to set when configuring SSL
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer secure(SslProvider sslProvider) {
		return new TcpServerSecure(this, sslProvider);
	}

	/**
	 * Injects default attribute to the future {@link io.netty.channel.ServerChannel}
	 * selector connection.
	 *
	 * @param key the attribute key
	 * @param value the attribute value
	 * @param <T> the attribute type
	 *
	 * @return a new {@link TcpServer}
	 *
	 * @see ServerBootstrap#attr(AttributeKey, Object)
	 */
	public final <T> TcpServer selectorAttr(AttributeKey<T> key, T value) {
		Objects.requireNonNull(key, "key");
		return bootstrap(b -> b.attr(key, value));
	}

	/**
	 * Sets a {@link ChannelOption} value for low level connection settings like {@code SO_TIMEOUT}
	 * or {@code SO_KEEPALIVE}. This will apply to parent selector channel.
	 *
	 * @param key the option key
	 * @param value the option value
	 * @param <T> the option type
	 *
	 * @return new {@link TcpServer}
	 *
	 * @see ServerBootstrap#option(ChannelOption, Object)
	 */
	public final <T> TcpServer selectorOption(ChannelOption<T> key, T value) {
		Objects.requireNonNull(key, "key");
		return bootstrap(b -> b.option(key, value));
	}

	/**
	 * Returns the current {@link SslProvider} if that {@link TcpServer} secured via SSL
	 * transport or null
	 *
	 * @return the current {@link SslProvider} if that {@link TcpServer} secured via SSL
	 * transport or null
	 */
	@Nullable
	public SslProvider sslProvider() {
		return null;
	}

	/**
	 * Apply or remove a wire logger configuration using {@link TcpServer} category
	 * and {@code DEBUG} logger level
	 *
	 * @param enable Specifies whether the wire logger configuration will be added to
	 *               the pipeline
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer wiretap(boolean enable) {
		if (enable) {
			return bootstrap(b -> BootstrapHandlers.updateLogSupport(b, LOGGING_HANDLER));
		}
		else {
			return bootstrap(b -> BootstrapHandlers.removeConfiguration(b, NettyPipeline.LoggingHandler));
		}
	}

	/**
	 * Whether to enable metrics to be collected and registered in Micrometer's
	 * {@link io.micrometer.core.instrument.Metrics#globalRegistry globalRegistry}
	 * under the name {@link reactor.netty.Metrics#TCP_SERVER_PREFIX}. Applications can
	 * separately register their own
	 * {@link io.micrometer.core.instrument.config.MeterFilter filters} associated with this name.
	 * For example, to put an upper bound on the number of tags produced:
	 * <pre class="code">
	 * MeterFilter filter = ... ;
	 * Metrics.globalRegistry.config().meterFilter(MeterFilter.maximumAllowableTags(TCP_SERVER_PREFIX, 100, filter));
	 * </pre>
	 * <p>By default this is not enabled.
	 *
	 * @param metricsEnabled true enables metrics collection; false disables it
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer metrics(boolean metricsEnabled) {
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
	 * Specifies whether the metrics are enabled on the {@link TcpServer}.
	 * All generated metrics are provided to the specified recorder.
	 *
	 * @param metricsEnabled if true enables the metrics on the server.
	 * @param recorder the {@link ChannelMetricsRecorder}
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer metrics(boolean metricsEnabled, ChannelMetricsRecorder recorder) {
		if (metricsEnabled) {
			Objects.requireNonNull(recorder, "recorder");
			return bootstrap(b -> BootstrapHandlers.updateMetricsSupport(b, recorder));
		}
		else {
			return bootstrap(BootstrapHandlers::removeMetricsSupport);
		}
	}

	/**
	 * Applies a wire logger configuration using the specified category
	 * and {@code DEBUG} logger level
	 *
	 * @param category the logger category
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer wiretap(String category) {
		return wiretap(category, LogLevel.DEBUG);
	}

	final AtomicReference<MicrometerChannelMetricsRecorder> channelMetricsRecorder = new AtomicReference<>();
	final MicrometerChannelMetricsRecorder getOrCreateMetricsRecorder() {
		MicrometerChannelMetricsRecorder recorder = channelMetricsRecorder.get();
		if (recorder == null) {
			channelMetricsRecorder.compareAndSet(null,
					new MicrometerChannelMetricsRecorder(reactor.netty.Metrics.TCP_SERVER_PREFIX, "tcp"));
			recorder = getOrCreateMetricsRecorder();
		}
		return recorder;
	}

	/**
	 * Applies a wire logger configuration using the specified category
	 * and logger level
	 *
	 * @param category the logger category
	 * @param level the logger level
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpServer wiretap(String category, LogLevel level) {
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(level, "level");
		return bootstrap(b -> BootstrapHandlers.updateLogSupport(b,
				new LoggingHandler(category, level)));
	}

	static final int                   DEFAULT_PORT      = 0;
	static final LoggingHandler        LOGGING_HANDLER   = new LoggingHandler(TcpServer.class);
	static final Logger                log               = Loggers.getLogger(TcpServer.class);
}
