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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.NetUtil;
import org.reactivestreams.Publisher;
import reactor.core.Cancellation;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyState;
import reactor.ipc.netty.channel.ColocatedEventLoopGroup;
import reactor.ipc.netty.channel.NettyHandlerNames;
import reactor.ipc.netty.channel.NettyOperations;
import reactor.ipc.netty.config.ClientOptions;
import reactor.ipc.netty.util.CompositeCancellation;
import reactor.ipc.netty.util.NettyNativeDetector;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * A TCP client connector.
 *
 * @author Stephane Maldini
 */
public class TcpClient implements NettyConnector<NettyInbound, NettyOutbound> {

	/**
	 * Bind a new TCP client to the localhost on port 12012. The default client
	 * implementation is scanned from the classpath on Class init.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @return a new {@link TcpClient}
	 */
	public static TcpClient create() {
		return create(NetUtil.LOCALHOST.getHostAddress());
	}

	/**
	 * Bind a new TCP client to the specified connect address and port 12012.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param bindAddress the address to connect to on port 12012
	 * <p>
	 * a new {@link TcpClient}
	 */
	public static TcpClient create(String bindAddress) {
		return create(bindAddress, NettyConnector.DEFAULT_PORT);
	}

	/**
	 * Bind a new TCP client to "loopback" on the the specified port. The default client
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param port the port to connect to on "loopback"
	 * <p>
	 * a new {@link TcpClient}
	 */
	public static TcpClient create(int port) {
		return create(NetUtil.LOCALHOST.getHostAddress(), port);
	}

	/**
	 * Bind a new TCP client to the specified connect address and port.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param bindAddress the address to connect to
	 * @param port the port to connect to
	 * <p>
	 * a new {@link TcpClient}
	 */
	public static TcpClient create(String bindAddress, int port) {
		return create(ClientOptions.to(bindAddress, port));
	}

	/**
	 * Bind a new TCP client to the specified connect address and port.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param options a new {@link TcpClient}
	 */
	public static TcpClient create(ClientOptions options) {
		return new TcpClient(Objects.requireNonNull(options, "options"));
	}

	final ClientOptions       options;
	final SslContext          sslContext;
	final NettyNativeDetector channelAdapter;

	protected TcpClient(ClientOptions options) {
		this.options = options.toImmutable();

		if (options.ssl() != null) {
			try {
				sslContext = options.ssl()
				                    .build();

				if (log.isDebugEnabled()) {
					log.debug("Will connect with SSL enabled using context {}",
							sslContext.getClass()
							          .getSimpleName());
				}

				channelAdapter = sslContext instanceof JdkSslContext ?
						NettyNativeDetector.force(false) : NettyNativeDetector.instance();
			}
			catch (SSLException ssle) {
				throw Exceptions.bubble(ssle);
			}
		}
		else {
			sslContext = null;
			channelAdapter = NettyNativeDetector.instance();
		}

	}

	/**
	 * Get the {@link ClientOptions} currently in effect.
	 *
	 * @return the client options
	 */
	public final ClientOptions getOptions() {
		return this.options;
	}

	@Override
	public final Mono<? extends NettyState> newHandler(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		return newHandler(handler, options.remoteAddress(), sslContext != null, null);
	}

	@Override
	public String toString() {
		return "TcpClient:" + options.remoteAddress().toString();
	}

	@SuppressWarnings("unchecked")
	protected Mono<NettyState> newHandler(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
			InetSocketAddress address,
			boolean secure,
			Consumer<? super Channel> onSetup) {

		final BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>>
				targetHandler = null == handler ? NettyOperations.noopHandler() : handler;

		return Mono.create(sink -> {
			Bootstrap bootstrap = new Bootstrap();

			resolver(bootstrap);
			options(bootstrap);
			handler(bootstrap, targetHandler, secure, sink, onSetup);
			Cancellation onCancelGroups = channel(bootstrap);
			Cancellation onCancelClient = connect(bootstrap, address, sink, onCancelGroups);

			sink.setCancellation(onCancelClient);
		});
	}

	/**
	 * Current {@link LoggingHandler}
	 *
	 * @return a {@link LoggingHandler}
	 */
	protected LoggingHandler logger() {
		return loggingHandler;
	}

