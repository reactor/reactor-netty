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

package reactor.ipc.netty.udp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.NetUtil;
import org.reactivestreams.Publisher;
import reactor.core.Cancellation;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.NettyState;
import reactor.ipc.netty.channel.NettyHandlerNames;
import reactor.ipc.netty.channel.NettyOperations;
import reactor.ipc.netty.config.ServerOptions;
import reactor.ipc.netty.util.CompositeCancellation;
import reactor.ipc.netty.util.NettyNativeDetector;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * A UDP client connector.
 *
 * @author Stephane Maldini
 */
final public class UdpClient implements NettyConnector<UdpInbound, UdpOutbound> {

	/**
	 * Bind a new UDP client to the "loopback" address.
	 * Handlers will run on the same thread they have beem receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @return a new {@link UdpClient}
	 */
	public static UdpClient create() {
		return create(NetUtil.LOCALHOST.getHostAddress());
	}

	/**
	 * Bind a new UDP client to the given bind address.
	 * Handlers will run on the same thread they have beem receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the client on the
	 * passed port
	 *
	 * @return a new {@link UdpClient}
	 */
	public static UdpClient create(String bindAddress) {
		return create(bindAddress, NettyConnector.DEFAULT_PORT);
	}

	/**
	 * Bind a new UDP client to the "loopback" address and specified port.
	 * Handlers will run on the same thread they have beem receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param port the port to listen on the passed bind address
	 *
	 * @return a new {@link UdpClient}
	 */
	public static UdpClient create(int port) {
		return create(NetUtil.LOCALHOST.getHostAddress(), port);
	}

	/**
	 * Bind a new UDP client to the given bind address and port.
	 * Handlers will run on the same thread they have beem receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param port the port to listen on the passed bind address
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the client on the
	 * passed port
	 *
	 * @return a new {@link UdpClient}
	 */
	public static UdpClient create(String bindAddress, int port) {
		return create(ServerOptions.create()
		                           .listen(bindAddress, port));
	}

	/**
	 * Bind a new UDP client to the given bind address and port.
	 * Handlers will run on the same thread they have beem receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param options
	 *
	 * @return a new {@link UdpClient}
	 */
	public static UdpClient create(ServerOptions options) {
		return new UdpClient(Objects.requireNonNull(options, "options"));
	}

	final InetSocketAddress listenAddress;
	final ServerOptions     options;

	UdpClient(ServerOptions options) {
		this.listenAddress = options.listenAddress();
		this.options = options.toImmutable();
	}

	/**
	 * Get the address to which this client is bound.
	 *
	 * @return the bind address
	 */
	public InetSocketAddress getListenAddress() {
		return listenAddress;
	}

	/**
	 * Get the {@link ServerOptions} currently in effect.
	 *
	 * @return the client options in use
	 */
	public ServerOptions getOptions() {
		return options;
	}

	@Override
	public Mono<? extends NettyState> newHandler(BiFunction<? super UdpInbound, ? super
			UdpOutbound, ?
			extends Publisher<Void>> handler) {
		final BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>>
				targetHandler = null == handler ? NettyOperations.noopHandler() : handler;

		return Mono.create(sink -> {
			Bootstrap bootstrap = new Bootstrap();

			options(bootstrap);
			handler(bootstrap, targetHandler, sink);
			Cancellation onCancelGroups = channel(bootstrap);
			Cancellation onCancelClient = bind(bootstrap, sink);

			sink.setCancellation(CompositeCancellation.from(onCancelClient,
					onCancelGroups));
		});
	}

	@SuppressWarnings("unchecked")
	void onSetup(BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler,
			DatagramChannel ioChannel,
			MonoSink<NettyState> sink) {
		UdpOperations.bind(ioChannel,
				options.multicastInterface(),
				handler,
				sink);
	}

	/**
	 * Set current {@link Bootstrap} channel and group
	 *
	 * @param bootstrap {@link Bootstrap}
	 *
	 * @return a {@link Cancellation} that shutdown the eventLoopGroup on dispose
	 */
	Cancellation channel(Bootstrap bootstrap) {
		final Cancellation onCancel;
		final EventLoopGroup elg;

		if (null != options.eventLoopGroup()) {
			elg = options.eventLoopGroup();
			onCancel = null;
		}
		else {
			int ioThreadCount = DEFAULT_UDP_THREAD_COUNT;
			ThreadFactory tf = (Runnable r) -> {
				Thread t = new Thread(r, "reactor-udp-io-" + COUNTER.incrementAndGet());
				t.setDaemon(options.daemon());
				return t;
			};

			elg = options.protocolFamily() == null ? NettyNativeDetector.instance()
			                                                            .newEventLoopGroup(
					                                                            ioThreadCount,
					                                                            tf) :
					new NioEventLoopGroup(ioThreadCount, tf);
			onCancel = () -> {
				try {
					elg.shutdownGracefully()
					   .sync();
				}
				catch (InterruptedException i) {
					log.error("error while disposing the handler io eventLoopGroup", i);
				}
			};
		}

		bootstrap.group(elg);

		if ((options.protocolFamily() == null) && NettyNativeDetector.instance()
		                                                             .getDatagramChannel(
				                                                             elg)
		                                                             .getSimpleName()
		                                                             .startsWith("Epoll")) {

			bootstrap.channel(NettyNativeDetector.instance()
			                                     .getDatagramChannel(elg));
		}
		else {
			bootstrap.channelFactory(() -> new NioDatagramChannel(toNettyFamily(options.protocolFamily())));
		}
		return onCancel;
	}

