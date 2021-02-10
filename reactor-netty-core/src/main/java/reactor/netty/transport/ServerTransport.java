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
package reactor.netty.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.codec.DecoderException;
import io.netty.util.AttributeKey;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import static reactor.netty.ReactorNetty.format;

/**
 * A generic server {@link Transport} that will {@link #bind()} to a local address and provide a {@link DisposableServer}
 *
 * @param <T> ServerTransport implementation
 * @param <CONF> Server Configuration implementation
 * @author Stephane Maldini
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public abstract class ServerTransport<T extends ServerTransport<T, CONF>,
		CONF extends ServerTransportConfig<CONF>>
		extends Transport<T, CONF> {

	/**
	 * Binds the {@link ServerTransport} and returns a {@link Mono} of {@link DisposableServer}. If
	 * {@link Mono} is cancelled, the underlying binding will be aborted. Once the {@link
	 * DisposableServer} has been emitted and is not necessary anymore, disposing the main server
	 * loop must be done by the user via {@link DisposableServer#dispose()}.
	 *
	 * @return a {@link Mono} of {@link DisposableServer}
	 */
	public Mono<? extends DisposableServer> bind() {
		CONF config = configuration();
		Objects.requireNonNull(config.bindAddress(), "bindAddress");

		Mono<? extends DisposableServer> mono =  Mono.create(sink -> {
			SocketAddress local = Objects.requireNonNull(config.bindAddress().get(), "Bind Address supplier returned null");
			if (local instanceof InetSocketAddress) {
				InetSocketAddress localInet = (InetSocketAddress) local;

				if (localInet.isUnresolved()) {
					local = AddressUtils.createResolved(localInet.getHostName(), localInet.getPort());
				}
			}

			boolean isDomainSocket = false;
			DisposableBind disposableServer;
			if (local instanceof DomainSocketAddress) {
				isDomainSocket = true;
				disposableServer = new UdsDisposableBind(sink, config, local);
			}
			else {
				disposableServer = new InetDisposableBind(sink, config, local);
			}

			ConnectionObserver childObs =
					new ChildObserver(config.defaultChildObserver().then(config.childObserver()));
			Acceptor acceptor = new Acceptor(config.childEventLoopGroup(), config.channelInitializer(childObs, null, true),
					config.childOptions, config.childAttrs, isDomainSocket);
			TransportConnector.bind(config, new AcceptorInitializer(acceptor), local, isDomainSocket)
			                  .subscribe(disposableServer);
		});

		if (config.doOnBind() != null) {
			mono = mono.doOnSubscribe(s -> config.doOnBind().accept(config));
		}
		return mono;
	}

	/**
	 * Starts the server in a blocking fashion, and waits for it to finish initializing
	 * or the startup timeout expires (the startup timeout is {@code 45} seconds). The
	 * returned {@link DisposableServer} offers simple server API, including to {@link
	 * DisposableServer#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @return a {@link Mono} of {@link Connection}
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
	 * @param timeout max startup timeout (resolution: ns)
	 * @return a {@link DisposableServer}
	 */
	public final DisposableServer bindNow(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		try {
			return Objects.requireNonNull(bind().block(timeout), "aborted");
		}
		catch (IllegalStateException e) {
			if (e.getMessage()
			     .contains("blocking read")) {
				throw new IllegalStateException(getClass().getSimpleName() + " couldn't be started within " + timeout.toMillis() + "ms");
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
		DisposableServer facade = Objects.requireNonNull(bindNow(), "facade");

		if (onStart != null) {
			onStart.accept(facade);
		}

		Runtime.getRuntime()
		       .addShutdownHook(new Thread(() -> facade.disposeNow(timeout)));

		facade.onDispose()
		      .block();
	}

	/**
	 * Injects default attribute to the future child {@link Channel} connections. It
	 * will be available via {@link Channel#attr(AttributeKey)}.
	 * If the {@code value} is {@code null}, the attribute of the specified {@code key}
	 * is removed.
	 *
	 * @param key the attribute key
	 * @param value the attribute value - null to remove a key
	 * @param <A> the attribute type
	 * @return a new {@link ServerTransport} reference
	 * @see ServerBootstrap#childAttr(AttributeKey, Object)
	 */
	public <A> T childAttr(AttributeKey<A> key, @Nullable A value) {
		Objects.requireNonNull(key, "key");
		T dup = duplicate();
		dup.configuration().childAttrs = TransportConfig.updateMap(configuration().childAttrs, key, value);
		return dup;
	}

	/**
	 * Set or add the given {@link ConnectionObserver} for each remote connection
	 *
	 * @param observer the {@link ConnectionObserver} addition
	 * @return a new {@link ServerTransport} reference
	 */
	public T childObserve(ConnectionObserver observer) {
		Objects.requireNonNull(observer, "observer");
		T dup = duplicate();
		ConnectionObserver current = configuration().childObserver;
		dup.configuration().childObserver = current == null ? observer : current.then(observer);
		return dup;
	}

	/**
	 * Injects default options to the future child {@link Channel} connections. It
	 * will be available via {@link Channel#config()}.
	 * If the {@code value} is {@code null}, the attribute of the specified {@code key}
	 * is removed.
	 * Note: Setting {@link ChannelOption#AUTO_READ} option will be ignored. It is configured to be {@code false}.
	 *
	 * @param key the option key
	 * @param value the option value - null to remove a key
	 * @param <A> the option type
	 * @return a new {@link ServerTransport} reference
	 * @see ServerBootstrap#childOption(ChannelOption, Object)
	 */
	@SuppressWarnings("ReferenceEquality")
	public <A> T childOption(ChannelOption<A> key, @Nullable A value) {
		Objects.requireNonNull(key, "key");
		// Reference comparison is deliberate
		if (ChannelOption.AUTO_READ == key) {
			if (value instanceof Boolean && Boolean.TRUE.equals(value)) {
				log.error("ChannelOption.AUTO_READ is configured to be [false], it cannot be set to [true]");
			}
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		dup.configuration().childOptions = TransportConfig.updateMap(configuration().childOptions, key, value);
		return dup;
	}

	/**
	 * Set or add a callback called when {@link ServerTransport} is about to start listening for incoming traffic.
	 *
	 * @param doOnBind a consumer observing connected events
	 * @return a new {@link ServerTransport} reference
	 */
	public T doOnBind(Consumer<? super CONF> doOnBind) {
		Objects.requireNonNull(doOnBind, "doOnBind");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<CONF> current = (Consumer<CONF>) configuration().doOnBind;
		dup.configuration().doOnBind = current == null ? doOnBind : current.andThen(doOnBind);
		return dup;
	}

	/**
	 * Set or add a callback called after {@link DisposableServer} has been started.
	 *
	 * @param doOnBound a consumer observing connected events
	 * @return a new {@link ServerTransport} reference
	 */
	public T doOnBound(Consumer<? super DisposableServer> doOnBound) {
		Objects.requireNonNull(doOnBound, "doOnBound");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<DisposableServer> current = (Consumer<DisposableServer>) configuration().doOnBound;
		dup.configuration().doOnBound = current == null ? doOnBound : current.andThen(doOnBound);
		return dup;
	}

	/**
	 * Set or add a callback called on new remote {@link Connection}.
	 *
	 * @param doOnConnection a consumer observing remote connections
	 * @return a new {@link ServerTransport} reference
	 */
	public T doOnConnection(Consumer<? super Connection> doOnConnection) {
		Objects.requireNonNull(doOnConnection, "doOnConnected");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<Connection> current = (Consumer<Connection>) configuration().doOnConnection;
		dup.configuration().doOnConnection = current == null ? doOnConnection : current.andThen(doOnConnection);
		return dup;
	}

	/**
	 * Set or add a callback called after {@link DisposableServer} has been shutdown.
	 *
	 * @param doOnUnbound a consumer observing unbound events
	 * @return a new {@link ServerTransport} reference
	 */
	public T doOnUnbound(Consumer<? super DisposableServer> doOnUnbound) {
		Objects.requireNonNull(doOnUnbound, "doOnUnbound");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<DisposableServer> current = (Consumer<DisposableServer>) configuration().doOnUnbound;
		dup.configuration().doOnUnbound = current == null ? doOnUnbound : current.andThen(doOnUnbound);
		return dup;
	}

	/**
	 * The host to which this server should bind.
	 *
	 * @param host the host to bind to.
	 * @return a new {@link ServerTransport} reference
	 */
	public T host(String host) {
		Objects.requireNonNull(host, "host");
		return bindAddress(() -> AddressUtils.updateHost(configuration().bindAddress(), host));
	}

	/**
	 * The port to which this server should bind.
	 *
	 * @param port The port to bind to.
	 * @return a new {@link ServerTransport} reference
	 */
	public T port(int port) {
		return bindAddress(() -> AddressUtils.updatePort(configuration().bindAddress(), port));
	}

	/**
	 * Based on the actual configuration, returns a {@link Mono} that triggers:
	 * <ul>
	 *     <li>an initialization of the event loop groups</li>
	 *     <li>loads the necessary native libraries for the transport</li>
	 * </ul>
	 * By default, when method is not used, the {@code bind operation} absorbs the extra time needed to load resources.
	 *
	 * @return a {@link Mono} representing the completion of the warmup
	 * @since 1.0.3
	 */
	public Mono<Void> warmup() {
		return Mono.fromRunnable(() -> {
			// event loop group for the server
			configuration().childEventLoopGroup();

			// event loop group for the server selector
			configuration().eventLoopGroup();
		});
	}

	static final Logger log = Loggers.getLogger(ServerTransport.class);

	static class Acceptor extends ChannelInboundHandlerAdapter {

		final EventLoopGroup childGroup;
		final ChannelHandler childHandler;
		final Map<ChannelOption<?>, ?> childOptions;
		final Map<AttributeKey<?>, ?> childAttrs;
		final boolean isDomainSocket;

		Runnable enableAutoReadTask;

		Acceptor(EventLoopGroup childGroup, ChannelHandler childHandler,
				Map<ChannelOption<?>, ?> childOptions, Map<AttributeKey<?>, ?> childAttrs,
				boolean isDomainSocket) {
			this.childGroup = childGroup;
			this.childHandler = childHandler;
			this.childOptions = childOptions;
			this.childAttrs = childAttrs;
			this.isDomainSocket = isDomainSocket;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			final Channel child = (Channel) msg;

			child.pipeline().addLast(childHandler);

			TransportConnector.setChannelOptions(child, childOptions, isDomainSocket);
			TransportConnector.setAttributes(child, childAttrs);

			try {
				childGroup.register(child).addListener((ChannelFutureListener) future -> {
					if (!future.isSuccess()) {
						forceClose(child, future.cause());
					}
				});
			}
			catch (Throwable t) {
				forceClose(child, t);
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			ChannelConfig config = ctx.channel().config();
			if (config.isAutoRead()) {
				// stop accept new connections for 1 second to allow the channel to recover
				// See https://github.com/netty/netty/issues/1328
				config.setAutoRead(false);
				ctx.channel()
				   .eventLoop()
				   .schedule(enableAutoReadTask, 1, TimeUnit.SECONDS)
				   .addListener(future -> {
				       if (!future.isSuccess()) {
				           log.debug(format(ctx.channel(), "Cannot enable auto-read"), future.cause());
				       }
				   });
			}
			// still let the exceptionCaught event flow through the pipeline to give the user
			// a chance to do something with it
			ctx.fireExceptionCaught(cause);
		}

		void enableAutoReadTask(Channel channel) {

			// Task which is scheduled to re-enable auto-read.
			// It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
			// not be able to load the class because of the file limit it already reached.
			//
			// See https://github.com/netty/netty/issues/1328
			enableAutoReadTask = () -> channel.config().setAutoRead(true);
		}

		static void forceClose(Channel child, Throwable t) {
			child.unsafe().closeForcibly();
			log.warn(format(child, "Failed to register an accepted channel: {}"), child, t);
		}
	}

	static final class AcceptorInitializer extends ChannelInitializer<Channel> {

		final Acceptor acceptor;

		AcceptorInitializer(Acceptor acceptor) {
			this.acceptor = acceptor;
		}

		@Override
		public void initChannel(final Channel ch) {
			ch.eventLoop().execute(() -> ch.pipeline().addLast(acceptor));
		}
	}

	static final class ChildObserver implements ConnectionObserver {

		final ConnectionObserver childObs;

		ChildObserver(ConnectionObserver childObs) {
			this.childObs = childObs;
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			ChannelOperations<?, ?> ops = ChannelOperations.get(connection.channel());
			if (ops == null && (error instanceof IOException || AbortedException.isConnectionReset(error) ||
					// DecoderException at this point might be SSL handshake issue or other TLS related issue.
					// In case of HTTP if there is an HTTP decoding issue, it is propagated with
					// io.netty.handler.codec.DecoderResultProvider
					// and not with throwing an exception
					error instanceof DecoderException)) {
				if (log.isDebugEnabled()) {
					log.debug(format(connection.channel(), "onUncaughtException(" + connection + ")"), error);
				}
			}
			else {
				log.error(format(connection.channel(), "onUncaughtException(" + connection + ")"), error);
			}
			connection.dispose();
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == State.DISCONNECTING) {
				if (connection.channel().isActive() && !connection.isPersistent()) {
					connection.dispose();
				}
			}

			childObs.onStateChange(connection, newState);
		}
	}

	static class DisposableBind implements CoreSubscriber<Channel>, DisposableServer, Connection {

		final MonoSink<DisposableServer> sink;
		final TransportConfig            config;
		final SocketAddress              bindAddress;

		Channel channel;
		Subscription subscription;

		DisposableBind(MonoSink<DisposableServer> sink, TransportConfig config, SocketAddress bindAddress) {
			this.sink = sink;
			this.config = config;
			this.bindAddress = bindAddress;
		}

		@Override
		public Channel channel() {
			return channel;
		}

		@Override
		public Context currentContext() {
			return sink.currentContext();
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public final void dispose() {
			if (channel != null) {
				if (channel.isActive()) {
					//"FutureReturnValueIgnored" this is deliberate
					channel.close();

					LoopResources loopResources = config.loopResources();
					if (loopResources instanceof ConnectionProvider) {
						((ConnectionProvider) loopResources).disposeWhen(bindAddress);
					}
				}
			}
			else {
				subscription.cancel();
			}
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void disposeNow(Duration timeout) {
			if (isDisposed()) {
				return;
			}
			dispose();
			Mono<Void> terminateSignals = Mono.empty();
			if (config.channelGroup != null) {
				List<Mono<Void>> channels = new ArrayList<>();
				// Wait for the running requests to finish
				config.channelGroup.forEach(channel -> {
					ChannelOperations<?, ?> ops = ChannelOperations.get(channel);
					if (ops != null) {
						channels.add(ops.onTerminate().doFinally(sig -> ops.dispose()));
					}
					else {
						//"FutureReturnValueIgnored" this is deliberate
						channel.close();
					}
				});
				if (!channels.isEmpty()) {
					terminateSignals = Mono.when(channels);
				}
			}

			try {
				onDispose().then(terminateSignals)
				           .block(timeout);
			}
			catch (IllegalStateException e) {
				if (e.getMessage()
						.contains("blocking read")) {
					throw new IllegalStateException("Socket couldn't be stopped within " + timeout.toMillis() + "ms");
				}
				throw e;
			}
		}

		@Override
		public void onComplete() {
		}

		@Override
		public void onError(Throwable t) {
			sink.error(ChannelBindException.fail(bindAddress, t));
		}

		@Override
		public void onNext(Channel channel) {
			this.channel = channel;
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Bound new server"));
			}
			sink.success(this);
			config.defaultConnectionObserver()
			      .then(config.connectionObserver())
			      .onStateChange(this, ConnectionObserver.State.CONNECTED);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(subscription, s)) {
				this.subscription = s;
				sink.onCancel(this);
				s.request(Long.MAX_VALUE);
			}
		}
	}

	static final class InetDisposableBind extends DisposableBind {

		InetDisposableBind(MonoSink<DisposableServer> sink, TransportConfig config, SocketAddress bindAddress) {
			super(sink, config, bindAddress);
		}

		@Override
		public InetSocketAddress address() {
			return (InetSocketAddress) channel().localAddress();
		}

		@Override
		public String host() {
			return address().getHostString();
		}

		@Override
		public int port() {
			return address().getPort();
		}
	}

	static final class UdsDisposableBind extends DisposableBind {

		UdsDisposableBind(MonoSink<DisposableServer> sink, TransportConfig config, SocketAddress bindAddress) {
			super(sink, config, bindAddress);
		}

		@Override
		public DomainSocketAddress address() {
			return (DomainSocketAddress) channel().localAddress();
		}

		@Override
		public String path() {
			return address().path();
		}
	}
}