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
import reactor.netty.transport.ClientTransport;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static reactor.netty.ReactorNetty.format;

/**
 * A UdpClient allows to build in a safe immutable way a UDP client that is materialized
 * and connecting when {@link #connect()} is ultimately called.
 * <p>
 * <p> Example:
 * <pre>
 * {@code
 * UdpClient.create()
 *          .doOnConnect(startMetrics)
 *          .doOnConnected(startedMetrics)
 *          .doOnDisconnected(stopMetrics)
 *          .host("127.0.0.1")
 *          .port(1234)
 *          .connect()
 *          .block()
 * }
 * </pre>
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public abstract class UdpClient extends ClientTransport<UdpClient, UdpClientConfig> {

	/**
	 * Prepare a {@link UdpClient}
	 *
	 * @return a {@link UdpClient}
	 */
	public static UdpClient create() {
		return UdpClientConnect.INSTANCE;
	}

	@Override
	public final <A> UdpClient attr(AttributeKey<A> key, @Nullable A value) {
		return super.attr(key, value);
	}

	@Override
	public final UdpClient bindAddress(Supplier<? extends SocketAddress> bindAddressSupplier) {
		return super.bindAddress(bindAddressSupplier);
	}

	@Override
	public final Mono<? extends Connection> connect() {
		return super.connect();
	}

	@Override
	public final Connection connectNow() {
		return super.connectNow();
	}

	@Override
	public final Connection connectNow(Duration timeout) {
		return super.connectNow(timeout);
	}

	@Override
	public final UdpClient doOnConnect(Consumer<? super UdpClientConfig> doOnConnect) {
		return super.doOnConnect(doOnConnect);
	}

	@Override
	public final UdpClient doOnConnected(Consumer<? super Connection> doOnConnected) {
		return super.doOnConnected(doOnConnected);
	}

	@Override
	public final UdpClient doOnDisconnected(Consumer<? super Connection> doOnDisconnected) {
		return super.doOnDisconnected(doOnDisconnected);
	}

	/**
	 * Attach an IO handler to react on connected client
	 *
	 * @param handler an IO handler that can dispose underlying connection when {@link
	 * Publisher} terminates.
	 *
	 * @return a new {@link UdpClient} reference
	 */
	public final UdpClient handle(BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return doOnConnected(new OnConnectedHandle(handler));
	}

	@Override
	public final UdpClient host(String host) {
		return super.host(host);
	}

	@Override
	public final UdpClient metrics(boolean enable) {
		return super.metrics(enable);
	}

	@Override
	public final UdpClient metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder) {
		return super.metrics(enable, recorder);
	}

	@Override
	public final UdpClient observe(ConnectionObserver observer) {
		return super.observe(observer);
	}

	@Override
	public final <O> UdpClient option(ChannelOption<O> key, @Nullable O value) {
		return super.option(key, value);
	}

	@Override
	public final UdpClient port(int port) {
		return super.port(port);
	}

	@Override
	public final UdpClient remoteAddress(Supplier<? extends SocketAddress> remoteAddressSupplier) {
		return super.remoteAddress(remoteAddressSupplier);
	}

	@Override
	public final UdpClient runOn(EventLoopGroup eventLoopGroup) {
		return super.runOn(eventLoopGroup);
	}

	@Override
	public final UdpClient runOn(LoopResources channelResources) {
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
	public final UdpClient runOn(LoopResources loopResources, boolean preferNative) {
		Objects.requireNonNull(loopResources, "loopResources");
		UdpClient dup = super.runOn(loopResources, preferNative);
		dup.configuration().family = null;
		return dup;
	}

	/**
	 * Run IO loops on a supplied {@link EventLoopGroup} from the {@link LoopResources} container.
	 *
	 * @param loopResources a new loop resources
	 * @param family a specific {@link InternetProtocolFamily} to run with
	 * @return a new {@link UdpClient} reference
	 */
	public final UdpClient runOn(LoopResources loopResources, InternetProtocolFamily family) {
		Objects.requireNonNull(loopResources, "loopResources");
		Objects.requireNonNull(family, "family");
		UdpClient dup = super.runOn(loopResources, false);
		dup.configuration().family = family;
		return dup;
	}

	@Override
	public final UdpClient wiretap(boolean enable) {
		return super.wiretap(enable);
	}

	@Override
	public final UdpClient wiretap(String category) {
		return super.wiretap(category);
	}

	@Override
	public final UdpClient wiretap(String category, LogLevel level) {
		return super.wiretap(category, level);
	}

	static final Logger log = Loggers.getLogger(UdpClient.class);

	static final class OnConnectedHandle implements Consumer<Connection> {

		final BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler;

		OnConnectedHandle(BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler) {
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
