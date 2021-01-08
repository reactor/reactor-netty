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

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.handler.logging.LogLevel;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.Transport;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static reactor.netty.ReactorNetty.format;

/**
 * A UdpServer allows to build in a safe immutable way a UDP server that is materialized
 * and connecting when {@link #bind()} is ultimately called.
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
 * </pre>
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public abstract class UdpServer extends Transport<UdpServer, UdpServerConfig> {

	/**
	 * Prepare a {@link UdpServer}
	 *
	 * @return a {@link UdpServer}
	 */
	public static UdpServer create() {
		return UdpServerBind.INSTANCE;
	}

	@Override
	public final <A> UdpServer attr(AttributeKey<A> key, @Nullable A value) {
		return super.attr(key, value);
	}

	/**
	 * Binds the {@link UdpServer} and returns a {@link Mono} of {@link Connection}. If
	 * {@link Mono} is cancelled, the underlying binding will be aborted. Once the {@link
	 * Connection} has been emitted and is not necessary anymore, disposing the main server
	 * loop must be done by the user via {@link Connection#dispose()}.
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	public abstract Mono<? extends Connection> bind();

	@Override
	public final UdpServer bindAddress(Supplier<? extends SocketAddress> bindAddressSupplier) {
		return super.bindAddress(bindAddressSupplier);
	}

	/**
	 * Starts the server in a blocking fashion, and waits for it to finish initializing
	 * or the startup timeout expires (the startup timeout is {@code 45} seconds). The
	 * returned {@link Connection} offers simple server API, including to {@link
	 * Connection#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @return a {@link Connection}
	 */
	public final Connection bindNow() {
		return bindNow(Duration.ofSeconds(45));
	}

	/**
	 * Start the server in a blocking fashion, and wait for it to finish initializing
	 * or the provided startup timeout expires. The returned {@link Connection}
	 * offers simple server API, including to {@link Connection#disposeNow()}
	 * shut it down in a blocking fashion.
	 *
	 * @param timeout max startup timeout (resolution: ns)
	 * @return a {@link Connection}
	 */
	public final Connection bindNow(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		try {
			return Objects.requireNonNull(bind().block(timeout), "aborted");
		}
		catch (IllegalStateException e) {
			if (e.getMessage().contains("blocking read")) {
				throw new IllegalStateException("UdpServer couldn't be started within " + timeout.toMillis() + "ms");
			}
			throw e;
		}
	}

	/**
	 * Set or add a callback called when {@link UdpServer} is about to start listening for incoming traffic.
	 *
	 * @param doOnBind a consumer observing connected events
	 * @return a new {@link UdpServer} reference
	 */
	public final UdpServer doOnBind(Consumer<? super UdpServerConfig> doOnBind) {
		Objects.requireNonNull(doOnBind, "doOnBind");
		UdpServer dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<UdpServerConfig> current = (Consumer<UdpServerConfig>) dup.configuration().doOnBind;
		dup.configuration().doOnBind = current == null ? doOnBind : current.andThen(doOnBind);
		return dup;
	}

	/**
	 * Set or add a callback called after {@link UdpServer} has been started.
	 *
	 * @param doOnBound a consumer observing connected events
	 * @return a new {@link UdpServer} reference
	 */
	public final UdpServer doOnBound(Consumer<? super Connection> doOnBound) {
		Objects.requireNonNull(doOnBound, "doOnBound");
		UdpServer dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<Connection> current = (Consumer<Connection>) dup.configuration().doOnBound;
		dup.configuration().doOnBound = current == null ? doOnBound : current.andThen(doOnBound);
		return dup;
	}

	/**
	 * Set or add a callback called after {@link UdpServer} has been shutdown.
	 *
	 * @param doOnUnbound a consumer observing unbound events
	 * @return a new {@link UdpServer} reference
	 */
	public final UdpServer doOnUnbound(Consumer<? super Connection> doOnUnbound) {
		Objects.requireNonNull(doOnUnbound, "doOnUnbound");
		UdpServer dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<Connection> current = (Consumer<Connection>) dup.configuration().doOnUnbound;
		dup.configuration().doOnUnbound = current == null ? doOnUnbound : current.andThen(doOnUnbound);
		return dup;
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
		return doOnBound(new OnBoundHandle(handler));
	}

	/**
	 * The host to which this server should bind.
	 *
	 * @param host the host to bind to.
	 * @return a new {@link UdpServer} reference
	 */
	public final UdpServer host(String host) {
		return bindAddress(() -> AddressUtils.updateHost(configuration().bindAddress(), host));
	}

	@Override
	public final UdpServer metrics(boolean enable) {
		return super.metrics(enable);
	}

	@Override
	public final UdpServer metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder) {
		return super.metrics(enable, recorder);
	}

	@Override
	public final UdpServer observe(ConnectionObserver observer) {
		return super.observe(observer);
	}

	@Override
	public final <O> UdpServer option(ChannelOption<O> key, @Nullable O value) {
		return super.option(key, value);
	}

	/**
	 * The port to which this server should bind.
	 *
	 * @param port The port to bind to.
	 * @return a new {@link UdpServer} reference
	 */
	public final UdpServer port(int port) {
		return bindAddress(() -> AddressUtils.updatePort(configuration().bindAddress(), port));
	}

	@Override
	public final UdpServer runOn(EventLoopGroup eventLoopGroup) {
		return super.runOn(eventLoopGroup);
	}

	@Override
	public final UdpServer runOn(LoopResources channelResources) {
		return super.runOn(channelResources);
	}

	/**
	 * Run IO loops on a supplied {@link EventLoopGroup} from the {@link LoopResources} container.
	 *
	 * @param loopResources a new loop resources
	 * @param preferNative should prefer running on epoll, kqueue or similar instead of java NIO
	 * @return a new {@link UdpServer} reference
	 */
	@Override
	public final UdpServer runOn(LoopResources loopResources, boolean preferNative) {
		Objects.requireNonNull(loopResources, "loopResources");
		UdpServer dup = super.runOn(loopResources, preferNative);
		dup.configuration().family = null;
		return dup;
	}

	/**
	 * Run IO loops on a supplied {@link EventLoopGroup} from the {@link LoopResources} container.
	 *
	 * @param loopResources a new loop resources
	 * @param family a specific {@link InternetProtocolFamily} to run with
	 * @return a new {@link UdpServer} reference
	 */
	public final UdpServer runOn(LoopResources loopResources, InternetProtocolFamily family) {
		Objects.requireNonNull(loopResources, "loopResources");
		Objects.requireNonNull(family, "family");
		UdpServer dup = super.runOn(loopResources, false);
		dup.configuration().family = family;
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
	 * @since 1.0.3
	 */
	public final Mono<Void> warmup() {
		return Mono.fromRunnable(() -> configuration().eventLoopGroup());
	}

	@Override
	public final UdpServer wiretap(boolean enable) {
		return super.wiretap(enable);
	}

	@Override
	public final UdpServer wiretap(String category) {
		return super.wiretap(category);
	}

	@Override
	public final UdpServer wiretap(String category, LogLevel level) {
		return super.wiretap(category, level);
	}

	static final Logger log = Loggers.getLogger(UdpServer.class);

	static final class OnBoundHandle implements Consumer<Connection> {

		final BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler;

		OnBoundHandle(BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler) {
			this.handler = handler;
		}

		@Override
		public void accept(Connection c) {
			if (log.isDebugEnabled()) {
				log.debug(format(c.channel(), "Handler is being applied: {}"), handler);
			}

			Mono.fromDirect(handler.apply((UdpInbound) c, (UdpOutbound) c))
			    .subscribe(c.disposeSubscriber());
		}
	}
}
