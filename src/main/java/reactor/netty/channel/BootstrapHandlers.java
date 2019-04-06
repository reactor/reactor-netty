/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.channel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LoggingHandler;
import reactor.core.Exceptions;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty.ReactorNetty.format;

/**
 * Helper to update configuration the main {@link Bootstrap} and
 * {@link ServerBootstrap} handlers
 *
 * @author Stephane Maldini
 */
public abstract class BootstrapHandlers {

	/**
	 * Default value whether the SSL debugging on the client side will be enabled/disabled,
	 * fallback to SSL debugging disabled
	 */
	static final boolean SSL_CLIENT_DEBUG =
			Boolean.parseBoolean(System.getProperty(ReactorNetty.SSL_CLIENT_DEBUG,
			                                        "false"));

	/**
	 * Default value whether the SSL debugging on the server side will be enabled/disabled,
	 * fallback to SSL debugging disabled
	 */
	static final boolean SSL_SERVER_DEBUG =
			Boolean.parseBoolean(System.getProperty(ReactorNetty.SSL_SERVER_DEBUG,
			                                        "false"));

	/**
	 * Finalize a server bootstrap pipeline configuration by turning it into a {@link
	 * ChannelInitializer} to safely initialize each child channel.
	 *
	 * @param b a server bootstrap
	 * @param opsFactory an operation factory
	 * @param childListener a connection observer
	 */
	public static void finalizeHandler(ServerBootstrap b, ChannelOperations.OnSetup
			opsFactory, ConnectionObserver childListener) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(opsFactory, "ops");
		Objects.requireNonNull(childListener, "childListener");

		BootstrapPipelineHandler pipeline = null;
		ChannelHandler handler = b.config().childHandler();
		if (handler instanceof BootstrapPipelineHandler) {
			pipeline = (BootstrapPipelineHandler) handler;
		}

