/*
 * Copyright (c) 2011-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.tcp;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty5.channel.ChannelOption;
import io.netty5.channel.EventLoopGroup;
import io.netty5.handler.logging.LogLevel;
import io.netty5.handler.ssl.JdkSslContext;
import io.netty5.handler.ssl.OpenSsl;
import io.netty5.handler.ssl.SslContext;
import io.netty5.resolver.AddressResolverGroup;
import io.netty5.util.AttributeKey;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.NettyInbound;
import reactor.netty5.NettyOutbound;
import reactor.netty5.channel.ChannelMetricsRecorder;
import reactor.netty5.resources.ConnectionProvider;
import reactor.netty5.resources.LoopResources;
import reactor.netty5.transport.ClientTransport;
import reactor.netty5.transport.ProxyProvider;
import reactor.util.Logger;
import reactor.util.Loggers;

import static java.util.Objects.requireNonNull;
import static reactor.netty5.ReactorNetty.format;

/**
 * A TcpClient allows building in a safe immutable way a TCP client that
 * is materialized and connecting when {@link #connect()} is ultimately called.
 * <p>
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
 * </pre>
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public abstract class TcpClient extends ClientTransport<TcpClient, TcpClientConfig> {

	/**
	 * Prepare a pooled {@link TcpClient}.
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient create() {
		return create(TcpResources.get());
	}


	/**
	 * Prepare a {@link TcpClient}.
	 *
	 * @param provider a {@link ConnectionProvider} to acquire connections
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient create(ConnectionProvider provider) {
		requireNonNull(provider, "provider");
		return new TcpClientConnect(provider);
	}

	/**
	 * Prepare a non pooled {@link TcpClient}.
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient newConnection() {
		return create(ConnectionProvider.newConnection());
	}

	@Override
	public <A> TcpClient attr(AttributeKey<A> key, @Nullable A value) {
		return super.attr(key, value);
	}

	@Override
	public TcpClient bindAddress(Supplier<? extends SocketAddress> bindAddressSupplier) {
		return super.bindAddress(bindAddressSupplier);
	}

	@Override
	public Mono<? extends Connection> connect() {
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
	public TcpClient doOnConnect(Consumer<? super TcpClientConfig> doOnConnect) {
		return super.doOnConnect(doOnConnect);
	}

	@Override
	public TcpClient doOnConnected(Consumer<? super Connection> doOnConnected) {
		return super.doOnConnected(doOnConnected);
	}

	@Override
	public TcpClient doOnDisconnected(Consumer<? super Connection> doOnDisconnected) {
		return super.doOnDisconnected(doOnDisconnected);
	}

	/**
	 * Attach an IO handler to react on connected client.
	 *
	 * @param handler an IO handler that can dispose underlying connection when {@link
	 * Publisher} terminates.
	 *
	 * @return a new {@link TcpClient}
	 */
	public TcpClient handle(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		requireNonNull(handler, "handler");
		return doOnConnected(new OnConnectedHandle(handler));
	}

	@Override
	public TcpClient host(String host) {
		return super.host(host);
	}

	@Override
	public TcpClient metrics(boolean enable) {
		return super.metrics(enable);
	}

	@Override
	public TcpClient metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder) {
		return super.metrics(enable, recorder);
	}

	@Override
	public TcpClient noProxy() {
		return super.noProxy();
	}

	/**
	 * Remove any previously applied SSL configuration customization.
	 *
	 * @return a new {@link TcpClient}
	 */
	public TcpClient noSSL() {
		if (configuration().isSecure()) {
			TcpClient dup = duplicate();
			dup.configuration().sslProvider = null;
			return dup;
		}
		return this;
	}

	@Override
	public TcpClient observe(ConnectionObserver observer) {
		return super.observe(observer);
	}

	@Override
	public <O> TcpClient option(ChannelOption<O> key, @Nullable O value) {
		return super.option(key, value);
	}

	/**
	 * The port to which this client should connect.
	 * If a port is not specified, the default port {@code 12012} is used.
	 * <p><strong>Note:</strong> The port can be specified also with {@code PORT} environment variable.
	 *
	 * @param port the port to connect to
	 * @return a new {@link TcpClient}
	 */
	@Override
	public TcpClient port(int port) {
		return super.port(port);
	}

	@Override
	public TcpClient proxy(Consumer<? super ProxyProvider.TypeSpec> proxyOptions) {
		return super.proxy(proxyOptions);
	}

	@Override
	public TcpClient remoteAddress(Supplier<? extends SocketAddress> remoteAddressSupplier) {
		return super.remoteAddress(remoteAddressSupplier);
	}

	@Override
	public TcpClient resolver(AddressResolverGroup<?> resolver) {
		return super.resolver(resolver);
	}

	@Override
	public TcpClient runOn(EventLoopGroup eventLoopGroup) {
		return super.runOn(eventLoopGroup);
	}

	@Override
	public TcpClient runOn(LoopResources channelResources) {
		return super.runOn(channelResources);
	}

	@Override
	public TcpClient runOn(LoopResources loopResources, boolean preferNative) {
		return super.runOn(loopResources, preferNative);
	}

	/**
	 * Enable default sslContext support. The default {@link SslContext} will be
	 * assigned to with a default value of:
	 * <ul>
	 *     <li>{@code 10} seconds handshake timeout unless the environment property
	 *     {@code reactor.netty5.tcp.sslHandshakeTimeout} is set.</li>
	 *     <li>{@code 3} seconds close_notify flush timeout</li>
	 *     <li>{@code 0} second close_notify read timeout</li>
	 * </ul>
	 *
	 * @return a new {@link TcpClient}
	 */
	public TcpClient secure() {
		TcpClient dup = duplicate();
		dup.configuration().sslProvider = SslProvider.defaultClientProvider();
		return dup;
	}

	/**
	 * Apply an SSL configuration customization via the passed builder. The builder
	 * will produce the {@link SslContext} to be passed to with a default value of:
	 * <ul>
	 *     <li>{@code 10} seconds handshake timeout unless the environment property
	 *     {@code reactor.netty5.tcp.sslHandshakeTimeout} is set.</li>
	 *     <li>{@code 3} seconds close_notify flush timeout</li>
	 *     <li>{@code 0} second close_notify read timeout</li>
	 * </ul>
	 *
	 * @param sslProviderBuilder builder callback for further customization of SslContext.
	 *
	 * @return a new {@link TcpClient}
	 */
	public TcpClient secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		requireNonNull(sslProviderBuilder, "sslProviderBuilder");
		TcpClient dup = duplicate();
		SslProvider.SslContextSpec builder = SslProvider.builder();
		sslProviderBuilder.accept(builder);
		dup.configuration().sslProvider = ((SslProvider.Builder) builder).build();
		return dup;
	}

	/**
	 * Apply an SSL configuration via the passed {@link SslProvider}.
	 *
	 * @param sslProvider The provider to set when configuring SSL
	 *
	 * @return a new {@link TcpClient}
	 */
	public TcpClient secure(SslProvider sslProvider) {
		requireNonNull(sslProvider, "sslProvider");
		TcpClient dup = duplicate();
		dup.configuration().sslProvider = sslProvider;
		return dup;
	}

	/**
	 * Based on the actual configuration, returns a {@link Mono} that triggers:
	 * <ul>
	 *     <li>an initialization of the event loop group</li>
	 *     <li>an initialization of the host name resolver</li>
	 *     <li>loads the necessary native libraries for the transport</li>
	 *     <li>loads the necessary native libraries for the security if there is such</li>
	 * </ul>
	 * By default, when method is not used, the {@code connect operation} absorbs the extra time needed to load resources.
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
	public TcpClient wiretap(boolean enable) {
		return super.wiretap(enable);
	}

	@Override
	public TcpClient wiretap(String category) {
		return super.wiretap(category);
	}

	@Override
	public TcpClient wiretap(String category, LogLevel level) {
		return super.wiretap(category, level);
	}

	static final Logger log = Loggers.getLogger(TcpClient.class);

	static final class OnConnectedHandle implements Consumer<Connection> {

		final BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler;

		OnConnectedHandle(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
			this.handler = handler;
		}

		@Override
		public void accept(Connection c) {
			if (log.isDebugEnabled()) {
				log.debug(format(c.channel(), "Handler is being applied: {}"), handler);
			}

			Mono.fromDirect(handler.apply((NettyInbound) c, (NettyOutbound) c))
			    .subscribe(c.disposeSubscriber());
		}
	}
}
