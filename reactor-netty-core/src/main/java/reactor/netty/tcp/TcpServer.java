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
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.ServerTransport;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty.ReactorNetty.format;

/**
 * A TcpServer allows to build in a safe immutable way a TCP server that is materialized
 * and connecting when {@link #bind()} is ultimately called.
 * <p>
 * <p> Example:
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
 * @author Violeta Georgieva
 */
public abstract class TcpServer extends ServerTransport<TcpServer, TcpServerConfig> {

	/**
	 * Prepare a {@link TcpServer}
	 *
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create() {
		return TcpServerBind.INSTANCE;
	}

	@Override
	public TcpServer bindAddress(Supplier<? extends SocketAddress> bindAddressSupplier) {
		return super.bindAddress(bindAddressSupplier);
	}

	@Override
	public TcpServer channelGroup(ChannelGroup channelGroup) {
		return super.channelGroup(channelGroup);
	}

	@Override
	public TcpServer doOnBind(Consumer<? super TcpServerConfig> doOnBind) {
		return super.doOnBind(doOnBind);
	}

	@Override
	public TcpServer doOnBound(Consumer<? super DisposableServer> doOnBound) {
		return super.doOnBound(doOnBound);
	}

	@Override
	public TcpServer doOnConnection(Consumer<? super Connection> doOnConnection) {
		return super.doOnConnection(doOnConnection);
	}

	@Override
	public TcpServer doOnUnbound(Consumer<? super DisposableServer> doOnUnbound) {
		return super.doOnUnbound(doOnUnbound);
	}

	/**
	 * Attaches an I/O handler to react on a connected client
	 *
	 * @param handler an I/O handler that can dispose underlying connection when
	 * {@link Publisher} terminates.
	 *
	 * @return a new {@link TcpServer}
	 */
	public TcpServer handle(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return doOnConnection(new OnConnectionHandle(handler));
	}

	@Override
	public TcpServer host(String host) {
		return super.host(host);
	}

	@Override
	public TcpServer metrics(boolean enable) {
		return super.metrics(enable);
	}

	@Override
	public TcpServer metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder) {
		return super.metrics(enable, recorder);
	}

	/**
	 * Removes any previously applied SSL configuration customization
	 *
	 * @return a new {@link TcpServer}
	 */
	public TcpServer noSSL() {
		if (configuration().isSecure()) {
			TcpServer dup = duplicate();
			dup.configuration().sslProvider = null;
			return dup;
		}
		return this;
	}

	@Override
	public TcpServer port(int port) {
		return super.port(port);
	}

	@Override
	public TcpServer runOn(EventLoopGroup eventLoopGroup) {
		return super.runOn(eventLoopGroup);
	}

	@Override
	public TcpServer runOn(LoopResources channelResources) {
		return super.runOn(channelResources);
	}

	@Override
	public TcpServer runOn(LoopResources loopResources, boolean preferNative) {
		return super.runOn(loopResources, preferNative);
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
	 * @return a new {@link TcpServer}
	 */
	public TcpServer secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		Objects.requireNonNull(sslProviderBuilder, "sslProviderBuilder");
		TcpServer dup = duplicate();
		SslProvider.SslContextSpec builder = SslProvider.builder();
		sslProviderBuilder.accept(builder);
		dup.configuration().sslProvider = ((SslProvider.Builder) builder).build();
		return dup;
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
	public TcpServer secure(SslProvider sslProvider) {
		Objects.requireNonNull(sslProvider, "sslProvider");
		TcpServer dup = duplicate();
		dup.configuration().sslProvider = sslProvider;
		return dup;
	}

	/**
	 * Based on the actual configuration, returns a {@link Mono} that triggers:
	 * <ul>
	 *     <li>an initialization of the event loop groups</li>
	 *     <li>loads the necessary native libraries for the transport</li>
	 *     <li>loads the necessary native libraries for the security if there is such</li>
	 * </ul>
	 * By default, when method is not used, the {@code bind operation} absorbs the extra time needed to load resources.
	 *
	 * @return a {@link Mono} representing the completion of the warmup
	 * @since 1.0.3
	 */
	@Override
	public Mono<Void> warmup() {
		return Mono.when(
				super.warmup(),
				Mono.fromRunnable(() -> {
					SslProvider provider = configuration().sslProvider();
					if (provider != null && !(provider.getSslContext() instanceof JdkSslContext)) {
						OpenSsl.version();
					}
				}));
	}

	@Override
	public TcpServer wiretap(boolean enable) {
		return super.wiretap(enable);
	}

	@Override
	public TcpServer wiretap(String category) {
		return super.wiretap(category);
	}

	@Override
	public TcpServer wiretap(String category, LogLevel level) {
		return super.wiretap(category, level);
	}

	static final Logger log = Loggers.getLogger(TcpServer.class);

	static final class OnConnectionHandle implements Consumer<Connection> {

		final BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler;

		OnConnectionHandle(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
			this.handler = handler;
		}

		@Override
		public void accept(Connection c) {
			if (log.isDebugEnabled()) {
				log.debug(format(c.channel(), "Handler is being applied: {}"), handler);
			}
			Mono.fromDirect(handler.apply(c.inbound(), c.outbound()))
			    .subscribe(c.disposeSubscriber());
		}
	}
}
