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

package reactor.ipc.netty.tcp;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.bootstrap.Bootstrap;
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
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.resources.PoolResources;
import reactor.util.Logger;
import reactor.util.Loggers;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
 *   TcpClient.create()
 *            .doOnConnect(connectMetrics)
 *            .doOnConnected(connectedMetrics)
 *            .doOnDisconnected(disconnectedMetrics)
 *            .host("127.0.0.1")
 *            .port(1234)
 *            .secure()
 *            .send(ByteBufFlux.fromByteArrays(pub))
 *            .block()
 * }
 *
 *
 * @author Stephane Maldini
 */
public abstract class TcpClient {

	/**
	 * The default port for reactor-netty servers. Defaults to 12012 but can be tuned via
	 * the {@code PORT} <b>environment variable</b>.
	 */
	static final int DEFAULT_PORT =
			System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) :
					12012;

	/**
	 * Prepare a pooled {@link TcpClient}
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient create() {
		return new TcpClientAcquire(TcpResources.get());
	}

	/**
	 * Prepare a pooled {@link TcpClient}
	 *
	 * @param poolResources a set of {@link PoolResources} to hold and manage pooled
	 * connections
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient create(PoolResources poolResources) {
		return new TcpClientAcquire(poolResources);
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
		Objects.requireNonNull(connectAddressSupplier, "connectAddressSupplier");
		return bootstrap(b -> b.remoteAddress(connectAddressSupplier.get()));
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
	 * If updateConfiguration phase fails, a {@link Mono#error(Throwable)} will be returned;
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	public final Mono<? extends Connection> connect() {
		Bootstrap b;
		try {
			b = configure();
		}
		catch (Throwable t) {
			Exceptions.throwIfFatal(t);
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
		return Objects.requireNonNull(connect().block(timeout), "aborted");
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
		return new TcpClientLifecycle(this, doOnConnect, null, null);
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
		return new TcpClientLifecycle(this, null, doOnConnected, null);
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
		return new TcpClientLifecycle(this, null, null, doOnDisconnected);
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
		return new TcpClientLifecycle(this, doOnConnect, doOnConnected, doOnDisconnected);
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
		return bootstrap(b -> b.remoteAddress(host, getPort(b)));
	}

	/**
	 * Attach an IO handler to react on connected client
	 *
	 * @param handler an IO handler that can dispose underlying connection when {@link
	 * Publisher} terminates.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient handler(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return doOnConnected(c -> {
			if (log.isDebugEnabled()) {
				log.debug("{} handler is being applied: {}", c.channel(), handler);
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
		return sslContext() != null;
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
	 * Set a {@link ChannelOption} value for low level connection settings like SO_TIMEOUT
	 * or SO_KEEPALIVE. This will apply to each new channel from remote peer.
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
		return bootstrap(b -> b.remoteAddress(getHost(b), port));
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
	 * {@link LoopResources} container. Will prefer native (epoll) implementation if
	 * available unless the environment property {@literal reactor.ipc.netty.epoll} is set
	 * to {@literal false}.
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
	 * @param preferNative Should the connector prefer native (epoll) if available.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient runOn(LoopResources channelResources, boolean preferNative) {
		return new TcpClientRunOn(this, channelResources, preferNative);
	}

	/**
	 * Enable default sslContext support. The default {@link SslContext} will be
	 * assigned to
	 * with a default value of {@literal 10} seconds handshake timeout unless
	 * the environment property {@literal reactor.ipc.netty.sslHandshakeTimeout} is set.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient secure() {
		return secure(sslProviderBuilder -> sslProviderBuilder.sslContext(TcpClientSecure.DEFAULT_SSL_CONTEXT));
	}

	/**
	 * Apply an SSL configuration customization via the passed builder. The builder
	 * will produce the {@link SslContext} to be passed to with a default value of
	 * {@literal 10} seconds handshake timeout unless the environment property {@literal
	 * reactor.ipc.netty.sslHandshakeTimeout} is set.
	 *
	 * @param sslProviderBuilder builder callback for further customization of SslContext.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		return new TcpClientSecure(this, sslProviderBuilder);
	}

	/**
	 * Apply an SSL configuration customization via the passed {@link SslContext}.
	 * with a default value of {@literal 10} seconds handshake timeout unless
	 * the environment property {@literal reactor.ipc.netty.sslHandshakeTimeout} is set.
	 *
	 * @param sslContext The context to set when configuring SSL
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient secure(SslContext sslContext) {
		return secure(sslProviderBuilder -> sslProviderBuilder.sslContext(sslContext));
	}

	/**
	 * Return the current {@link SslContext} if that {@link TcpClient} secured via SSL
	 * transport or null
	 *
	 * @return he current {@link SslContext} if that {@link TcpClient} secured via SSL
	 * transport or null
	 */
	@Nullable
	public SslContext sslContext(){
		return null;
	}

	/**
	 * Apply a wire logger configuration using {@link TcpServer} category
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpClient wiretap() {
		return bootstrap(b -> BootstrapHandlers.updateLogSupport(b, LOGGING_HANDLER));
	}

	/**
	 * Apply a wire logger configuration
	 *
	 * @param category the logger category
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpClient wiretap(String category) {
		return wiretap(category, LogLevel.DEBUG);
	}

	/**
	 * Apply a wire logger configuration
	 *
	 * @param category the logger category
	 * @param level the logger level
	 *
	 * @return a new {@link TcpServer}
	 */
	public final TcpClient wiretap(String category, LogLevel level) {
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(level, "level");
		return bootstrap(b -> BootstrapHandlers.updateLogSupport(b,
				new LoggingHandler(category, level)));
	}

	static final Bootstrap DEFAULT_BOOTSTRAP =
			new Bootstrap().option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
					.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
					.option(ChannelOption.AUTO_READ, false)
					.option(ChannelOption.SO_RCVBUF, 1024 * 1024)
					.option(ChannelOption.SO_SNDBUF, 1024 * 1024)
					.remoteAddress(NetUtil.LOCALHOST, DEFAULT_PORT);

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(TcpClient.class);
	static final Logger         log             = Loggers.getLogger(TcpClient.class);

	static String getHost(Bootstrap b) {
		if (b.config()
				.remoteAddress() instanceof InetSocketAddress) {
			return ((InetSocketAddress) b.config()
					.remoteAddress()).getHostString();
		}
		return NetUtil.LOCALHOST.getHostAddress();
	}

	static int getPort(Bootstrap b) {
		if (b.config()
				.remoteAddress() instanceof InetSocketAddress) {
			return ((InetSocketAddress) b.config()
					.remoteAddress()).getPort();
		}
		return DEFAULT_PORT;
	}
}
