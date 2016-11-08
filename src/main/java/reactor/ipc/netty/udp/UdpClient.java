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

import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.util.Objects;
import java.util.function.BiFunction;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
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
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.NettyState;
import reactor.ipc.netty.channel.NettyOperations;
import reactor.ipc.netty.options.EventLoopSelector;
import reactor.ipc.netty.options.NettyOptions;
import reactor.ipc.netty.options.ServerOptions;
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
		return create(bindAddress, NettyOptions.DEFAULT_PORT);
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

			Cancellation onCancelGroups = channel(bootstrap);
			options(bootstrap);
			handler(bootstrap, targetHandler, sink, onCancelGroups);
			Cancellation onCancelClient = bind(bootstrap, sink, onCancelGroups);

			sink.setCancellation(onCancelClient);
		});
	}

	@SuppressWarnings("unchecked")
	void onSetup(BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler,
			DatagramChannel ioChannel, MonoSink<NettyState> sink, Cancellation onClose) {
		UdpOperations.bind(ioChannel,
				options.multicastInterface(),
				handler, sink, onClose);
	}

	/**
	 * Return the current {@link EventLoopSelector}
	 * @return the current {@link EventLoopSelector}
	 */
	protected EventLoopSelector optionsOrDefaultLoops(){
		return this.options.eventLoopSelector() != null ?
				this.options.eventLoopSelector()
				            .get() : DEFAULT_UDP_LOOPS;
	}

	/**
	 * Set current {@link Bootstrap} channel and group
	 *
	 * @param bootstrap {@link Bootstrap}
	 *
	 * @return a {@link Cancellation} that shutdown the eventLoopSelector on dispose
	 */
	Cancellation channel(Bootstrap bootstrap) {

		EventLoopSelector loops = optionsOrDefaultLoops();

		final EventLoopGroup elg = loops.onServer(options.protocolFamily() == null && options.preferNative());
		final Cancellation onCancel;

		if (loops == DEFAULT_UDP_LOOPS) {
			onCancel = null;
		}
		else {
			onCancel = () -> {
				try {
					elg.shutdownGracefully()
					   .sync();
				}
				catch (InterruptedException i) {
					log.error("error while disposing the handler io eventLoopSelector",
							i);
				}
			};
		}
		bootstrap.group(elg);

		if (options.protocolFamily() == null && options.preferNative()) {
			bootstrap.channel(loops.onDatagramChannel(elg));
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
	Cancellation bind(Bootstrap bootstrap,
			MonoSink<NettyState> sink,
			Cancellation onClose) {

		return NettyOperations.newConnectHandler(bootstrap.bind(), sink, onClose);
	}

	@SuppressWarnings("unchecked")
	void handler(Bootstrap bootstrap,
			BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> sink,
			Cancellation onClose) {
		bootstrap.handler(new ClientSetup(handler, this, sink, onClose));
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

		final UdpClient            parent;
		final MonoSink<NettyState> sink;
		final Cancellation         onClose;
		final BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>>
		                           handler;

		public ClientSetup(BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler,
				UdpClient parent, MonoSink<NettyState> sink, Cancellation onClose) {
			this.parent = parent;
			this.onClose = onClose;
			this.sink = sink;
			this.handler = handler;
		}

		@Override
		public void initChannel(final DatagramChannel ch) throws Exception {
			if (log.isDebugEnabled()) {
				log.debug("UDP CONNECT {} {}", parent.options.multicastInterface(), ch);
			}
			if (parent.options.onChannelInit() != null) {
				if (parent.options.onChannelInit()
				                  .test(ch)) {
					if (log.isDebugEnabled()) {
						log.debug("TCP DROPPED by onChannelInit predicate {}", ch);
					}
					ch.close();
					sink.success();
					return;
				}
			}

			ChannelPipeline pipeline = ch.pipeline();

			if (log.isDebugEnabled()) {
				pipeline.addLast(NettyHandlerNames.LoggingHandler, loggingHandler);
			}

			try {
				parent.onSetup(handler, ch, sink, onClose);
			}
			finally {
				if (null != parent.options.afterChannelInit()) {
					parent.options.afterChannelInit()
					              .accept(ch);
				}
			}
		}
	}

	static final int DEFAULT_UDP_THREAD_COUNT = Integer.parseInt(System.getProperty(
			"reactor.udp.ioThreadCount",
			"" + Schedulers.DEFAULT_POOL_SIZE));

	static final Logger         log            = Loggers.getLogger(UdpClient.class);
	static final LoggingHandler loggingHandler = new LoggingHandler(UdpClient.class);

	static final EventLoopSelector DEFAULT_UDP_LOOPS =
			EventLoopSelector.create("udp", DEFAULT_UDP_THREAD_COUNT, true);
}
