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
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.options.NettyOptions;
import reactor.ipc.netty.options.ServerOptions;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * A one time-set channel pipeline callback to emit {@link NettyContext} state for clean
 * disposing. A {@link ContextHandler} is bound to a user-facing {@link MonoSink}
 *
 * @param <CHANNEL> the channel type
 *
 * @author Stephane Maldini
 */
public abstract class ContextHandler<CHANNEL extends Channel>
		extends ChannelInitializer<CHANNEL> implements Disposable {

	/**
	 * Create a new client context
	 *
	 * @param sink
	 * @param options
	 * @param loggingHandler
	 * @param secure
	 * @param channelOpFactory
	 * @param <CHANNEL>
	 *
	 * @return a new {@link ContextHandler} for clients
	 */
	public static <CHANNEL extends Channel> ContextHandler<CHANNEL> newClientContext(
			MonoSink<NettyContext> sink,
			ClientOptions options,
			LoggingHandler loggingHandler,
			boolean secure, ChannelOperations.OnNew<CHANNEL> channelOpFactory) {
		return newClientContext(sink,
				options,
				loggingHandler,
				secure,
				null,
				channelOpFactory);
	}

	/**
	 * Create a new client context with optional pool support
	 *
	 * @param sink
	 * @param options
	 * @param loggingHandler
	 * @param secure
	 * @param channelOpFactory
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
			ChannelPool pool, ChannelOperations.OnNew<CHANNEL> channelOpFactory) {
		if (pool != null) {
			return new PooledClientContextHandler<>(channelOpFactory,
					options,
					sink,
					loggingHandler,
					secure,
					pool);
		}
		return new ClientContextHandler<>(channelOpFactory,
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
	 * @param channelOpFactory
	 *
	 * @return a new {@link ContextHandler} for servers
	 */
	public static ContextHandler<Channel> newServerContext(MonoSink<NettyContext> sink,
			ServerOptions options,
			LoggingHandler loggingHandler,
			ChannelOperations.OnNew<Channel> channelOpFactory) {
		return new ServerContextHandler(channelOpFactory, options, sink, loggingHandler);
	}

	final MonoSink<NettyContext>           sink;
	final NettyOptions<?, ?>               options;
	final LoggingHandler                   loggingHandler;
	final ChannelOperations.OnNew<CHANNEL> channelOpFactory;

	BiConsumer<ChannelPipeline, ContextHandler<Channel>> pipelineConfigurator;
	boolean                                              fired;
	boolean                                              autoCreateOperations;

	/**
	 * @param channelOpFactory
	 * @param options
	 * @param sink
	 * @param loggingHandler
	 */
	@SuppressWarnings("unchecked")
	protected ContextHandler(ChannelOperations.OnNew<CHANNEL> channelOpFactory,
			NettyOptions<?, ?> options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler) {
		this.options = options;
		this.channelOpFactory =
				Objects.requireNonNull(channelOpFactory, "channelOpFactory");
		this.sink = sink;
		this.loggingHandler = loggingHandler;
		this.autoCreateOperations = true;
	}

	/**
	 * Setup protocol specific handlers such as HTTP codec. Should be called before
	 * binding the context to any channel or bootstrap.
	 *
	 * @param pipelineConfigurator a configurator for extra codecs in the {@link
	 * ChannelPipeline}
	 *
	 * @return this context
	 */
	public final ContextHandler<CHANNEL> onPipeline(BiConsumer<ChannelPipeline, ContextHandler<Channel>> pipelineConfigurator) {
		this.pipelineConfigurator =
				Objects.requireNonNull(pipelineConfigurator, "pipelineConfigurator");
		return this;
	}

	/**
	 * Allow the {@link ChannelOperations} to be created automatically on pipeline setup
	 *
	 * @param autoCreateOperations should auto create {@link ChannelOperations}
	 *
	 * @return this context
	 */
	public final ContextHandler<CHANNEL> autoCreateOperations(boolean autoCreateOperations) {
		this.autoCreateOperations = autoCreateOperations;
		return this;
	}

	/**
	 * Return a new {@link ChannelOperations} or null if one of the two
	 * following conditions are not met:
	 * <ul>
	 * <li>{@link #autoCreateOperations(boolean)} is true
	 * </li>
	 * <li>The passed message is not null</li>
	 * </ul>
	 *
	 * @param channel the current {@link Channel}
	 * @param msg an optional message inbound, meaning the channel has already been
	 * started before
	 *
	 * @return a new {@link ChannelOperations}
	 */
	@SuppressWarnings("unchecked")
	public final ChannelOperations<?, ?> createOperations(Channel channel, Object msg) {

		if (autoCreateOperations || msg != null) {
			ChannelOperations<?, ?> op =
					channelOpFactory.create((CHANNEL) channel, this, msg);
			channel.attr(ChannelOperations.OPERATIONS_KEY)
			       .set(op);

			channel.eventLoop().execute(op::onHandlerStart);
			return op;
		}

		return null;
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
	public void fireContextError(Throwable t) {
		if (!fired) {
			fired = true;
			sink.error(t);
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

	@Override
	protected void initChannel(CHANNEL ch) throws Exception {
		doPipeline(ch.pipeline());
		ch.pipeline()
		  .addLast(NettyPipeline.BridgeSetup, new BridgeSetupHandler(this));
		if (log.isDebugEnabled()) {
			log.debug("After pipeline {}",
					ch.pipeline()
					  .toString());
		}
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
	 * Return a Publisher to signal onComplete on {@link Channel} close or release.
	 *
	 * @param channel the channel to monitor
	 *
	 * @return a Publisher to signal onComplete on {@link Channel} close or release.
	 */
	protected abstract Publisher<Void> onCloseOrRelease(Channel channel);

	static final AbortedException ABORTED =
			new AbortedException() {
				@Override
				public synchronized Throwable fillInStackTrace() {
					return this;
				}

			};

	static final Logger         log      = Loggers.getLogger(ContextHandler.class);

	static void addSslAndLogHandlers(NettyOptions<?, ?> options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler,
			boolean secure,
			ChannelPipeline pipeline) {
		SslHandler sslHandler = secure ? options.getSslHandler(pipeline.channel()
		                                                               .alloc()) : null;
		if (sslHandler != null) {
			if (log.isDebugEnabled()) {
				log.debug("SSL enabled using engine {}",
						sslHandler.engine()
						          .getClass()
						          .getSimpleName());
			}
			if (log.isTraceEnabled()) {
				pipeline.addFirst(NettyPipeline.SslLoggingHandler, new
						LoggingHandler(SslReadHandler.class));
				pipeline.addAfter(NettyPipeline.SslLoggingHandler,
						NettyPipeline.SslHandler,
						sslHandler);
			}
			else {
				pipeline.addFirst(NettyPipeline.SslHandler, sslHandler);
			}
			if (log.isDebugEnabled()) {
				pipeline.addAfter(NettyPipeline.SslHandler,
						NettyPipeline.LoggingHandler,
						loggingHandler);
				pipeline.addAfter(NettyPipeline.LoggingHandler,
						NettyPipeline.SslReader,
						new SslReadHandler(sink));
			}
			else {
				pipeline.addAfter(NettyPipeline.SslHandler,
						NettyPipeline.SslReader,
						new SslReadHandler(sink));
			}
		}
		else if (log.isDebugEnabled()) {
			pipeline.addFirst(NettyPipeline.LoggingHandler, loggingHandler);
		}
	}

	static final class BridgeSetupHandler extends ChannelInboundHandlerAdapter {

		final ContextHandler<Channel> parent;

		boolean active;

		@SuppressWarnings("unchecked")
		BridgeSetupHandler(ContextHandler<?> parent) {
			this.parent = (ContextHandler<Channel>) parent;
		}

		@Override
		@SuppressWarnings("unchecked")
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			if (!active) {
				active = true;

				if (parent.options.onChannelInit() != null) {
					if (parent.options.onChannelInit()
					                  .test(ctx.channel())) {
						if (log.isDebugEnabled()) {
							log.debug("DROPPED by onChannelInit predicate {}",
									ctx.channel());
						}
						parent.doDropped(ctx.channel());
						return;
					}
				}

				try {
					if (parent.pipelineConfigurator != null) {
						parent.pipelineConfigurator.accept(ctx.pipeline(), parent);
					}
					ctx.pipeline()
					   .addLast(NettyPipeline.ReactiveBridge,
							   new ChannelOperationsHandler(parent));
				}
				catch (Exception t) {
					if (log.isErrorEnabled()) {
						log.error("Error while binding a channelOperation with: " + ctx.channel()
						                                                               .toString() + " on " + ctx.pipeline(),
								t);
					}
				}
				finally {
					if (null != parent.options.afterChannelInit()) {
						parent.options.afterChannelInit()
						              .accept(ctx.channel());
					}
				}
				ctx.pipeline().remove(this);
			}
			ctx.fireChannelActive();
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			if (!active) {
				ctx.pipeline().remove(this);
				parent.terminateChannel(ctx.channel());
				parent.fireContextError(ABORTED);
			}
			ctx.fireChannelInactive();
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
				throws Exception {
			if (!active) {
				if (log.isErrorEnabled()) {
					log.error("Error while binding a channelOperation with: " + ctx.channel()
					                                                               .toString(),
							cause);
				}
				ctx.pipeline().remove(this);
				parent.terminateChannel(ctx.channel());
				parent.fireContextError(cause);
			}
			ctx.fireExceptionCaught(cause);
		}
	}

	static final AttributeKey<Boolean> CLOSE_CHANNEL =
			AttributeKey.newInstance("CLOSE_CHANNEL");
}
