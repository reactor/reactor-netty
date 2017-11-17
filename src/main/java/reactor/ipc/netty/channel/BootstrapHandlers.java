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

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.handler.logging.LoggingHandler;
import reactor.core.Exceptions;
import reactor.ipc.netty.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

/**
 * Helper to update configuration the main {@link Bootstrap} and
 * {@link ServerBootstrap} handlers
 *
 * @author Stephane Maldini
 */
public abstract class BootstrapHandlers {

	/**
	 * Finalize a server bootstrap pipeline configuration by turning it into a
	 * {@link ChannelInitializer} to safely initialize each child channel.
	 *
	 * @param b a server bootstrap
	 * @param ctx a context handler
	 */
	public static void finalize(ServerBootstrap b, ContextHandler ctx) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(ctx, "ctx");

		BootstrapPipelineHandler pipeline = null;
		ChannelHandler handler = b.config().childHandler();
		if (handler instanceof BootstrapPipelineHandler) {
			pipeline = (BootstrapPipelineHandler) handler;
		}

		b.childHandler(new BootstrapInitializerHandler(pipeline, ctx));
	}

	/**
	 * Finalize a bootstrap pipeline configuration by turning it into a
	 * {@link ChannelInitializer} to safely initialize each child channel.
	 *
	 * @param b a bootstrap
	 * @param ctx a context handler
	 */
	public static void finalize(Bootstrap b, ContextHandler ctx) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(ctx, "ctx");

		BootstrapPipelineHandler pipeline = null;
		ChannelHandler handler = b.config().handler();
		if (handler instanceof BootstrapPipelineHandler){
			pipeline = (BootstrapPipelineHandler) handler;
		}

		b.handler(new BootstrapInitializerHandler(pipeline, ctx));
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
	 * Find the given typed configuration consumer or return null;
	 *
	 * @param name the name of configuration to find
	 * @param handler optional handler to scan
	 *
	 * @return a configuration or null
	 */
	@Nullable
	public static Consumer<? super Channel> findConfiguration(String name,
															  @Nullable ChannelHandler handler) {
		Objects.requireNonNull(name, "configuration type");
		if (handler instanceof BootstrapPipelineHandler) {
			BootstrapPipelineHandler rph =
					(BootstrapPipelineHandler) handler;
			for (PipelineConfiguration aRph : rph) {
				if (name.equalsIgnoreCase(aRph.name)) {
					return aRph.consumer;
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
		b.handler(removeConfiguration(b.config().handler(), name));
	}

	/**
	 * Set a {@link ChannelOperations.OnSetup} to the passed bootstrap.
	 *
	 * @param b the bootstrap to scan
	 * @param opsFactory a new {@link ChannelOperations.OnSetup} factory
	 */
	public static <CHANNEL extends Channel> void channelOperationFactory(AbstractBootstrap<?, ?> b,
	                                                                     ChannelOperations.OnSetup<CHANNEL> opsFactory) {
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
	public static <CHANNEL extends Channel> ChannelOperations.OnSetup<CHANNEL> channelOperationFactory(AbstractBootstrap<?, ?> b) {
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
	public static Bootstrap updateConfiguration(Bootstrap b,
												String name,
												Consumer<? super Channel> c) {
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
													  Consumer<? super Channel> c) {
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
											  Consumer<? super Channel> c) {

		BootstrapPipelineHandler p;

		if (handler instanceof BootstrapPipelineHandler) {
			p = new BootstrapPipelineHandler((BootstrapPipelineHandler) handler);
		}
		else {
			p = new BootstrapPipelineHandler(Collections.emptyList());

			if (handler != null) {
				p.add(new PipelineConfiguration(consumer -> consumer.pipeline()
						.addFirst(handler),
						"user"));
			}
		}

		p.add(new PipelineConfiguration(c, name));
		return p;
	}

	static Consumer<? super Channel> logConfiguration(LoggingHandler handler) {
		Objects.requireNonNull(handler, "loggingHandler");
		return channel -> {
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
		final ContextHandler           ctx;

		BootstrapInitializerHandler(@Nullable BootstrapPipelineHandler pipeline, ContextHandler ctx) {
			this.pipeline = pipeline;
			this.ctx = ctx;
		}

		@Override
		protected void initChannel(Channel ch) throws Exception {
			if (pipeline != null) {
				ch.pipeline().addLast(pipeline);
			}
			ch.pipeline()
					.addLast(NettyPipeline.ReactiveBridge, new ChannelOperationsHandler(ctx));

			if (log.isDebugEnabled()) {
				log.debug("After pipeline {}", ch.pipeline().toString());
			}
		}
	}

	static final Logger log = Loggers.getLogger(BootstrapHandlers.class);

	static final class PipelineConfiguration {

		final Consumer<? super Channel> consumer;
		final String                    name;

		PipelineConfiguration(Consumer<? super Channel> consumer, String name) {
			this.consumer = consumer;
			this.name = name;
		}

	}

	static final class BootstrapPipelineHandler extends ArrayList<PipelineConfiguration>
			implements ChannelHandler {

		boolean removed;

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
			if (removed) {
				return;
			}
			removed = true;

			for (PipelineConfiguration pipelineConfiguration : this) {
				pipelineConfiguration.consumer.accept(ctx.channel());
			}

			ctx.pipeline().remove(this);
		}

		@Override
		public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
			removed = true;
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
				throws Exception {
			throw Exceptions.propagate(cause);
		}
	}

	BootstrapHandlers() {
	}

	final static ChannelOption<ChannelOperations.OnSetup<? extends Channel>> OPS_OPTION =
			ChannelOption.newInstance("ops_factory");
}
