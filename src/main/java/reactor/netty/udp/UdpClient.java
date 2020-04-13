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

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.InternetProtocolFamily;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.ClientTransport;
import reactor.util.Logger;
import reactor.util.Loggers;

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
	public final UdpClient metrics(boolean enable) {
		return super.metrics(enable);
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
