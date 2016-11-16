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

package reactor.ipc.netty.channel;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.BiConsumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import reactor.core.Cancellation;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.ChannelFutureMono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.options.NettyOptions;
import reactor.ipc.netty.options.ServerOptions;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * A one time-set channel pipeline callback and context state for clean disposing
 *
 * @param <CHANNEL> the channel type
 *
 * @author Stephane Maldini
 */
public abstract class ContextHandler<CHANNEL extends Channel>
		extends ChannelInitializer<CHANNEL> implements Cancellation {

	/**
	 * @param sink
	 * @param options
	 * @param loggingHandler
	 * @param secure
	 * @param doWithPipeline
	 * @param <CHANNEL>
	 *
	 * @return a new {@link ContextHandler} for clients
	 */
	public static <CHANNEL extends Channel> ContextHandler<CHANNEL> newClientContext(
			MonoSink<NettyContext> sink,
			ClientOptions options,
			LoggingHandler loggingHandler,
			boolean secure,
			BiConsumer<? super CHANNEL, ? super Cancellation> doWithPipeline) {
		return newClientContext(sink,
				options,
				loggingHandler,
				secure,
				null,
				doWithPipeline);
	}

	/**
	 * @param sink
	 * @param options
	 * @param loggingHandler
	 * @param secure
	 * @param doWithPipeline
	 * @param pool
	 * @param <CHANNEL>
	 *
	 * @return a new {@link ContextHandler} for clients
	 */
	public static <CHANNEL extends Channel> ContextHandler<CHANNEL> newClientContext(
			MonoSink<NettyContext> sink,
			ClientOptions options,
			LoggingHandler loggingHandler,
			boolean secure,
			ChannelPool pool,
			BiConsumer<? super CHANNEL, ? super Cancellation> doWithPipeline) {
		if (pool != null) {
			return new PooledClientContextHandler<>(doWithPipeline,
					options,
					sink,
					loggingHandler,
					secure,
					pool);
		}
		return new ClientContextHandler<>(doWithPipeline,
				options,
				sink,
				loggingHandler,
				secure);
	}

	/**
	 * @param sink
	 * @param options
	 * @param loggingHandler
	 * @param doWithPipeline
	 *
	 * @return a new {@link ContextHandler} for servers
	 */
	public static ContextHandler<Channel> newServerContext(MonoSink<NettyContext> sink,
			ServerOptions options,
			LoggingHandler loggingHandler,
			BiConsumer<? super Channel, ? super Cancellation> doWithPipeline) {
		return new ServerContextHandler(doWithPipeline, options, sink, loggingHandler);
	}

	final MonoSink<NettyContext>                            sink;
	final LoggingHandler                                    loggingHandler;
	final BiConsumer<? super CHANNEL, ? super Cancellation> doWithPipeline;
	final NettyOptions<?, ?>                                options;

	/**
	 * @param doWithPipeline
	 * @param options
	 * @param sink
	 * @param loggingHandler
	 */
	protected ContextHandler(BiConsumer<? super CHANNEL, ? super Cancellation> doWithPipeline,
			NettyOptions<?, ?> options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler) {
		this.options = options;
		this.doWithPipeline = Objects.requireNonNull(doWithPipeline, "doWithPipeline");
		this.sink = sink;
		this.loggingHandler = loggingHandler;
	}

	@Override
	public final void initChannel(final CHANNEL ch) throws Exception {
		if (options.onChannelInit() != null) {
			if (options.onChannelInit()
			           .test(ch)) {
				if (log.isDebugEnabled()) {
					log.debug("DROPPED by onChannelInit predicate {}", ch);
				}
				doDropped(ch);
				return;
			}
		}

		doPipeline(ch.pipeline());

		try {
			doWithPipeline.accept(ch, doChannelTerminated(ch));
			ch.pipeline()
			  .addLast(NettyHandlerNames.ReactiveBridge, BRIDGE);
		}
		finally {
			if (null != options.afterChannelInit()) {
				options.afterChannelInit()
				       .accept(ch);
			}
		}
	}

	/**
	 * One-time only future setter
	 *
	 * @param future the connect/bind future to associate with and cancel on dispose
	 */
	public abstract void setFuture(Future<?> future);

	/**
	 * @param channel
	 */
	protected void doStarted(Channel channel) {
		//ignore
	}

	/**
	 * @param channel
	 */
	protected void doDropped(Channel channel) {
		//ignore
	}

	/**
	 * @param pipeline
	 */
	protected abstract void doPipeline(ChannelPipeline pipeline);

	/**
	 * @param channel
	 *
	 * @return Cancellation to dispose on each remote termination
	 */
	protected Cancellation doChannelTerminated(CHANNEL channel) {
		return this;
	}

	static final class ChannelCancellation implements NettyContext {

		final Channel      c;
		final Cancellation onClose;

		ChannelCancellation(Channel c, Cancellation onClose) {
			this.c = c;
			this.onClose = onClose;
		}

		@Override
		public InetSocketAddress address() {
			if (c instanceof SocketChannel) {
				return ((SocketChannel) c).remoteAddress();
			}
			if (c instanceof ServerSocketChannel) {
				return ((ServerSocketChannel) c).localAddress();
			}
			if (c instanceof DatagramChannel) {
				return ((DatagramChannel) c).localAddress();
			}
			throw new IllegalStateException("Does not have an InetSocketAddress");
		}

		@Override
		public Channel channel() {
			return c;
		}

		@Override
		public void dispose() {
			if (onClose != null) {
				onClose.dispose();
			}
		}

		@Override
		public Mono<Void> onClose() {
			return ChannelFutureMono.from(c.closeFuture());
		}
	}

	static final Logger         log    = Loggers.getLogger(ContextHandler.class);
	static final ChannelHandler BRIDGE = new ChannelOperationsHandler();
}
