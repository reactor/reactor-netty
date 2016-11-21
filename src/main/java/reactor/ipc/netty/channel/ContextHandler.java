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

import java.util.Objects;
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import reactor.core.Cancellation;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.options.NettyOptions;
import reactor.ipc.netty.options.ServerOptions;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * A one time-set channel pipeline callback and {@link NettyContext} state for clean disposing.
 * A {@link ContextHandler} is bound to a user-facing {@link MonoSink}
 *
 * @param <CHANNEL> the channel type
 *
 * @author Stephane Maldini
 */
public abstract class ContextHandler<CHANNEL extends Channel>
		extends ChannelInitializer<CHANNEL> implements Cancellation, NettyContext {

	/**
	 * Create a new client context
	 *
	 * @param sink
	 * @param options
	 * @param loggingHandler
	 * @param secure
	 * @param channelOpSelector
	 * @param <CHANNEL>
	 *
	 * @return a new {@link ContextHandler} for clients
	 */
	public static <CHANNEL extends Channel> ContextHandler<CHANNEL> newClientContext(
			MonoSink<NettyContext> sink,
			ClientOptions options,
			LoggingHandler loggingHandler,
			boolean secure,
			BiFunction<? super CHANNEL, ? super ContextHandler<CHANNEL>, ? extends ChannelOperations<?, ?>> channelOpSelector) {
		return newClientContext(sink,
				options,
				loggingHandler,
				secure,
				null,
				channelOpSelector);
	}

	/**
	 * Create a new client context with optional pool support
	 *
	 * @param sink
	 * @param options
	 * @param loggingHandler
	 * @param secure
	 * @param channelOpSelector
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
			BiFunction<? super CHANNEL, ? super ContextHandler<CHANNEL>, ? extends ChannelOperations<?, ?>> channelOpSelector) {
		if (pool != null) {
			return new PooledClientContextHandler<>(channelOpSelector,
					options,
					sink,
					loggingHandler,
					secure,
					pool);
		}
		return new ClientContextHandler<>(channelOpSelector,
				options,
				sink,
				loggingHandler,
				secure);
	}

	/**
	 * Create a new server context
	 *
	 * @param sink
	 * @param options
	 * @param loggingHandler
	 * @param channelOpSelector
	 *
	 * @return a new {@link ContextHandler} for servers
	 */
	public static ContextHandler<Channel> newServerContext(MonoSink<NettyContext> sink,
			ServerOptions options,
			LoggingHandler loggingHandler,
			BiFunction<? super Channel, ? super ContextHandler<Channel>, ? extends ChannelOperations<?, ?>> channelOpSelector) {
		return new ServerContextHandler(channelOpSelector, options, sink, loggingHandler);
	}

	final MonoSink<NettyContext> sink;
	final NettyOptions<?, ?>     options;
	final LoggingHandler         loggingHandler;
	final BiFunction<? super CHANNEL, ? super ContextHandler<CHANNEL>, ? extends ChannelOperations<?, ?>>
	                             channelOpSelector;

	/**
	 * @param channelOpSelector
	 * @param options
	 * @param sink
	 * @param loggingHandler
	 */
	protected ContextHandler(BiFunction<? super CHANNEL, ? super ContextHandler<CHANNEL>, ? extends ChannelOperations<?, ?>> channelOpSelector,
			NettyOptions<?, ?> options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler) {
		this.options = options;
		this.channelOpSelector =
				Objects.requireNonNull(channelOpSelector, "channelOpSelector");
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

		ChannelHandlerContext lastCtx = ch.pipeline()
		                                  .lastContext();
		if (lastCtx != null) {
			ch.attr(LAST_STATIC_TAIL_HANDLER)
			  .set(lastCtx.name());
		}

		try {
			ChannelOperations<?, ?> op = channelOpSelector.apply(ch, this);

			ch.attr(ChannelOperations.OPERATIONS_ATTRIBUTE_KEY)
			  .set(op);

			ch.pipeline()
			  .addLast(NettyHandlerNames.ReactiveBridge, BRIDGE);

			if(!ch.isOpen()){
				op.onInboundError(ABORTED);
				return;
			}
			if(!ch.isActive()){
				if (log.isDebugEnabled()) {
					log.debug("Delayed bridging, adding onChannelActive handler");
				}
				ch.pipeline().addLast(new ChannelInboundHandlerAdapter(){
					@Override
					public void channelActive(ChannelHandlerContext ctx)
							throws Exception {
						ctx.pipeline().remove(this);
						op.onChannelActive(ch.pipeline()
						                     .context(NettyHandlerNames.ReactiveBridge));
						ctx.fireChannelActive();
					}

					@Override
					public void channelInactive(ChannelHandlerContext ctx)
							throws Exception {
						if(ctx.pipeline().context(this) != null) {
							ctx.pipeline()
							   .remove(this);
							op.onInboundError(ABORTED);
							ctx.fireChannelInactive();
						}
					}
				});
			}
			else {
				op.onChannelActive(ch.pipeline()
				                     .context(NettyHandlerNames.ReactiveBridge));
			}
		}
		catch (Exception t) {
			if (log.isErrorEnabled()) {
				log.error("Error while binding a channelOperation to {}", ch.toString());
			}
		}
		finally {
			if (null != options.afterChannelInit()) {
				options.afterChannelInit()
				       .accept(ch);
			}
		}
	}

	/**
	 * Trigger {@link MonoSink#success(Object)} that will signal
	 * {@link reactor.ipc.netty.NettyConnector#newHandler(BiFunction)} returned
	 * {@link Mono} subscriber.
	 */
	public final void fireContextActive() {
		fireContextActive(null);
	}

	/**
	 * Trigger {@link MonoSink#success(Object)} that will signal
	 * {@link reactor.ipc.netty.NettyConnector#newHandler(BiFunction)} returned
	 * {@link Mono} subscriber.
	 *
	 * @param context optional context to succeed the associated {@link MonoSink}
	 */
	public abstract void fireContextActive(NettyContext context);


	/**
	 * Trigger {@link MonoSink#error(Throwable)} that will signal
	 * {@link reactor.ipc.netty.NettyConnector#newHandler(BiFunction)} returned
	 * {@link Mono} subscriber.
	 *
	 * @param t error to fail the associated {@link MonoSink}
	 */
	public final void fireContextError(Throwable t){
		sink.error(t);
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
	 * Cleanly terminate a channel according to the current context handler type.
	 * Server might keep alive and recycle connections, pooled client will release and
	 * classic client will close.
	 *
	 * @param channel the channel to unregister
	 */
	protected void terminateChannel(Channel channel) {
		dispose();
	}

	/**
	 * clean all handler until marked tail
	 *
	 * @param channel the target channel to cleanup
	 */
	protected static void cleanHandlers(Channel channel){
		ChannelHandlerContext ctx;
		ChannelPipeline pipeline = channel.pipeline();
		String lastHandler = channel.attr(LAST_STATIC_TAIL_HANDLER).get();

		while((ctx = pipeline.lastContext()) != null) {
			if (lastHandler != null && ctx.name()
			                              .equals(lastHandler)){
				break;
			}
			pipeline.removeLast();
		}
		pipeline.addLast(NettyHandlerNames.ReactiveBridge, BRIDGE);
	}

	static final IllegalStateException ABORTED =
			new IllegalStateException("Connection " + "has been aborted by the remote " +
					"peer"){
				@Override
				public synchronized Throwable fillInStackTrace() {
					return this;
				}
			};

	static final Logger               log                      =
			Loggers.getLogger(ContextHandler.class);
	static final ChannelHandler       BRIDGE                   =
			new ChannelOperationsHandler();
	static final AttributeKey<String> LAST_STATIC_TAIL_HANDLER =
			AttributeKey.valueOf("staticTailHandler");
}
