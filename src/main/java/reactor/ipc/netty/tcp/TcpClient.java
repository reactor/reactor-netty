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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.net.ssl.SSLException;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
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
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.Channel;
import reactor.ipc.netty.common.ChannelBridge;
import reactor.ipc.netty.common.ColocatedEventLoopGroup;
import reactor.ipc.netty.common.DuplexSocket;
import reactor.ipc.netty.common.MonoChannelFuture;
import reactor.ipc.netty.common.NettyChannel;
import reactor.ipc.netty.common.NettyChannelHandler;
import reactor.ipc.netty.common.NettyHandlerNames;
import reactor.ipc.netty.config.ClientOptions;
import reactor.ipc.netty.util.NettyNativeDetector;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * The base class for a Reactor-based TCP client.
 *
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class TcpClient extends DuplexSocket<ByteBuf, ByteBuf, NettyChannel>
		implements ChannelBridge<TcpChannel> {



	public static final Function PING = o -> Flux.empty();

	/**
	 * Bind a new TCP client to the localhost on port 12012. The default client implementation is scanned
	 * from the classpath on Class init. Support for Netty first is provided as long as the relevant
	 * library dependencies are on the classpath. <p> A {@link TcpClient} is a specific kind of {@link
	 * org.reactivestreams.Publisher} that will emit: - onNext {@link Channel} to consume data from - onComplete
	 * when client is shutdown - onError when any error (more specifically IO error) occurs From the emitted {@link
	 * Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data on the
	 * active connection, {@link Channel#send} can subscribe to any passed {@link
	 * org.reactivestreams.Publisher}. <p> Note that  read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static TcpClient create() {
		return create(DEFAULT_BIND_ADDRESS);
	}

	/**
	 * Bind a new TCP client to the specified connect address and port 12012. The default client
	 * implementation is scanned from the classpath on Class init. Support for Netty is provided
	 * as long as the relevant library dependencies are on the classpath. <p> A {@link TcpClient} is a specific kind of
	 * {@link org.reactivestreams.Publisher} that will emit: - onNext {@link Channel} to consume data from -
	 * onComplete when client is shutdown - onError when any error (more specifically IO error) occurs From the emitted
	 * {@link Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data
	 * on the active connection, {@link Channel#send} can subscribe to any passed {@link
	 * org.reactivestreams.Publisher}. <p> Note that  read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param bindAddress the address to connect to on port 12012
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static TcpClient create(String bindAddress) {
		return create(bindAddress, DEFAULT_PORT);
	}

	/**
	 * Bind a new TCP client to "loopback" on the the specified port. The default client implementation is
	 * scanned from the classpath on Class init. Support for Netty is provided as long as the
	 * relevant library dependencies are on the classpath. <p> A {@link TcpClient} is a specific kind of {@link
	 * org.reactivestreams.Publisher} that will emit: - onNext {@link Channel} to consume data from - onComplete
	 * when client is shutdown - onError when any error (more specifically IO error) occurs From the emitted {@link
	 * Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data on the
	 * active connection, {@link Channel#send} can subscribe to any passed {@link
	 * org.reactivestreams.Publisher}. <p> Note that  read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param port the port to connect to on "loopback"
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static TcpClient create(int port) {
		return create(DEFAULT_BIND_ADDRESS, port);
	}

	/**
	 * Bind a new TCP client to the specified connect address and port. The default client implementation is
	 * scanned from the classpath on Class init. Support for Netty is provided as long as the
	 * relevant library dependencies are on the classpath. <p> A {@link TcpClient} is a specific kind of {@link
	 * org.reactivestreams.Publisher} that will emit: - onNext {@link Channel} to consume data from - onComplete
	 * when client is shutdown - onError when any error (more specifically IO error) occurs From the emitted {@link
	 * Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data on the
	 * active connection, {@link Channel#send} can subscribe to any passed {@link
	 * org.reactivestreams.Publisher}. <p> Note that  read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param bindAddress the address to connect to
	 * @param port the port to connect to
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static TcpClient create(String bindAddress, int port) {
		return create(ClientOptions.to(bindAddress, port));
	}

	/**
	 * Bind a new TCP client to the specified connect address and port. The default client implementation is
	 * scanned from the classpath on Class init. Support for Netty is provided as long as the relevant library
	 * dependencies are on the classpath. <p> A {@link TcpClient} is a specific kind of {@link
	 * org.reactivestreams.Publisher} that will emit: - onNext {@link Channel} to consume data from - onComplete
	 * when client is shutdown - onError when any error (more specifically IO error) occurs From the emitted {@link
	 * Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data on the
	 * active connection, {@link Channel#send} can subscribe to any passed {@link org.reactivestreams.Publisher}.
	 * <p> Note that read will pause when capacity number of elements
	 * have been dispatched. <p> Emitted channels will run on the same thread they have beem receiving IO events.
	 * <p>
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param options
	 *
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static TcpClient create(ClientOptions options) {
		return new TcpClient(options);
	}

	final EventLoopGroup      ioGroup;
	final ClientOptions       options;
	final SslContext          sslContext;
	final NettyNativeDetector channelAdapter;

	final InetSocketAddress connectAddress;

	protected TcpClient(ClientOptions options) {
		if (null == options.remoteAddress()) {
			this.connectAddress = new InetSocketAddress("127.0.0.1", 3000);
		}
		else{
			this.connectAddress = options.remoteAddress();
		}

		this.options = options.toImmutable();

		if(options.ssl() != null){
			try{
				sslContext = options.ssl().build();

				if (log.isDebugEnabled()) {
					log.debug("Connecting with SSL enabled using context {}",
							sslContext.getClass().getSimpleName());
				}

				channelAdapter = sslContext instanceof JdkSslContext ?
						NettyNativeDetector.force(false) :
						NettyNativeDetector.instance();
			}
			catch (SSLException ssle){
				throw Exceptions.bubble(ssle);
			}
		}
		else{
			sslContext = null;
			channelAdapter = NettyNativeDetector.instance();
		}

		if (null != options.eventLoopGroup()) {
			this.ioGroup = options.eventLoopGroup();
		}
		else {
			int ioThreadCount = TcpServer.DEFAULT_TCP_THREAD_COUNT;
			this.ioGroup = new ColocatedEventLoopGroup(channelAdapter.newEventLoopGroup(ioThreadCount,
					(Runnable r) -> {
						Thread t = new Thread(r, "reactor-tcp-client-io-"+COUNTER
								.incrementAndGet());
						t.setDaemon(options.daemon());
						return t;
					}));
		}

	}

	/**
	 * Get the {@link InetSocketAddress} to which this client must connect.
	 *
	 * @return the connect address
	 */
	public InetSocketAddress getConnectAddress() {
		return connectAddress;
	}

	@Override
	public String toString() {
		return "TcpClient:" + getConnectAddress().toString();
	}

	/**
	 * Get the {@link ClientOptions} currently in effect.
	 *
	 * @return the client options
	 */
	protected ClientOptions getOptions() {
		return this.options;
	}

	@Override
	protected Mono<Void> doStart(final Function<? super NettyChannel, ? extends Publisher<Void>>
			handler){
		return doStart(handler, getConnectAddress(), this, sslContext != null);
	}

	@SuppressWarnings("unchecked")
	protected Mono<Void> doStart(final Function<? super NettyChannel, ? extends Publisher<Void>>
			handler, InetSocketAddress address, ChannelBridge<? extends
			TcpChannel> channelBridge, boolean secure) {

		final Function<? super NettyChannel, ? extends Publisher<Void>> targetHandler =
				null == handler ? (Function<? super NettyChannel, ? extends Publisher<Void>>) PING : handler;

		Bootstrap _bootstrap = new Bootstrap().group(ioGroup);

		if (options.proxyType() != null) {
			_bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
		}

		_bootstrap
		                                      .channel(channelAdapter.getChannel(
				                                      ioGroup))
		                                      .option(ChannelOption.ALLOCATOR,
				                                      PooledByteBufAllocator.DEFAULT)
		                                       .option(ChannelOption.SO_RCVBUF,
				                                      options.rcvbuf())
		                                      .option(ChannelOption.SO_SNDBUF,
				                                      options.sndbuf())
		                                      .option(ChannelOption.AUTO_READ, false)
		                                      .option(ChannelOption.SO_KEEPALIVE,
				                                      options.keepAlive())
		                                      .option(ChannelOption.SO_LINGER,
				                                      options.linger())
		                                      .option(ChannelOption.TCP_NODELAY,
				                                      options.tcpNoDelay())
		                                      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
				                                      (int) Math.min(Integer.MAX_VALUE,
						                                      options
						                                      .timeoutMillis()));
		if (!secure) {
			_bootstrap.handler(new TcpClientChannelSetup(this, null, channelBridge,
					targetHandler));
			return MonoChannelFuture.from(_bootstrap.connect(address));
		}
		else {
			DirectProcessor<Void> p = DirectProcessor.create();
			_bootstrap.handler(new TcpClientChannelSetup(this, p, channelBridge, targetHandler));
			return MonoChannelFuture.from(_bootstrap.connect(address)).flux().then(p);

		}
	}

	protected Class<?> logClass() {
		return TcpClient.class;
	}

	@Override
	protected Mono<Void> doShutdown() {

		if (getOptions() != null && getOptions().eventLoopGroup() != null) {
			return Mono.empty();
		}

		return MonoChannelFuture.from(ioGroup.shutdownGracefully());
	}

	protected void bindChannel(Function<? super NettyChannel, ? extends Publisher<Void>> handler,
			SocketChannel ch, ChannelBridge<? extends TcpChannel> channelBridge)
			throws Exception {
		ch.pipeline()
		  .addLast(NettyHandlerNames.ReactiveBridge,
				  new NettyChannelHandler<>(handler, channelBridge, ch));
	}

	@Override
	protected boolean shouldFailOnStarted() {
		return false;
	}

	protected static final Logger log = Loggers.getLogger(TcpClient.class);

	static final class TcpClientChannelSetup extends ChannelInitializer<SocketChannel> {

		final TcpClient                                      parent;
		final ChannelBridge<? extends TcpChannel>            channelBridge;
		final DirectProcessor<Void>                            secureCallback;
		final Function<? super NettyChannel, ? extends Publisher<Void>> targetHandler;

		TcpClientChannelSetup(TcpClient parent,
				DirectProcessor<Void> secureCallback,
				ChannelBridge<? extends TcpChannel> channelBridge,
				Function<? super NettyChannel, ? extends Publisher<Void>> targetHandler) {
			this.parent = parent;
			this.secureCallback = secureCallback;
			this.channelBridge = channelBridge;
			this.targetHandler = targetHandler;
		}

		@Override
		public void initChannel(final SocketChannel ch) throws Exception {
			ChannelPipeline pipeline = ch.pipeline();

			if (secureCallback != null && null != parent.sslContext) {
				SslHandler sslHandler = parent.sslContext.newHandler(ch.alloc());
				sslHandler.setHandshakeTimeoutMillis(parent.options.sslHandshakeTimeoutMillis());
				if (log.isTraceEnabled()) {
					pipeline.addFirst(NettyHandlerNames.SslLoggingHandler,
							new LoggingHandler(parent.logClass()));
					pipeline.addAfter(NettyHandlerNames.SslLoggingHandler,
							NettyHandlerNames.SslHandler,
							sslHandler);
				}
				else {
					pipeline.addFirst(NettyHandlerNames.SslHandler,
							sslHandler);
				}
				if (log.isDebugEnabled()) {
					pipeline.addAfter(NettyHandlerNames.SslHandler,
							NettyHandlerNames.LoggingHandler,
							new LoggingHandler(parent.logClass()));
					pipeline.addAfter(NettyHandlerNames.LoggingHandler,
							NettyHandlerNames.SslReader,
							new NettySslReader(secureCallback));
				}
				else {
					pipeline.addAfter(NettyHandlerNames.SslHandler,
							NettyHandlerNames.SslReader,
							new NettySslReader(secureCallback));
				}

			}
			else if (log.isDebugEnabled()) {
				pipeline.addFirst(NettyHandlerNames.LoggingHandler,
						new LoggingHandler(parent.logClass()));
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

			if (null != parent.options.pipelineConfigurer()) {
				parent.options.pipelineConfigurer()
				              .accept(pipeline);
			}

			parent.bindChannel(targetHandler, ch, channelBridge);
		}
	}

	@Override
	public TcpChannel createChannelBridge(io.netty.channel.Channel ioChannel,
			Flux<Object> input,
			Object... parameters) {
		return new TcpChannel(ioChannel, input);
	}

	static final AtomicLong COUNTER = new AtomicLong();
}
