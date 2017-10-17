/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.options.NettyOptions;
import reactor.ipc.netty.options.ServerOptions;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.function.Tuple2;

/**
 * A one time-set channel pipeline callback to emit {@link Connection} state for clean
 * disposing. A {@link ContextHandler} is bound to a user-facing {@link MonoSink}
 *
 * @param <CHANNEL> the channel type
 *
 * @author Stephane Maldini
 */
public abstract class ContextHandler<CHANNEL extends Channel>
		extends ChannelInitializer<CHANNEL> implements Disposable, Consumer<Channel> {

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
			MonoSink<Connection> sink,
			ClientOptions options,
			LoggingHandler loggingHandler,
			boolean secure,
			SocketAddress providedAddress,
			ChannelOperations.OnNew<CHANNEL> channelOpFactory) {
		return newClientContext(sink,
				options,
				loggingHandler,
				secure,
				providedAddress,
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
	 * @param providedAddress
	 * @param channelOpFactory
	 * @param pool
	 * @param <CHANNEL>
	 *
	 * @return a new {@link ContextHandler} for clients
	 */
	public static <CHANNEL extends Channel> ContextHandler<CHANNEL> newClientContext(
			MonoSink<Connection> sink,
			ClientOptions options,
			LoggingHandler loggingHandler,
			boolean secure,
			SocketAddress providedAddress,
			ChannelPool pool, ChannelOperations.OnNew<CHANNEL> channelOpFactory) {
		if (pool != null) {
			return new PooledClientContextHandler<>(channelOpFactory,
					options,
					sink,
					loggingHandler,
					secure,
					providedAddress,
					pool);
		}
		return new ClientContextHandler<>(channelOpFactory,
				options,
				sink,
				loggingHandler,
				secure,
				providedAddress);
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
	public static ContextHandler<Channel> newServerContext(MonoSink<Connection> sink,
			ServerOptions options,
			LoggingHandler loggingHandler,
			ChannelOperations.OnNew<Channel> channelOpFactory) {
		return new ServerContextHandler(channelOpFactory, options, sink, loggingHandler, options.getAddress());
	}

	final MonoSink<Connection>             sink;
	final NettyOptions<?, ?>               options;
	final LoggingHandler                   loggingHandler;
	final SocketAddress                    providedAddress;
	final ChannelOperations.OnNew<CHANNEL> channelOpFactory;

	BiConsumer<ChannelPipeline, ContextHandler<Channel>> pipelineConfigurator;
	boolean                                              fired;
	boolean                                              autoCreateOperations;

	/**
	 * @param channelOpFactory
	 * @param options
	 * @param sink
	 * @param loggingHandler
	 * @param providedAddress the {@link InetSocketAddress} targeted by the operation
	 * associated with that handler (useable eg. for SNI), or null if unavailable.
	 */
	@SuppressWarnings("unchecked")
	protected ContextHandler(ChannelOperations.OnNew<CHANNEL> channelOpFactory,
			NettyOptions<?, ?> options,
			MonoSink<Connection> sink,
			LoggingHandler loggingHandler,
			SocketAddress providedAddress) {
		this.channelOpFactory =
				Objects.requireNonNull(channelOpFactory, "channelOpFactory");
		this.options = options;
		this.sink = sink;
		this.loggingHandler = loggingHandler;
		this.autoCreateOperations = true;
		this.providedAddress = providedAddress;

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
	public final ChannelOperations<?, ?> createOperations(Channel channel, @Nullable Object msg) {

		if (autoCreateOperations || msg != null) {
			ChannelOperations<?, ?> op =
					channelOpFactory.create((CHANNEL) channel, this, msg);

			if (op != null) {
				ChannelOperations old = ChannelOperations.tryGetAndSet(channel, op);

				if (old != null) {
					if (log.isDebugEnabled()) {
						log.debug(channel.toString() + "Mixed pooled connection " + "operations between " + op + " - and a previous one " + old);
					}
					return null;
				}

				if (this.options.afterNettyContextInit() != null) {
					try {
						this.options.afterNettyContextInit().accept(op);
					}
					catch (Throwable t) {
						log.error("Could not apply afterNettyContextInit callback {}", t.toString());
					}
				}

				channel.pipeline()
				       .get(ChannelOperationsHandler.class).lastContext = this;

				channel.eventLoop().execute(op::onHandlerStart);
			}
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
	public abstract void fireContextActive(Connection context);

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
		else if (AbortedException.isConnectionReset(t)) {
			if (log.isDebugEnabled()) {
				log.error("Connection closed remotely", t);
			}
		}
		else {
			log.error("Error cannot be forwarded to user-facing Mono", t);
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
		accept(ch);
	}

	/**
	 * Initialize pipeline
	 *
	 * @param ch channel to initialize
	 */
	protected abstract void doPipeline(Channel ch);

	@Override
	@SuppressWarnings("unchecked")
	public void accept(Channel channel) {
		doPipeline(channel);
		if (options.onChannelInit() != null) {
			if (options.onChannelInit()
			           .test(channel)) {
				if (log.isDebugEnabled()) {
					log.debug("DROPPED by onChannelInit predicate {}", channel);
				}
				doDropped(channel);
				return;
			}
		}

		try {
			if (pipelineConfigurator != null) {
				pipelineConfigurator.accept(channel.pipeline(),
						(ContextHandler<Channel>) this);
			}
			channel.pipeline()
			       .addLast(NettyPipeline.ReactiveBridge,
					       new ChannelOperationsHandler(this));
		}
		catch (Exception t) {
			if (log.isErrorEnabled()) {
				log.error("Error while binding a channelOperation with: " + channel
								.toString() + " on " + channel.pipeline(),
						t);
			}
		}
		finally {
			if (null != options.afterChannelInit()) {
				options.afterChannelInit()
				       .accept(channel);
			}
		}
		if (log.isDebugEnabled()) {
			log.debug("After pipeline {}",
					channel.pipeline()
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
	 * Cleanly terminate a channel according to the current context handler type.
	 * Server might keep alive and recycle connections, pooled client will release and
	 * classic client will close.
	 *
	 * @param channel the channel to unregister
	 */
	protected void terminateChannel(Channel channel) {
		dispose();
	}

	protected Tuple2<String, Integer> getSNI() {
		return null; //will ignore SNI
	}

	/**
	 * Return a Publisher to signal onComplete on {@link Channel} close or release.
	 *
	 * @param channel the channel to monitor
	 *
	 * @return a Publisher to signal onComplete on {@link Channel} close or release.
	 */
	protected abstract Publisher<Void> onCloseOrRelease(Channel channel);

	static final Logger         log      = Loggers.getLogger(ContextHandler.class);

	static void addSslAndLogHandlers(NettyOptions<?, ?> options,
			ContextHandler<?> sink,
			LoggingHandler loggingHandler,
			boolean secure,
			Tuple2<String, Integer> sniInfo,
			ChannelPipeline pipeline) {
		SslHandler sslHandler = secure
				? options.getSslHandler(pipeline.channel().alloc(), sniInfo)
				: null;

		if (sslHandler != null) {
			if (log.isDebugEnabled() && sniInfo != null) {
				log.debug("SSL enabled using engine {} and SNI {}",
						sslHandler.engine()
						          .getClass()
						          .getSimpleName(),
						sniInfo);
			}
			else if (log.isDebugEnabled()) {
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
}
