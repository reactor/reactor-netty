/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.BiFunction;
import javax.net.ssl.SSLException;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.NetUtil;
import org.reactivestreams.Publisher;
import reactor.core.Cancellation;
import reactor.core.Exceptions;
import reactor.core.MultiProducer;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyState;
import reactor.ipc.netty.channel.NettyOperations;
import reactor.ipc.netty.options.EventLoopSelector;
import reactor.ipc.netty.options.ServerOptions;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * * A TCP server connector.
 *
 * @author Stephane Maldini
 */
public class TcpServer
		implements NettyConnector<NettyInbound, NettyOutbound>, MultiProducer {

	/**
	 * Bind a new TCP server to "loopback" on port {@literal 12012}.
	 * Handlers will run on the same thread they have beem receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create() {
		return create(NetUtil.LOCALHOST.getHostAddress());
	}

	/**
	 * Bind a new TCP server to the given bind address and port.
	 * Handlers will run on the same thread they have beem receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param options {@link ServerOptions} configuration input
	 *
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create(ServerOptions options) {
		return new TcpServer(Objects.requireNonNull(options, "options"));
	}

	/**
	 * Bind a new TCP server to "loopback" on the given port.
	 * Handlers will run on the same thread they have beem receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param port the port to listen on loopback
	 *
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create(int port) {
		return create(NetUtil.LOCALHOST.getHostAddress(), port);
	}

	/**
	 * Bind a new TCP server to the given bind address on port {@literal 12012}.
	 * Handlers will run on the same thread they have beem receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the
	 * default port 12012
	 *
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create(String bindAddress) {
		return create(bindAddress, 0);
	}

	/**
	 * Bind a new TCP server to the given bind address and port.
	 * Handlers will run on the same thread they have beem receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param port the port to listen on the passed bind address
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the
	 * passed port
	 *
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create(String bindAddress, int port) {
		return create(ServerOptions.create()
		                           .listen(bindAddress, port));
	}

	final ChannelGroup      channelGroup;
	final ServerOptions     options;
	final SslContext        sslContext;
	final InetSocketAddress listenAddress;
	final boolean           useNative;

	/**
	 * @param options
	 */
	protected TcpServer(ServerOptions options) {
		this.options = options.toImmutable();
		this.listenAddress = options.listenAddress() == null ? new InetSocketAddress(0) :
				options.listenAddress();

		if (options.ssl() != null) {
			try {
				this.sslContext = options.ssl()
				                         .build();
				if (log.isDebugEnabled()) {
					log.debug("Will serve with SSL enabled using context {}",
							sslContext.getClass()
							          .getSimpleName());
				}
			}
			catch (SSLException e) {
				throw Exceptions.bubble(e);
			}
		}
		else {
			this.sslContext = null;
		}
		this.useNative = options.preferNative() && !(sslContext instanceof JdkSslContext);

		if (options.managed()) {
			log.debug("Server is managed.");
			this.channelGroup = new DefaultChannelGroup(null);
		}
		else {
			log.debug("Server is not managed (Not directly introspectable)");
			this.channelGroup = null;
		}
	}

	@Override
	public long downstreamCount() {
		return channelGroup == null ? -1 : channelGroup.size();
	}

	@Override
	public Iterator<?> downstreams() {
		if (channelGroup == null) {
			return null;
		}
		return new Iterator<Object>() {
			final Iterator<io.netty.channel.Channel> channelIterator =
					channelGroup.iterator();

			@Override
			public boolean hasNext() {
				return channelIterator.hasNext();
			}

			@Override
			public Object next() {
				return channelIterator.next()
				                      .attr(NettyOperations.OPERATIONS_ATTRIBUTE_KEY)
				                      .get();
			}
		};
	}

	/**
	 * Get the {@link ServerOptions} currently in effect.
	 *
	 * @return the current server options
	 */
	public final ServerOptions getOptions() {
		return options;
	}

	/**
	 * Get the {@link SslContext}
	 *
	 * @return the {@link SslContext}
	 */
	public final SslContext getSslContext() {
		return sslContext;
	}

	@Override
	public final Mono<? extends NettyState> newHandler(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return Mono.create(sink -> {
			ServerBootstrap bootstrap = new ServerBootstrap();

			options(bootstrap);
			childOptions(bootstrap);
			childHandler(bootstrap, handler);
			Cancellation onCancelGroups = channel(bootstrap);
			Cancellation onCancelServer = bind(bootstrap, sink, onCancelGroups);

			sink.setCancellation(onCancelServer);
		});
	}

	@Override
	public String toString() {
		return "TcpServer:" + options.listenAddress()
		                             .toString();
	}

	/**
	 * Triggered when {@link Channel} has been fully setup by the server to bind
	 * handler
	 * state.
	 *
	 * @param handler the user-provided handler on in/out
	 * @param nativeChannel the configured {@link Channel}
	 */
	protected void onSetup(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
			SocketChannel nativeChannel) {
		NettyOperations.bind(nativeChannel, handler, null, null);
	}

	/**
	 * Bind the passed {@link ServerBootstrap} and connect the mono sink to pass bind
	 * error if any
	 *
	 * @param bootstrap the current {@link ServerBootstrap}
	 * @param sink the {@link MonoSink} used to forward binding error
	 *
	 * @return a {@link Cancellation} that shutdown the server on dispose
	 */
	protected Cancellation bind(ServerBootstrap bootstrap,
			MonoSink<NettyState> sink,
			Cancellation onClose) {
		return NettyOperations.newConnectHandler(bootstrap.bind(listenAddress),
				sink,
				onClose,
				true);
	}

	/**
	 * Return the current {@link EventLoopSelector}
	 * @return the current {@link EventLoopSelector}
	 */
	protected final EventLoopSelector optionsOrDefaultLoops(){
		return this.options.eventLoopSelector() != null ?
				this.options.eventLoopSelector()
				            .get() : global();
	}


	/**
	 * Return the global {@link EventLoopSelector}
	 * @return the global {@link EventLoopSelector}
	 */
	protected EventLoopSelector global(){
		return TcpEventLoopSelector.DEFAULT_TCP_LOOPS;
	}

	/**
	 * Set current {@link ServerBootstrap} channel and group
	 *
	 * @param bootstrap {@link ServerBootstrap}
	 *
	 * @return a {@link Cancellation} that shutdown the eventLoopSelector on dispose
	 */
	protected Cancellation channel(ServerBootstrap bootstrap) {
		final Cancellation onCancel;

		EventLoopSelector loops = optionsOrDefaultLoops();

		final EventLoopGroup selectorGroup = loops.onServerSelect(useNative);
		final EventLoopGroup elg = loops.onServer(useNative);

		if (loops != global()) {
			onCancel = () -> {
				try {
					selectorGroup.shutdownGracefully()
					             .sync();
				}
				catch (InterruptedException i) {
					log.error(
							"error while disposing the handler selector eventLoopSelector",
							i);
				}
				try {
					selectorGroup.shutdownGracefully()
					             .sync();
				}
				catch (InterruptedException i) {
					log.error(
							"error while disposing the handler selector eventLoopSelector",
							i);
				}
			};
		}
		else {
			onCancel = null;
		}

		bootstrap.group(selectorGroup, elg)
		         .channel(loops.onServerChannel(elg));

		return onCancel;
	}

	/**
	 * Set current {@link ServerBootstrap} childHandler
	 *
	 * @param bootstrap {@link ServerBootstrap}
	 * @param handler user provided in/out handler
	 */
	protected void childHandler(ServerBootstrap bootstrap,
			BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		bootstrap.childHandler(new ServerSetup(handler, this));
	}

	/**
	 * Set current {@link ServerBootstrap} childOptions
	 *
	 * @param bootstrap {@link ServerBootstrap}
	 */
	protected void childOptions(ServerBootstrap bootstrap) {
		bootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
		         .childOption(ChannelOption.SO_RCVBUF, options.rcvbuf())
		         .childOption(ChannelOption.SO_SNDBUF, options.sndbuf())
		         .childOption(ChannelOption.AUTO_READ, false)
		         .childOption(ChannelOption.SO_KEEPALIVE, options.keepAlive())
		         .childOption(ChannelOption.SO_LINGER, options.linger())
		         .childOption(ChannelOption.TCP_NODELAY, options.tcpNoDelay())
		         .childOption(ChannelOption.SO_REUSEADDR, options.reuseAddr())
		         .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS,
				         (int) Math.min(Integer.MAX_VALUE, options.timeoutMillis()));
	}

	/**
	 * Set current {@link ServerBootstrap} options
	 *
	 * @param bootstrap {@link ServerBootstrap}
	 */
	protected void options(ServerBootstrap bootstrap) {
		bootstrap.option(ChannelOption.SO_BACKLOG, options.backlog())
		         .option(ChannelOption.SO_REUSEADDR, options.reuseAddr());
	}

	/**
	 * Current {@link LoggingHandler}
	 *
	 * @return a {@link LoggingHandler}
	 */
	protected LoggingHandler logger() {
		return loggingHandler;
	}

	static final class ServerSetup extends ChannelInitializer<SocketChannel> {

		final TcpServer parent;
		final BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>>
		                handler;

		ServerSetup(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
				TcpServer parent) {
			this.parent = parent;
			this.handler = handler;
		}

		@Override
		public void initChannel(final SocketChannel ch) throws Exception {
			if (null != parent.options.onChannelInit()) {
				if (parent.options.onChannelInit()
				                  .test(ch)) {
					if (log.isDebugEnabled()) {
						log.debug("TCP DROPPED by onChannelInit predicate {}", ch);
					}
					ch.close();
					return;
				}
			}

			if (parent.channelGroup != null) {
				parent.channelGroup.add(ch);
			}

			ChannelPipeline pipeline = ch.pipeline();

			if (parent.sslContext != null) {
				SslHandler sslHandler = parent.sslContext.newHandler(ch.alloc());
				sslHandler.setHandshakeTimeoutMillis(parent.options.sslHandshakeTimeoutMillis());
				pipeline.addFirst(NettyHandlerNames.SslHandler, sslHandler);
			}

			if (log.isDebugEnabled()) {
				pipeline.addLast(NettyHandlerNames.LoggingHandler, parent.logger());
			}

			try {
				parent.onSetup(handler, ch);
			}
			finally {
				if (null != parent.options.afterChannelInit()) {
					parent.options.afterChannelInit()
					              .accept(ch);
				}
			}

		}
	}

	static final Logger            log                      =
			Loggers.getLogger(TcpServer.class);
	static final LoggingHandler    loggingHandler           =
			new LoggingHandler(TcpServer.class);

}
