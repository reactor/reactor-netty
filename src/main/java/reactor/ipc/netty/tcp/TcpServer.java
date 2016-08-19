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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.net.ssl.SSLException;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
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
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.Exceptions;
import reactor.core.MultiProducer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.Channel;
import reactor.ipc.netty.common.ChannelBridge;
import reactor.ipc.netty.common.DuplexSocket;
import reactor.ipc.netty.common.MonoChannelFuture;
import reactor.ipc.netty.common.NettyChannel;
import reactor.ipc.netty.common.NettyChannelHandler;
import reactor.ipc.netty.common.NettyHandlerNames;
import reactor.ipc.netty.config.ServerOptions;
import reactor.ipc.netty.util.NettyNativeDetector;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Base functionality needed by all servers that communicate with clients over TCP.
 *
 * @author Stephane Maldini
 */
public class TcpServer extends DuplexSocket<ByteBuf, ByteBuf, NettyChannel> implements
                                                                    MultiProducer,
                                                                    ChannelBridge<TcpChannel> {

	public static final int DEFAULT_TCP_THREAD_COUNT = Integer.parseInt(System.getProperty(
			"reactor.tcp.ioThreadCount",
			"" + Schedulers.DEFAULT_POOL_SIZE / 2));

	public static final int DEFAULT_TCP_SELECT_COUNT =
			Integer.parseInt(System.getProperty("reactor.tcp.selectThreadCount", "" + DEFAULT_TCP_THREAD_COUNT));

	/**
	 * Bind a new TCP server to "loopback" on port {@literal 12012}. The default server implementation is
	 * scanned from the classpath on Class init. Support for Netty is provided as long as the
	 * relevant library dependencies are on the classpath. <p> To reply data on the active connection, {@link
	 * Channel#send} can subscribe to any passed {@link org.reactivestreams.Publisher}. <p> Note that
	 * read will pause when capacity number of elements have been dispatched. <p>
	 * Emitted channels will run on the same thread they have beem receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static TcpServer create() {
		return create(DEFAULT_BIND_ADDRESS);
	}

	/**
	 * Bind a new TCP server to the given bind address and port. The default server implementation is scanned
	 * from the classpath on Class init. Support for Netty is provided as long as the relevant library dependencies are
	 * on the classpath. <p> A {@link TcpServer} is a specific kind of {@link org.reactivestreams.Publisher} that will
	 * emit: - onNext {@link Channel} to consume data from - onComplete when server is shutdown - onError when any
	 * error (more specifically IO error) occurs From the emitted {@link Channel}, one can decide to add in-channel
	 * consumers to read any incoming data. <p> To reply data on the active connection, {@link Channel#send} can
	 * subscribe to any passed {@link org.reactivestreams.Publisher}. <p> Note that read will pause when capacity number of elements have been dispatched. <p>
	 * Emitted channels will run on the same thread they have beem receiving IO events.
	 * <p>
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param options
	 *
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static TcpServer create(ServerOptions options) {
		return new TcpServer(options);
	}

	/**
	 * Bind a new TCP server to "loopback" on the given port. The default server implementation is scanned
	 * from the classpath on Class init. Support for Netty first is provided as long as the relevant
	 * library dependencies are on the classpath. <p> A {@link TcpServer} is a specific kind of {@link
	 * org.reactivestreams.Publisher} that will emit: - onNext {@link Channel} to consume data from - onComplete
	 * when server is shutdown - onError when any error (more specifically IO error) occurs From the emitted {@link
	 * Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data on the
	 * active connection, {@link Channel#send} can subscribe to any passed {@link
	 * org.reactivestreams.Publisher}. <p> Note that  read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param port the port to listen on loopback
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static TcpServer create(int port) {
		return create(DEFAULT_BIND_ADDRESS, port);
	}

	/**
	 * Bind a new TCP server to the given bind address on port {@literal 12012}. The default server
	 * implementation is scanned from the classpath on Class init. Support for Netty first is provided
	 * as long as the relevant library dependencies are on the classpath. <p> A {@link TcpServer} is a specific kind of
	 * {@link org.reactivestreams.Publisher} that will emit: - onNext {@link Channel} to consume data from -
	 * onComplete when server is shutdown - onError when any error (more specifically IO error) occurs From the emitted
	 * {@link Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data
	 * on the active connection, {@link Channel#send} can subscribe to any passed {@link
	 * org.reactivestreams.Publisher}. <p> Note that  read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the default port 12012
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static TcpServer create(String bindAddress) {
		return create(bindAddress, 0);
	}

	/**
	 * Bind a new TCP server to the given bind address and port. The default server implementation is scanned
	 * from the classpath on Class init. Support for Netty is provided as long as the relevant
	 * library dependencies are on the classpath. <p> A {@link TcpServer} is a specific kind of {@link
	 * org.reactivestreams.Publisher} that will emit: - onNext {@link Channel} to consume data from - onComplete
	 * when server is shutdown - onError when any error (more specifically IO error) occurs From the emitted {@link
	 * Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data on the
	 * active connection, {@link Channel#send} can subscribe to any passed {@link
	 * org.reactivestreams.Publisher}. <p> Note that  read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param port the port to listen on the passed bind address
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the passed port
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static TcpServer create(String bindAddress, int port) {
		return create(ServerOptions.create()
		                           .listen(bindAddress, port));
	}

	final ServerBootstrap bootstrap;
	final EventLoopGroup  selectorGroup;
	final EventLoopGroup  ioGroup;
	final ChannelGroup    channelGroup;
	final ServerOptions   options;
	final SslContext      sslContext;

	//Carefully reset
	InetSocketAddress listenAddress;
	ChannelFuture     bindFuture;

	protected TcpServer(ServerOptions options) {
		this.listenAddress = options.listenAddress();
		this.options = options.toImmutable();
		int selectThreadCount = DEFAULT_TCP_SELECT_COUNT;
		int ioThreadCount = DEFAULT_TCP_THREAD_COUNT;

		NettyNativeDetector channelAdapter;
		if(options.ssl() != null) {
			try {
				this.sslContext = options.ssl().build();
				if (log.isDebugEnabled()) {
					log.debug("Serving SSL enabled using context {}", sslContext.getClass().getSimpleName());
				}
				channelAdapter = sslContext instanceof JdkSslContext ?
						NettyNativeDetector.force(false) :
						NettyNativeDetector.instance();
			}
			catch (SSLException e) {
				throw Exceptions.bubble(e);
			}
		}
		else{
			this.sslContext = null;
			channelAdapter = NettyNativeDetector.instance();
		}

		this.selectorGroup = channelAdapter.newEventLoopGroup(selectThreadCount,
				(Runnable r) -> {
					Thread t = new Thread(r, "reactor-tcp-server-select-"+COUNTER
							.incrementAndGet());
					t.setDaemon(options.daemon());
					return t;
				});

		if (null != options.eventLoopGroup()) {
			this.ioGroup = options.eventLoopGroup();
		}
		else {
			this.ioGroup = channelAdapter.newEventLoopGroup(ioThreadCount,
					(Runnable r) -> {
						Thread t = new Thread(r,
								"reactor-tcp-server-io-" + COUNTER.incrementAndGet());
						t.setDaemon(options.daemon());
						return t;
					});
		}

		ServerBootstrap _serverBootstrap =
				new ServerBootstrap().group(selectorGroup, ioGroup)
				                     .channel(channelAdapter.getServerChannel(ioGroup))
				                     .option(ChannelOption.SO_BACKLOG, options.backlog())
		                             .childOption(ChannelOption.ALLOCATOR,
				                             PooledByteBufAllocator.DEFAULT)
		                             .childOption(ChannelOption.SO_RCVBUF,
				                             options.rcvbuf())
		                             .childOption(ChannelOption.SO_SNDBUF,
				                             options.sndbuf())
		                             .childOption(ChannelOption.AUTO_READ, false)
		                             .childOption(ChannelOption.SO_KEEPALIVE,
				                             options.keepAlive())
		                             .childOption(ChannelOption.SO_LINGER,
				                             options.linger())
		                             .childOption(ChannelOption.TCP_NODELAY,
				                             options.tcpNoDelay())
		                             .option(ChannelOption.SO_REUSEADDR, options.reuseAddr())
		                             .childOption(ChannelOption.SO_REUSEADDR, options.reuseAddr())
		                             .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS,
				                             (int) Math.min(Integer.MAX_VALUE,
						                             options
								                             .timeoutMillis()))
		                                   .localAddress((null == listenAddress ?
				                                   new InetSocketAddress(0) : listenAddress));

		if (options.managed()) {
			log.debug("Server is managed.");
			this.channelGroup = new DefaultChannelGroup(null);
		}
		else {
			log.debug("Server is not managed (Not directly introspectable)");
			this.channelGroup = null;
		}

		this.bootstrap = _serverBootstrap;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<Void> doShutdown() {
		try {
			bindFuture.channel()
			          .close()
			          .sync();
		}
		catch (InterruptedException ie) {
			return Mono.error(ie);
		}

		final Mono<Void> shutdown = MonoChannelFuture.from(selectorGroup.shutdownGracefully());

		if (null == getOptions() || null == getOptions().eventLoopGroup()) {
			return shutdown.then(aVoid -> MonoChannelFuture.from(ioGroup.shutdownGracefully()));
		}

		return shutdown;
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
			final Iterator<io.netty.channel.Channel> channelIterator = channelGroup.iterator();

			@Override
			public boolean hasNext() {
				return channelIterator.hasNext();
			}

			@Override
			public Object next() {
				return channelIterator.next()
				                      .pipeline()
				                      .get(NettyChannelHandler.class);
			}
		};
	}

	/**
	 * Get the address to which this server is bound. If port 0 was used, returns the resolved port if possible
	 * @return the address bound
	 */
	public InetSocketAddress getListenAddress() {
		return listenAddress;
	}

	/**
	 * Get the {@link ServerOptions} currently in effect.
	 * @return the current server options
	 */
	protected ServerOptions getOptions() {
		return options;
	}

	@Override
	public String toString() {
		return "TcpServer:" + getListenAddress().toString();
	}

	@Override
	protected Mono<Void> doStart(final Function<? super NettyChannel, ? extends Publisher<Void>> handler) {

		bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
			@Override
			public void initChannel(final SocketChannel ch) throws Exception {
				if (log.isDebugEnabled()) {
					log.debug("CONNECT {}", ch);
				}

				if (channelGroup != null) {
					channelGroup.add(ch);
				}

				bindChannel(handler, ch);
			}
		});

		bindFuture = bootstrap.bind();

		return new MonoChannelFuture<ChannelFuture>(bindFuture) {
			@Override
			protected void doComplete(ChannelFuture future, Subscriber<? super Void> s) {
				if(log.isInfoEnabled()) {
					log.info("BIND {} {}",
							future.isSuccess() ? "OK" : "FAILED",
							future.channel()
							      .localAddress());
				}
				if (listenAddress.getPort() == 0) {
					listenAddress = (InetSocketAddress) future.channel()
					                                          .localAddress();
				}
				super.doComplete(future, s);
			}
		};
	}

	protected SslContext getSslContext() {
		return sslContext;
	}

	@Override
	public TcpChannel createChannelBridge(io.netty.channel.Channel ioChannel,
			Flux<Object> input,
			Object... parameters) {
		return new TcpChannel(ioChannel, input);
	}

	protected void bindChannel(Function<? super NettyChannel, ? extends Publisher<Void>> handler, SocketChannel nativeChannel) {
		ChannelPipeline pipeline = nativeChannel.pipeline();

		if (sslContext != null) {
			SslHandler sslHandler =  sslContext.newHandler
					(nativeChannel.alloc());
			sslHandler.setHandshakeTimeoutMillis(options.sslHandshakeTimeoutMillis());
			pipeline
			  .addFirst(NettyHandlerNames.SslHandler, sslHandler);
		}

		if (null != getOptions() && null != getOptions().pipelineConfigurer()) {
			getOptions().pipelineConfigurer()
			            .accept(pipeline);
		}


		if (log.isDebugEnabled()) {
			pipeline.addLast(NettyHandlerNames.LoggingHandler,
					new LoggingHandler(TcpServer.class));
		}

		pipeline.addLast(NettyHandlerNames.ReactiveBridge,
				new NettyChannelHandler<>(handler, this, nativeChannel));
	}

	final static Logger log = Loggers.getLogger(TcpServer.class);

	static final AtomicLong COUNTER = new AtomicLong();
}