	/**
	 * Triggered when {@link Channel} has been fully setup by the client to bind
	 * handler
	 * state.
	 *
	 * @param handler the user-provided handler on in/out
	 * @param ch the configured {@link Channel}
	 * @param sink the user-facing {@link MonoSink}
	 */
	protected void onSetup(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
			SocketChannel ch,
			MonoSink<NettyState> sink) {
		NettyOperations.bind(ch, handler, sink);
	}

	/**
	 * Set current {@link Bootstrap} channel and group
	 *
	 * @param bootstrap {@link Bootstrap}
	 *
	 * @return a {@link Cancellation} that shutdown the eventLoopGroup on dispose
	 */
	protected Cancellation channel(Bootstrap bootstrap) {
		final EventLoopGroup elg;
		final Cancellation onCancel;
		if (null != options.eventLoopGroup()) {
			elg = options.eventLoopGroup();
			onCancel = null;
		}
		else {
			elg = new ColocatedEventLoopGroup(channelAdapter.newEventLoopGroup(
					NettyConnector.DEFAULT_IO_THREAD_COUNT,
					(Runnable r) -> {
						Thread t = new Thread(r,
								"reactor-tcp-client-io-" + COUNTER.incrementAndGet());
						t.setDaemon(options.daemon());
						return t;
					}));

			onCancel = elg::shutdownGracefully;
		}
		bootstrap.group(elg)
		         .channel(channelAdapter.getChannel(elg));
		return onCancel;
	}

	/**
	 * Connect to the remote address with the configured {@link Bootstrap}
	 *
	 * @param bootstrap current {@link Bootstrap}
	 * @param address remote {@link InetSocketAddress}
	 * @param sink the {@link MonoSink} to bind connection termination on terminated or
	 * failure
	 * @param onClose close callback
	 *
	 * @return a {@link Cancellation} that terminate the connection on dispose
	 */
	protected Cancellation connect(Bootstrap bootstrap,
			InetSocketAddress address,
			MonoSink<NettyState> sink,
			Cancellation onClose) {
		ChannelFuture f = bootstrap.connect(address)
		                           .addListener(new OnBindListener(sink, onClose));

		return () -> {
			try {
				f.channel()
				 .close()
				 .sync();
				if(onClose != null) {
					onClose.dispose();
				}
			}
			catch (InterruptedException e) {
				log.error("error while disposing the channel", e);
			}
		};
	}

	/**
	 * Set current {@link Bootstrap} handler
	 *
	 * @param bootstrap {@link Bootstrap}
	 * @param handler the user provided in/out handler
	 * @param secure if the connection will have secure transport
	 * @param sink the {@link MonoSink} to complete successfully or not on connection
	 * terminated
	 */
	protected void handler(Bootstrap bootstrap,
			BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
			boolean secure,
			MonoSink<NettyState> sink,
			Consumer<? super Channel> onSetup) {
		bootstrap.handler(new ClientSetup(this, sink, secure, handler, onSetup));
	}

