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

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
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
		return channel -> channel.pipeline()
				.addFirst(NettyPipeline.LoggingHandler, handler);
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
}
