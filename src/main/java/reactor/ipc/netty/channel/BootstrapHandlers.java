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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import reactor.core.Exceptions;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionEvents;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Helper to update configuration the main {@link Bootstrap} and
 * {@link ServerBootstrap} handlers
 *
 * @author Stephane Maldini
 */
public abstract class BootstrapHandlers {

	/**
	 * Finalize a server bootstrap pipeline configuration by turning it into a {@link
	 * ChannelInitializer} to safely initialize each child channel.
	 *
	 * @param b a server bootstrap
	 * @param ops a connection factory
	 * @param sink a server mono callback
	 * @return a listener for deferred or bind()
	 */
	public static Consumer<Future<?>> finalize(ServerBootstrap b,
			ChannelOperations.OnSetup ops,
			MonoSink<DisposableServer> sink) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(ops, "ops");
		Objects.requireNonNull(sink, "sink");

		DisposableBind disposableServer = new DisposableBind(sink, ops);

		BootstrapPipelineHandler pipeline = null;
		ChannelHandler handler = b.config().childHandler();
		if (handler instanceof BootstrapPipelineHandler) {
			pipeline = (BootstrapPipelineHandler) handler;
		}

		b.childHandler(new BootstrapInitializerHandler(pipeline, disposableServer));

		return disposableServer;
	}

	/**
	 * Finalize a bootstrap pipeline configuration by turning it into a
	 * {@link ChannelInitializer} to safely initialize each child channel.
	 *
	 * @param b a bootstrap
	 * @param ops a connection factory
	 * @param sink a client mono callback
	 *
	 * @return a listener for deferred connect() or bind()
	 */
	public static Consumer<Future<?>> finalize(Bootstrap b,
			ChannelOperations.OnSetup ops,
			MonoSink<Connection> sink) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(ops, "ops");
		Objects.requireNonNull(sink, "sink");

		DisposableConnect disposableConnect =
				new DisposableConnect(sink, ops);

		BootstrapPipelineHandler pipeline = null;
		ChannelHandler handler = b.config().handler();
		if (handler instanceof BootstrapPipelineHandler){
			pipeline = (BootstrapPipelineHandler) handler;
		}

		b.handler(new BootstrapInitializerHandler(pipeline, disposableConnect));

		return disposableConnect;
	}

	/**
	 * Finalize a bootstrap pipeline configuration by turning it into a {@link
	 * ChannelInitializer} to safely initialize each child channel.
	 *
	 * @param b a bootstrap
	 * @param ops a connection factory
	 * @param sink a client mono callback
	 * @param pool a channel pool
	 * @return a listener for deferred connect() or bind()
	 */
	public static Consumer<Future<?>> finalize(Bootstrap b,
			ChannelOperations.OnSetup ops,
			MonoSink<Connection> sink,
			ChannelPool pool) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(ops, "ops");
		Objects.requireNonNull(pool, "pool");

		DisposableAcquire disposableAcquire =
				new DisposableAcquire(sink, ops, pool);

		BootstrapPipelineHandler pipeline = null;
		ChannelHandler handler = b.config()
		                          .handler();
		if (handler instanceof BootstrapPipelineHandler) {
			pipeline = (BootstrapPipelineHandler) handler;
		}

		b.handler(new BootstrapInitializerHandler(pipeline, disposableAcquire));

		return disposableAcquire;
	}

	/**
	 * Find the given typed configuration consumer or return null;
	 *
	 * @param clazz the type of configuration to find
	 * @param handler optional handler to scan
	 * @param <C> configuration consumer type
	 *
	 * @return a typed configuration or null
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	public static <C> C findConfiguration(Class<C> clazz,
										  @Nullable ChannelHandler handler) {
		Objects.requireNonNull(clazz, "configuration type");
		if (handler instanceof BootstrapPipelineHandler) {
			BootstrapPipelineHandler rph =
					(BootstrapPipelineHandler) handler;
			for (PipelineConfiguration aRph : rph) {
				if (clazz.isInstance(aRph.consumer)) {
					return (C) aRph.consumer;
				}
			}
		}
		return null;
	}

	/**
	 * Remove a configuration given its unique name from the given {@link
	 * ServerBootstrap}
	 *
	 * @param b a server bootstrap
	 * @param name a configuration name
	 */
	public static void removeConfiguration(ServerBootstrap b, String name) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(name, "name");
		b.childHandler(removeConfiguration(b.config().childHandler(), name));
	}

	/**
	 * Remove a configuration given its unique name from the given {@link
	 * Bootstrap}
	 *
	 * @param b a bootstrap
	 * @param name a configuration name
	 */
	public static void removeConfiguration(Bootstrap b, String name) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(name, "name");
		if (b.config().handler() == null) {
			return;
		}
		b.handler(removeConfiguration(b.config().handler(), name));
	}

	/**
	 * Set a {@link ChannelOperations.OnSetup} to the passed bootstrap.
	 *
	 * @param b the bootstrap to scan
	 * @param opsFactory a new {@link ChannelOperations.OnSetup} factory
	 */
	public static void channelOperationFactory(AbstractBootstrap<?, ?> b,
			ChannelOperations.OnSetup opsFactory) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(opsFactory, "opsFactory");
		b.option(OPS_OPTION, opsFactory);
	}

	/**
	 * Obtain and remove the current {@link ChannelOperations.OnSetup} from the bootstrap.
	 *
	 * @param b the bootstrap to scan
	 *
	 * @return current {@link ChannelOperations.OnSetup} factory or null
	 *
	 */
	@SuppressWarnings("unchecked")
	public static ChannelOperations.OnSetup channelOperationFactory(AbstractBootstrap<?, ?> b) {
		Objects.requireNonNull(b, "bootstrap");
		ChannelOperations.OnSetup ops =
				(ChannelOperations.OnSetup) b.config()
				                             .options()
				                             .get(OPS_OPTION);
		b.option(OPS_OPTION, null);
		return ops;
	}

	/**
	 * Add the configuration consumer to this {@link Bootstrap} given a unique
	 * configuration name. Configuration will be run on channel init.
	 *
	 * @param b a bootstrap
	 * @param name a configuration name
	 * @param c a configuration consumer
	 * @return the mutated bootstrap
	 */
	public static Bootstrap updateConfiguration(Bootstrap b, String name,
												BiConsumer<ConnectionEvents, ? super Channel> c) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(c, "configuration");
		b.handler(updateConfiguration(b.config().handler(), name, c));
		return b;
	}

	/**
	 * Add the configuration consumer to this {@link ServerBootstrap} given a unique
	 * configuration name. Configuration will be run on child channel init.
	 *
	 * @param b a server bootstrap
	 * @param name a configuration name
	 * @param c a configuration consumer
	 * @return the mutated bootstrap
	 */
	public static ServerBootstrap updateConfiguration(ServerBootstrap b,
			String name,
			BiConsumer<ConnectionEvents, ? super Channel> c) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(c, "configuration");
		b.childHandler(updateConfiguration(b.config().childHandler(), name, c));
		return b;
	}

	/**
	 * Configure log support for a {@link Bootstrap}
	 *
	 * @param b the bootstrap to setup
	 * @param handler the logging handler to setup
	 *
	 * @return a mutated {@link Bootstrap#handler}
	 */
	public static Bootstrap updateLogSupport(Bootstrap b, LoggingHandler handler) {
		updateConfiguration(b, NettyPipeline.LoggingHandler, logConfiguration(handler));
		return b;
	}

	/**
	 * Configure log support for a {@link ServerBootstrap}
	 *
	 * @param b the bootstrap to setup
	 * @param handler the logging handler to setup
	 *
	 * @return a mutated {@link ServerBootstrap#childHandler}
	 */
	public static ServerBootstrap updateLogSupport(ServerBootstrap b,
												   LoggingHandler handler) {
		updateConfiguration(b, NettyPipeline.LoggingHandler, logConfiguration(handler));
		return b;
	}

	static ChannelHandler removeConfiguration(ChannelHandler handler, String name) {
		if (handler instanceof BootstrapPipelineHandler) {
			BootstrapPipelineHandler rph =
					new BootstrapPipelineHandler((BootstrapPipelineHandler) handler);

			for (int i = 0; i < rph.size(); i++) {
				if (rph.get(i).name.equals(name)) {
					rph.remove(i);
					return rph;
				}
			}
		}
		return handler;
	}

	static ChannelHandler updateConfiguration(@Nullable ChannelHandler handler,
			String name,
											  BiConsumer<ConnectionEvents, ? super Channel> c) {

		BootstrapPipelineHandler p;

		if (handler instanceof BootstrapPipelineHandler) {
			p = new BootstrapPipelineHandler((BootstrapPipelineHandler) handler);
		}
		else {
			p = new BootstrapPipelineHandler(Collections.emptyList());

			if (handler != null) {
				p.add(new PipelineConfiguration((ctx, ch) -> ch.pipeline()
						.addFirst(handler),
						"user"));
			}
		}

		p.add(new PipelineConfiguration(c, name));
		return p;
	}

	static BiConsumer<ConnectionEvents, ? super Channel> logConfiguration(LoggingHandler handler) {
		Objects.requireNonNull(handler, "loggingHandler");
		return (listener, channel) -> {
			if (channel.pipeline().get(NettyPipeline.SslHandler) != null) {
				if (log.isTraceEnabled()) {
					channel.pipeline()
							.addBefore(NettyPipeline.SslHandler,
									NettyPipeline.SslLoggingHandler,
									new LoggingHandler("reactor.ipc.netty.tcp.ssl"));
				}
				channel.pipeline()
						.addAfter(NettyPipeline.SslHandler,
						          NettyPipeline.LoggingHandler,
						          handler);
			}
			else {
				channel.pipeline()
						.addFirst(NettyPipeline.LoggingHandler, handler);
			}
		};
	}

	@ChannelHandler.Sharable
	static final class BootstrapInitializerHandler extends ChannelInitializer<Channel> {

		final BootstrapPipelineHandler pipeline;
		final ConnectionEvents         listener;

		BootstrapInitializerHandler(@Nullable BootstrapPipelineHandler pipeline,
				ConnectionEvents listener) {
			this.pipeline = pipeline;
			this.listener = listener;
		}

		@Override
		protected void initChannel(Channel ch) throws Exception {
			if (pipeline != null) {
				for (PipelineConfiguration pipelineConfiguration : pipeline) {
					pipelineConfiguration.consumer.accept(listener, ch);
				}
			}
			ch.pipeline()
			  .addLast(NettyPipeline.ReactiveBridge, new ChannelOperationsHandler(listener));

			if (log.isDebugEnabled()) {
				log.debug("{} Initialized pipeline {}", ch, ch.pipeline().toString());
			}
		}
	}

	static final Logger log = Loggers.getLogger(BootstrapHandlers.class);

	static final class PipelineConfiguration {

		final BiConsumer<ConnectionEvents, ? super Channel> consumer;
		final String                                        name;

		PipelineConfiguration(BiConsumer<ConnectionEvents, ? super Channel> consumer, String name) {
			this.consumer = consumer;
			this.name = name;
		}

	}

	static final class BootstrapPipelineHandler extends ArrayList<PipelineConfiguration>
			implements ChannelHandler {

		BootstrapPipelineHandler(Collection<? extends PipelineConfiguration> c) {
			super(c);
		}

		@Override
		public boolean add(PipelineConfiguration consumer) {
			for (int i = 0; i < size(); i++) {
				if (get(i).name.equals(consumer.name)) {
					set(i, consumer);
					return true;
				}
			}
			return super.add(consumer);
		}

		@Override
		public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
			throw new UnsupportedOperationException("Transient handler, missing " +
					"BootstrapHandlers.finalize() call");
		}

		@Override
		public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
			throw new UnsupportedOperationException("Transient handler, missing " +
					"BootstrapHandlers.finalize() call");
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
				throws Exception {
			throw Exceptions.propagate(cause);
		}
	}

	BootstrapHandlers() {
	}

	final static ChannelOption<ChannelOperations.OnSetup> OPS_OPTION =
			ChannelOption.newInstance("ops_factory");

	final static ChannelOption<BiConsumer<Connection, Object>> ON_PROTOCOL_EVENT =
			ChannelOption.newInstance("on_protocol_event");
}
