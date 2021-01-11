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
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.ClientTransport;
import reactor.netty.transport.ProxyProvider;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static java.util.Objects.requireNonNull;
import static reactor.netty.ReactorNetty.format;

/**
 * A TcpClient allows to build in a safe immutable way a TCP client that
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
		requireNonNull(provider, "provider");
		return new TcpClientConnect(provider);
	}

	/**
	 * Prepare a non pooled {@link TcpClient}
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient newConnection() {
		return create(ConnectionProvider.newConnection());
	}

	/**
	 * The address to which this client should connect for each subscribe.
	 *
	 * @param connectAddressSupplier A supplier of the address to connect to.
	 *
	 * @return a new {@link TcpClient}
	 * @deprecated Use {@link #remoteAddress(Supplier)}. This method will be removed in version 1.1.0.
	 */
	@Deprecated
	public final TcpClient addressSupplier(Supplier<? extends SocketAddress> connectAddressSupplier) {
		return remoteAddress(connectAddressSupplier);
	}

	@Override
	public <A> TcpClient attr(AttributeKey<A> key, @Nullable A value) {
		return super.attr(key, value);
	}

	@Override
	public TcpClient bindAddress(Supplier<? extends SocketAddress> bindAddressSupplier) {
		return super.bindAddress(bindAddressSupplier);
	}

	/**
	 * Apply a {@link Bootstrap} mapping function to update {@link TcpClient} configuration and
	 * return an enriched {@link TcpClient} to use.
	 * <p>
	 * <strong>Note:</strong>
	 * There isn't only one method that replaces this deprecated method.
	 * The configuration that can be done with this deprecated method,
	 * can also be done with the other methods exposed by {@link TcpClient}.
	 * </p>
	 * <p>Examples:</p>
	 * <p>Configuration via the deprecated '.bootstrap(...)' method</p>
	 * <pre>
	 * {@code
	 * TcpClient.bootstrap(b ->
	 *     b.attr(...) // configures the channel attributes
	 *      .group(...) // configures the event loop group
	 *      .handler(...) // configures the channel handler
	 *      .localAddress(...) // configures the bind (local) address
	 *      .option(...) // configures the channel options
	 *      .remoteAddress(...) // configures the remote address
	 *      .resolver(...)) // configures the host names resolver
	 * }
	 * </pre>
	 *
	 * <p>Configuration via the other methods exposed by {@link TcpClient}</p>
	 * <pre>
	 * {@code
	 * TcpClient.attr(...) // configures the channel attributes
	 *          .runOn(...) // configures the event loop group
	 *          .doOnChannelInit(...) // configures the channel handler
	 *          .bindAddress(...) // configures the bind (local) address
	 *          .option(...) // configures the channel options
	 *          .remoteAddress(...) // configures the remote address
	 *          .resolver(...) // configures the host names resolver
	 * }
	 * </pre>
	 *
	 * <p>Wire logging in plain text</p>
	 * <pre>
	 * {@code
	 * TcpClient.wiretap("logger", LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL)
	 * }
	 * </pre>
	 *
	 * @param bootstrapMapper A {@link Bootstrap} mapping function to update {@link TcpClient} configuration and
	 * return an enriched {@link TcpClient} to use.
	 * @return a new {@link TcpClient}
	 * @deprecated as of 0.9.10. Use the other methods exposed by {@link TcpClient} to achieve the same configurations.
	 * The method will be removed in version 1.1.0.
	 */
	@Deprecated
	@SuppressWarnings("ReturnValueIgnored")
	public final TcpClient bootstrap(Function<? super Bootstrap, ? extends Bootstrap> bootstrapMapper) {
		requireNonNull(bootstrapMapper, "bootstrapMapper");
		TcpClientBootstrap tcpClientBootstrap = new TcpClientBootstrap(this);
		// ReturnValueIgnored is deliberate
		bootstrapMapper.apply(tcpClientBootstrap);
		return tcpClientBootstrap.tcpClient;
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
	 * Attach an IO handler to react on connected client
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
	 * Remove any previously applied SSL configuration customization
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
	 * assigned to
	 * with a default value of {@code 10} seconds handshake timeout unless
	 * the environment property {@code reactor.netty.tcp.sslHandshakeTimeout} is set.
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
	 * will produce the {@link SslContext} to be passed to with a default value of
	 * {@code 10} seconds handshake timeout unless the environment property {@code
	 * reactor.netty.tcp.sslHandshakeTimeout} is set.
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