	/**
	 * Connect to the local address with the configured {@link Bootstrap}
	 *
	 * @param bootstrap current {@link Bootstrap}
	 * @param sink the {@link MonoSink} to bind connection termination on terminated or
	 * failure
	 *
	 * @return a {@link Cancellation} that terminate the connection on dispose
	 */
	Cancellation bind(Bootstrap bootstrap, MonoSink<NettyState> sink) {

		ChannelFuture f = bootstrap.bind()
		                           .addListener(new OnBindListener(sink));

		return () -> {
			try {
				f.channel()
				 .close()
				 .sync();
			}
			catch (InterruptedException e) {
				log.error("error while disposing the channel", e);
			}
		};
	}

	@SuppressWarnings("unchecked")
	void handler(Bootstrap bootstrap,
			BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> sink) {
		bootstrap.handler(new ClientSetup(handler, this, sink));
	}

	/**
	 * Set current {@link Bootstrap} options
	 *
	 * @param bootstrap {@link Bootstrap}
	 */
	void options(Bootstrap bootstrap) {
		bootstrap.localAddress(listenAddress)
		         .option(ChannelOption.AUTO_READ, false)
		         .option(ChannelOption.SO_RCVBUF, options.rcvbuf())
		         .option(ChannelOption.SO_SNDBUF, options.sndbuf())
		         .option(ChannelOption.SO_REUSEADDR, options.reuseAddr())
		         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
				         (int) Math.min(Integer.MAX_VALUE, options.timeoutMillis()));

		if (null != options.multicastInterface()) {
			bootstrap.option(ChannelOption.IP_MULTICAST_IF, options.multicastInterface());
		}
	}

	InternetProtocolFamily toNettyFamily(ProtocolFamily family) {
		if (family == null) {
			return null;
		}
		switch (family.name()) {
			case "INET":
				return InternetProtocolFamily.IPv4;
			case "INET6":
				return InternetProtocolFamily.IPv6;
			default:
				throw new IllegalArgumentException("Unsupported protocolFamily: " + family.name());
		}
	}

	static final class ClientSetup extends ChannelInitializer<DatagramChannel> {

		final UdpClient   parent;
		final MonoSink<NettyState> sink;
		final BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>>
		                  handler;

		public ClientSetup(BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler,
				UdpClient parent,
				MonoSink<NettyState> sink) {
			this.parent = parent;
			this.sink = sink;
			this.handler = handler;
		}

		@Override
		public void initChannel(final DatagramChannel ch) throws Exception {
			if (log.isDebugEnabled()) {
				log.debug("CONNECT {} {}", parent.options.multicastInterface(), ch);
			}

			ChannelPipeline pipeline = ch.pipeline();

			if (log.isDebugEnabled()) {
				pipeline.addLast(NettyHandlerNames.LoggingHandler, loggingHandler);
			}

			parent.onSetup(handler, ch, sink);

			if(parent.options.onStart() != null){
				parent.options.onStart().accept(ch);
			}

			if (null != parent.options.pipelineConfigurer()) {
				parent.options.pipelineConfigurer()
				              .accept(pipeline);
			}
		}
	}

	final static class OnBindListener implements ChannelFutureListener {

		final MonoSink<NettyState>            sink;

		public OnBindListener(MonoSink<NettyState> sink) {
			this.sink = sink;
		}

		@Override
		public void operationComplete(ChannelFuture f) throws Exception {
			if (log.isInfoEnabled()) {
				log.info("BIND {} {}",
						f.isSuccess() ? "OK" : "FAILED",
						f.channel()
						 .localAddress());
			}
			if (!f.isSuccess()) {
				if (f.cause() != null) {
					sink.error(f.cause());
				}
				else {
					sink.error(new IOException("error while binding to " + f.channel()
					                                                        .localAddress()));
				}
			}
		}

	}

	static final int DEFAULT_UDP_THREAD_COUNT = Integer.parseInt(System.getProperty(
			"reactor.udp.ioThreadCount",
			"" + Schedulers.DEFAULT_POOL_SIZE));

	static final Logger         log            = Loggers.getLogger(UdpClient.class);
	static final AtomicLong     COUNTER        = new AtomicLong();
	static final LoggingHandler loggingHandler = new LoggingHandler(UdpClient.class);
}