		b.childHandler(new BootstrapInitializerHandler(pipeline, opsFactory, childListener));
	}

	/**
	 * Finalize a bootstrap pipeline configuration by turning it into a
	 * {@link ChannelInitializer} to safely initialize each child channel.
	 *
	 * @param b a bootstrap
	 * @param opsFactory an operation factory
	 * @param listener a connection observer
	 *
	 */
	public static void finalizeHandler(Bootstrap b,
			ChannelOperations.OnSetup opsFactory,
			ConnectionObserver listener) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(listener, "listener");

		BootstrapPipelineHandler pipeline = null;
		ChannelHandler handler = b.config().handler();
		if (handler instanceof BootstrapPipelineHandler){
			pipeline = (BootstrapPipelineHandler) handler;
		}


		if (pipeline != null) {
			PipelineConfiguration pipelineConfiguration;
			for (int i = 0; i < pipeline.size(); i++) {
				pipelineConfiguration = pipeline.get(i);
				if (pipelineConfiguration.deferredConsumer != null) {
					pipeline.set(i,
							new PipelineConfiguration(
									pipelineConfiguration.deferredConsumer.apply(b),
									pipelineConfiguration.name));
				}
			}
		}

		b.handler(new BootstrapInitializerHandler(pipeline, opsFactory, listener));
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
				if (clazz.isInstance(aRph.deferredConsumer)) {
					return (C) aRph.deferredConsumer;
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
	public static ServerBootstrap removeConfiguration(ServerBootstrap b, String name) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(name, "name");
		if (b.config().childHandler() != null) {
			b.childHandler(removeConfiguration(b.config().childHandler(), name));
		}
		return b;
	}

	/**
	 * Remove a configuration given its unique name from the given {@link
	 * Bootstrap}
	 *
	 * @param b a bootstrap
	 * @param name a configuration name
	 */
	public static Bootstrap removeConfiguration(Bootstrap b, String name) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(name, "name");
		if (b.config().handler() != null) {
			b.handler(removeConfiguration(b.config().handler(), name));
		}
		return b;
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
		if (ops == null) {
			return ChannelOperations.OnSetup.empty(); //will not be triggered in
		}
		return ops;
	}


	/**
	 * Add a {@link ConnectionObserver} to the passed bootstrap.
	 *
	 * @param b the bootstrap to scan
	 * @param connectionObserver a new {@link ConnectionObserver}
	 */
	public static void connectionObserver(AbstractBootstrap<?, ?> b,
			ConnectionObserver connectionObserver) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(connectionObserver, "connectionObserver");
		b.option(OBSERVER_OPTION, connectionObserver);
	}

	/**
	 * Obtain and remove the current {@link ConnectionObserver} from the bootstrap.
	 *
	 * @param b the bootstrap to scan
	 *
	 * @return current {@link ConnectionObserver} or null
	 *
	 */
	@SuppressWarnings("unchecked")
	public static ConnectionObserver connectionObserver(AbstractBootstrap<?, ?> b) {
		Objects.requireNonNull(b, "bootstrap");
		ConnectionObserver obs =
				(ConnectionObserver) b.config()
				                             .options()
				                             .get(OBSERVER_OPTION);
		if (obs == null) {
			return ConnectionObserver.emptyListener(); //will not be triggered in
		}
		b.option(OBSERVER_OPTION, null);
		return obs;
	}

	/**
	 * Add a childHandler {@link ConnectionObserver} to the passed bootstrap.
	 *
	 * @param b the bootstrap to scan
	 * @param connectionObserver a new {@link ConnectionObserver}
	 */
	public static void childConnectionObserver(ServerBootstrap b,
			ConnectionObserver connectionObserver) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(connectionObserver, "connectionObserver");
		b.childOption(OBSERVER_OPTION, connectionObserver);
	}

	/**
	 * Obtain and remove the current childHandler {@link ConnectionObserver} from the
	 * bootstrap.
	 *
	 * @param b the bootstrap to scan
	 *
	 * @return current {@link ConnectionObserver} or null
	 *
	 */
	@SuppressWarnings("unchecked")
	public static ConnectionObserver childConnectionObserver(ServerBootstrap b) {
		Objects.requireNonNull(b, "bootstrap");
		ConnectionObserver obs = (ConnectionObserver) b.config()
		                                               .childOptions()
		                                               .get(OBSERVER_OPTION);
		if (obs == null) {
			return ConnectionObserver.emptyListener(); //will not be triggered in
		}
		b.childOption(OBSERVER_OPTION, null);
		return obs;
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
												BiConsumer<ConnectionObserver, ? super Channel> c) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(c, "configuration");
		b.handler(updateConfiguration(b.config().handler(), name, c));
		return b;
	}

	/**
	 * Add the configuration consumer to this {@link Bootstrap} given a unique
	 * configuration name. Configuration will be run on channel init.
	 *
	 * @param b a bootstrap
	 * @param name a configuration name
	 * @param c a deferred configuration consumer
	 * @return the mutated bootstrap
	 */
	@SuppressWarnings("unchecked")
	public static Bootstrap updateConfiguration(Bootstrap b, String name,
												Function<? super Bootstrap, ? extends BiConsumer<ConnectionObserver, ? super Channel>> c) {
		Objects.requireNonNull(b, "bootstrap");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(c, "configuration");
		b.handler(updateConfiguration(b.config().handler(), name,
				(Function<AbstractBootstrap<?, ?>, BiConsumer<ConnectionObserver, ? super Channel>>)c));
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
			BiConsumer<ConnectionObserver, ? super Channel> c) {
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
	 * @return a mutated {@link Bootstrap}
	 */
	public static Bootstrap updateLogSupport(Bootstrap b, LoggingHandler handler) {
		updateConfiguration(b, NettyPipeline.LoggingHandler, logConfiguration(handler, SSL_CLIENT_DEBUG));
		return b;
	}

	/**
	 * Configure log support for a {@link ServerBootstrap}
	 *
	 * @param b the bootstrap to setup
	 * @param handler the logging handler to setup
	 *
	 * @return a mutated {@link ServerBootstrap}
	 */
	public static ServerBootstrap updateLogSupport(ServerBootstrap b,
												   LoggingHandler handler) {
		updateConfiguration(b, NettyPipeline.LoggingHandler, logConfiguration(handler, SSL_SERVER_DEBUG));
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

	static BootstrapPipelineHandler getOrCreateInitializer(@Nullable ChannelHandler
			handler) {
		if (handler instanceof BootstrapPipelineHandler) {
			return new BootstrapPipelineHandler((BootstrapPipelineHandler) handler);
		}
		else {
			BootstrapPipelineHandler p = new BootstrapPipelineHandler(Collections.emptyList());

			if (handler != null) {
				p.add(new PipelineConfiguration((ctx, ch) -> ch.pipeline()
				                                               .addFirst(handler),
						"user"));
			}
			return p;
		}
	}

	static ChannelHandler updateConfiguration(@Nullable ChannelHandler handler,
			String name,
			BiConsumer<ConnectionObserver, ? super Channel> c) {

		BootstrapPipelineHandler p = getOrCreateInitializer(handler);

		p.add(new PipelineConfiguration(c, name));
		return p;
	}

	static ChannelHandler updateConfiguration(@Nullable ChannelHandler handler,
			String name,
			Function<AbstractBootstrap<?, ?>, BiConsumer<ConnectionObserver, ? super Channel>> c) {

		BootstrapPipelineHandler p = getOrCreateInitializer(handler);

		p.add(new PipelineConfiguration(c, name));
		return p;
	}

	static BiConsumer<ConnectionObserver, ? super Channel> logConfiguration(LoggingHandler handler, boolean debugSsl) {
		Objects.requireNonNull(handler, "loggingHandler");
		return new LoggingHandlerSupportConsumer(handler, debugSsl);
	}

	@ChannelHandler.Sharable
	static final class BootstrapInitializerHandler extends ChannelInitializer<Channel> {

		final BootstrapPipelineHandler pipeline;
		final ConnectionObserver       listener;
		final ChannelOperations.OnSetup opsFactory;

		BootstrapInitializerHandler(@Nullable BootstrapPipelineHandler pipeline,
				ChannelOperations.OnSetup opsFactory,
				ConnectionObserver listener) {
			this.pipeline = pipeline;
			this.opsFactory = opsFactory;
			this.listener = listener;
		}

		@Override
		protected void initChannel(Channel ch) {
			if (pipeline != null) {
				for (PipelineConfiguration pipelineConfiguration : pipeline) {
					pipelineConfiguration.consumer.accept(listener, ch);
				}
			}

			ChannelOperations.addReactiveBridge(ch, opsFactory, listener);

			if (log.isDebugEnabled()) {
				log.debug(format(ch, "Initialized pipeline {}"), ch.pipeline().toString());
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			ctx.fireExceptionCaught(cause);
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			ctx.fireChannelInactive();
		}
	}

	static final Logger log = Loggers.getLogger(BootstrapHandlers.class);

	static final class PipelineConfiguration {

		final BiConsumer<ConnectionObserver, ? super Channel> consumer;
		final String                                          name;

		final Function<AbstractBootstrap<?, ?>, BiConsumer<ConnectionObserver, ? super Channel>> deferredConsumer;

		PipelineConfiguration(BiConsumer<ConnectionObserver, ? super Channel> consumer, String name) {
			this.consumer = consumer;
			this.name = name;
			this.deferredConsumer = null;
		}

		PipelineConfiguration(Function<AbstractBootstrap<?, ?>, BiConsumer<ConnectionObserver, ? super Channel>> deferredConsumer, String name) {
			this.consumer = null;
			this.name = name;
			this.deferredConsumer = deferredConsumer;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			PipelineConfiguration that = (PipelineConfiguration) o;
			return Objects.equals(consumer, that.consumer) &&
					Objects.equals(name, that.name) &&
					Objects.equals(deferredConsumer, that.deferredConsumer);
		}

		@Override
		public int hashCode() {
			return Objects.hash(consumer, name, deferredConsumer);
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
		public void handlerAdded(ChannelHandlerContext ctx) {
			throw new UnsupportedOperationException("Transient handler, missing " +
					"BootstrapHandlers.finalizeHandler() call");
		}

		@Override
		public void handlerRemoved(ChannelHandlerContext ctx) {
			throw new UnsupportedOperationException("Transient handler, missing " +
					"BootstrapHandlers.finalizeHandler() call");
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			throw Exceptions.propagate(cause);
		}
	}

	BootstrapHandlers() {
	}

	static final ChannelOption<ChannelOperations.OnSetup> OPS_OPTION = ChannelOption.newInstance("ops_factory");
	static final ChannelOption<ConnectionObserver> OBSERVER_OPTION = ChannelOption.newInstance("connectionObserver");


	static final class LoggingHandlerSupportConsumer
			implements BiConsumer<ConnectionObserver, Channel> {

		final ChannelHandler handler;
		final boolean debugSsl;

		LoggingHandlerSupportConsumer(ChannelHandler handler, boolean debugSsl) {
			this.handler = handler;
			this.debugSsl = debugSsl;
		}

		@Override
		public void accept(ConnectionObserver connectionObserver, Channel channel) {
			ChannelPipeline pipeline = channel.pipeline();
			if (pipeline.get(NettyPipeline.SslHandler) != null) {
				if (debugSsl) {
					pipeline.addBefore(NettyPipeline.SslHandler,
							NettyPipeline.SslLoggingHandler,
							new LoggingHandler("reactor.netty.tcp.ssl"));
				}
				pipeline.addAfter(NettyPipeline.SslHandler,
						NettyPipeline.LoggingHandler,
						handler);
			}
			else if (pipeline.get(NettyPipeline.ProxyHandler) != null) {
				pipeline.addAfter(NettyPipeline.ProxyHandler,
						NettyPipeline.LoggingHandler,
						handler);
			}
			else {
				pipeline.addFirst(NettyPipeline.LoggingHandler, handler);
			}
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			LoggingHandlerSupportConsumer that = (LoggingHandlerSupportConsumer) o;
			return Objects.equals(handler, that.handler);
		}

		@Override
		public int hashCode() {
			return Objects.hash(handler);
		}
	}
}
