/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.resources.LoopResources;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
 * {@code UdpServer.create()
 * .doOnBind(startMetrics)
 * .doOnBound(startedMetrics)
 * .doOnUnbind(stopMetrics)
 * .host("127.0.0.1")
 * .port(1234)
 * .block() }
 *
 * @author Stephane Maldini
 */
public abstract class UdpServer {

	/**
	 * The default port for reactor-netty servers. Defaults to 12012 but can be tuned via
	 * the {@code PORT} <b>environment variable</b>.
	 */
	public static final int DEFAULT_PORT =
			System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) :
					12012;

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
	 * If updateConfiguration phase fails, a {@link Mono#error(Throwable)} will be returned;
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	public final Mono<? extends Connection> bind() {
		Bootstrap b;
		try{
			b = configure();
		}
		catch (Throwable t){
			Exceptions.throwIfFatal(t);
			return Mono.error(t);
		}
		return bind(b);
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
		return new UdpServerLifecycle(this, doOnBind, null, null);

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
		return new UdpServerLifecycle(this, null, doOnBound, null);
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
		return new UdpServerLifecycle(this, null, null, doOnUnbound);
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
		return new UdpServerLifecycle(this, onBind, onBound, onUnbound);
	}

	/**
	 * Attach an IO handler to react on connected client
	 *
	 * @param handler an IO handler that can dispose underlying connection when {@link
	 * Publisher} terminates.
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer handler(BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return doOnBound(c -> {
			if (log.isDebugEnabled()) {
				log.debug("[UdpServer] {} handler is being applied: {}", c.channel(), handler);
			}

			Mono.fromDirect(handler.apply((UdpInbound) c,
					(UdpOutbound) c))
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
	 * Set a {@link ChannelOption} value for low level connection settings like SO_TIMEOUT
	 * or SO_KEEPALIVE. This will apply to each new channel from remote peer.
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
	 * container. Will prefer native (epoll) implementation if available unless the
	 * environment property {@literal reactor.ipc.netty.epoll} is set to {@literal
	 * false}.
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
	 * @param preferNative Should the connector prefer native (epoll) if available.
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
	 * Apply a wire logger configuration using {@link UdpServer} category
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer wiretap() {
		return bootstrap(b -> BootstrapHandlers.updateLogSupport(b, LOGGING_HANDLER));
	}

	/**
	 * Apply a wire logger configuration
	 *
	 * @param category the logger category
	 *
	 * @return a new {@link UdpServer}
	 */
	public final UdpServer wiretap(String category) {
		return wiretap(category, LogLevel.DEBUG);
	}

	/**
	 * Apply a wire logger configuration
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

	static final Bootstrap DEFAULT_BOOTSTRAP =
			new Bootstrap().option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
					.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
					.option(ChannelOption.AUTO_READ, false)
					.option(ChannelOption.SO_RCVBUF, 1024 * 1024)
					.option(ChannelOption.SO_SNDBUF, 1024 * 1024)
					.localAddress(NetUtil.LOCALHOST, DEFAULT_PORT);

	static {
		BootstrapHandlers.channelOperationFactory(DEFAULT_BOOTSTRAP,
				(ChannelOperations.OnSetup< DatagramChannel>) (ch, c, msg) -> UdpOperations.bindUdp(ch, c));
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