	/**
	 * Set current {@link Bootstrap} options
	 *
	 * @param bootstrap {@link Bootstrap}
	 */
	protected void options(Bootstrap bootstrap) {
		bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
		         .option(ChannelOption.SO_RCVBUF, options.rcvbuf())
		         .option(ChannelOption.SO_SNDBUF, options.sndbuf())
		         .option(ChannelOption.AUTO_READ, false)
		         .option(ChannelOption.SO_KEEPALIVE, options.keepAlive())
		         .option(ChannelOption.SO_LINGER, options.linger())
		         .option(ChannelOption.TCP_NODELAY, options.tcpNoDelay())
		         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
				         (int) Math.min(Integer.MAX_VALUE, options.timeoutMillis()));
	}

	protected void resolver(Bootstrap bootstrap) {
		if (options.proxyType() != null) {
			bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
		}
	}

	static final class ClientSetup extends ChannelInitializer<SocketChannel> {

		final TcpClient                 parent;
		final MonoSink<NettyState>      sink;
		final Consumer<? super Channel> onSetup;
		final boolean                   secure;
		final BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>>
		                                handler;

		ClientSetup(TcpClient parent,
				MonoSink<NettyState> sink,
				boolean secure,
				BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
				Consumer<? super Channel> onSetup) {
			this.parent = parent;
			this.secure = secure;
			this.sink = sink;
			this.handler = handler;
			this.onSetup = onSetup;
		}

		@Override
		public void initChannel(final SocketChannel ch) throws Exception {
			ChannelPipeline pipeline = ch.pipeline();

			if (secure && null != parent.sslContext) {
				SslHandler sslHandler = parent.sslContext.newHandler(ch.alloc());
				sslHandler.setHandshakeTimeoutMillis(parent.options.sslHandshakeTimeoutMillis());
				if (log.isTraceEnabled()) {
					pipeline.addFirst(NettyHandlerNames.SslLoggingHandler,
							parent.logger());
					pipeline.addAfter(NettyHandlerNames.SslLoggingHandler,
							NettyHandlerNames.SslHandler,
							sslHandler);
				}
				else {
					pipeline.addFirst(NettyHandlerNames.SslHandler, sslHandler);
				}
				if (log.isDebugEnabled()) {
					pipeline.addAfter(NettyHandlerNames.SslHandler,
							NettyHandlerNames.LoggingHandler, parent.logger());
					pipeline.addAfter(NettyHandlerNames.LoggingHandler,
							NettyHandlerNames.SslReader,
							new NettySslReader(sink));
				}
				else {
					pipeline.addAfter(NettyHandlerNames.SslHandler,
							NettyHandlerNames.SslReader,
							new NettySslReader(sink));
				}

			}
			else if (log.isDebugEnabled()) {
				pipeline.addFirst(NettyHandlerNames.LoggingHandler, parent.logger());
			}

			if (parent.options.proxyType() != null) {
				ProxyHandler proxy;
				InetSocketAddress proxyAddr = parent.options.proxyAddress()
				                                            .get();
				String username = parent.options.proxyUsername();
				String password =
						username != null && parent.options.proxyPassword() != null ?
								parent.options.proxyPassword()
								              .apply(username) : null;

				switch (parent.options.proxyType()) {

					default:
					case HTTP:
						proxy = username != null && password != null ?
								new HttpProxyHandler(proxyAddr, username, password) :
								new HttpProxyHandler(proxyAddr);
						break;
					case SOCKS4:
						proxy = username != null ?
								new Socks4ProxyHandler(proxyAddr, username) :
								new Socks4ProxyHandler(proxyAddr);
						break;
					case SOCKS5:
						proxy = username != null && password != null ?
								new Socks5ProxyHandler(proxyAddr, username, password) :
								new Socks5ProxyHandler(proxyAddr);
						break;
				}
				pipeline.addFirst(NettyHandlerNames.ProxyHandler, proxy);
			}

			parent.onSetup(handler, ch, sink);

			if (onSetup != null) {
				onSetup.accept(ch);
			}

			if (null != parent.options.onStart()){
				parent.options.onStart().accept(ch);
			}
			if (null != parent.options.pipelineConfigurer()) {
				parent.options.pipelineConfigurer()
				              .accept(pipeline);
			}

		}
	}

	static final class OnBindListener implements ChannelFutureListener {

		final MonoSink<?> sink;
		final Cancellation onClose;

		OnBindListener(MonoSink<NettyState> sink, Cancellation onClose) {
			this.sink = sink;
			this.onClose = onClose;
		}

		@Override
		public void operationComplete(ChannelFuture f) throws Exception {
			if (log.isDebugEnabled()) {
				log.debug("CONNECT {} {}",
						f.isSuccess() ? "OK" : "FAILED",
						f.channel()
						 .remoteAddress());
			}
			if (onClose != null){
				f.channel().closeFuture().addListener(x -> onClose.dispose());
			}
			if (!f.isSuccess()) {
				if (f.cause() != null) {
					sink.error(f.cause());
				}
				else {
					sink.error(new IOException("error while connecting to " + f.channel()
					                                                           .remoteAddress()));
				}
			}
		}
	}

	static final Logger         log            = Loggers.getLogger(TcpClient.class);
	static final AtomicLong     COUNTER        = new AtomicLong();
	static final LoggingHandler loggingHandler = new LoggingHandler(TcpClient.class);
}
