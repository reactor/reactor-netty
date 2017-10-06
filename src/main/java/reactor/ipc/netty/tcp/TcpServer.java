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

package reactor.ipc.netty.tcp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.NetUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.options.NettyOptions;
import reactor.ipc.netty.options.ServerOptions;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * * A TCP server connector.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public class TcpServer implements NettyConnector<NettyInbound, NettyOutbound> {

	private static final int DEFAULT_PORT_FOR_CREATE = 0;

	/**
	 * Bind a new TCP server to "localhost" on a randomly assigned port.
	 * <p> The assigned port can be found once a handler has been bound, using
	 * {@link Connection#address()} and its {@code getPort()} method.
	 * <p> Handlers will run on the same thread they have been receiving IO events.
	 * The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create() {
		return builder().listenAddress(new InetSocketAddress(NetUtil.LOCALHOST, DEFAULT_PORT_FOR_CREATE)).build();
	}

	/**
	 * Bind a new TCP server to the bind address and port provided through the options.
	 * Use {@literal 0} to let the system assign a random port, or
	 * {@link NettyOptions#DEFAULT_PORT} once to use a global default port.
	 * <p> Handlers will run on the same thread they have been receiving IO events.
	 * The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param options {@link ServerOptions} configuration input
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create(Consumer<? super ServerOptions.Builder<?>> options) {
		return builder().options(options).build();
	}

	/**
	 * Bind a new TCP server to "localhost" on the given port. Use {@literal 0} to let
	 * the system assign a random port, or {@link NettyOptions#DEFAULT_PORT} once to use
	 * a global default port.
	 * <p> Handlers will run on the same thread they have been receiving IO events.
	 * The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param port the port to listen on loopback
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create(int port) {
		return builder().listenAddress(new InetSocketAddress(port)).build();
	}

	/**
	 * Bind a new TCP server to the given bind address on a randomly assigned port.
	 * <p> The assigned port can be found once a handler has been bound, using
	 * {@link Connection#address()} and its {@code getPort()} method.
	 * <p> Handlers will run on the same thread they have been receiving IO events.
	 * The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the
	 * default port
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create(String bindAddress) {
		return create(bindAddress, DEFAULT_PORT_FOR_CREATE);
	}

	/**
	 * Bind a new TCP server to the given bind address and port. Use {@literal 0} to let
	 * the system assign a random port, or {@link NettyOptions#DEFAULT_PORT} once to use
	 * a global default port.
	 * <p> Handlers will run on the same thread they have been receiving IO events.
	 * The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the
	 * passed port
	 * @param port the port to listen on the passed bind address
	 * @return a new {@link TcpServer}
	 */
	public static TcpServer create(String bindAddress, int port) {
		return builder().bindAddress(bindAddress).port(port).build();
	}

	/**
	 * Creates a builder for {@link TcpServer TcpServer}
	 *
	 * @return a new TcpServer builder
	 */
	public static TcpServer.Builder builder() {
		return new TcpServer.Builder();
	}

	final ServerOptions options;

	protected TcpServer(TcpServer.Builder builder) {
		ServerOptions.Builder<?> serverOptionsBuilder = ServerOptions.builder();
		if (Objects.isNull(builder.options)) {
			if (Objects.isNull(builder.bindAddress)) {
				serverOptionsBuilder.listenAddress(builder.listenAddress.get());
			}
			else {
				serverOptionsBuilder.host(builder.bindAddress).port(builder.port);
			}
		}
		else {
			builder.options.accept(serverOptionsBuilder);
		}
		if (!serverOptionsBuilder.isLoopAvailable()) {
			serverOptionsBuilder.loopResources(TcpResources.get());
		}
		this.options = serverOptionsBuilder.build();
	}

	/**
	 * @param options server options
	 */
	protected TcpServer(ServerOptions options) {
		this.options = Objects.requireNonNull(options, "options");
	}

	@Override
	public final Mono<? extends Connection> newHandler(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return Mono.create(sink -> {
			ServerBootstrap b = options.get();
			SocketAddress local = options.getAddress();
			b.localAddress(local);
			ContextHandler<Channel> contextHandler = doHandler(handler, sink);
			b.childHandler(contextHandler);
			if(log.isDebugEnabled()){
				b.handler(loggingHandler());
			}
			contextHandler.setFuture(b.bind());
		});
	}

	/**
	 * Get a copy of the {@link ServerOptions} currently in effect.
	 *
	 * @return the server options
	 */
	public ServerOptions options() {
		return this.options.duplicate();
	}

	@Override
	public String toString() {
		return "TcpServer: " + options.asSimpleString();
	}

	/**
	 * Return the current logging handler for the server
	 * @return the current logging handler for the server
	 */
	protected LoggingHandler loggingHandler(){
		return loggingHandler;
	}

	/**
	 * Create a {@link ContextHandler} for {@link ServerBootstrap#childHandler()}
	 *
	 * @param handler user provided in/out handler
	 * @param sink user provided bind handler
	 *
	 * @return a new {@link ContextHandler}
	 */
	protected ContextHandler<Channel> doHandler(
			BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
			MonoSink<Connection> sink) {
		return ContextHandler.newServerContext(sink,
				options,
				loggingHandler(),
				(ch, c, msg) -> ChannelOperations.bind(ch, handler, c));
	}

	static final LoggingHandler loggingHandler = new LoggingHandler(TcpServer.class);

	static final Logger log = Loggers.getLogger(TcpServer.class);

	public static final class Builder {
		private String bindAddress = null;
		private int port = 80;
		private Supplier<InetSocketAddress> listenAddress = () -> new InetSocketAddress(NetUtil.LOCALHOST, port);
		private Consumer<? super ServerOptions.Builder<?>> options;

		private Builder() {
		}

		/**
		 * The address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
		 *
		 * @param bindAddress address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
		 * @return {@code this}
		 */
		public final Builder bindAddress(String bindAddress) {
			this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
			return this;
		}

		/**
		 * The {@link InetSocketAddress} to listen on.
		 *
		 * @param listenAddress the listen address
		 * @return {@code this}
		 */
		public final Builder listenAddress(InetSocketAddress listenAddress) {
			Objects.requireNonNull(listenAddress, "listenAddress");
			this.listenAddress = () -> listenAddress;
			return this;
		}

		/**
		 * The port to listen to, or 0 to dynamically attribute one.
		 *
		 * @param port the port to listen to, or 0 to dynamically attribute one.
		 * @return {@code this}
		 */
		public final Builder port(int port) {
			this.port = port;
			return this;
		}

		/**
		 * The options for the server, including bind address and port.
		 *
		 * @param options the options for the server, including bind address and port.
		 * @return {@code this}
		 */
		public final Builder options(Consumer<? super ServerOptions.Builder<?>> options) {
			this.options = Objects.requireNonNull(options, "options");
			return this;
		}

		public TcpServer build() {
			return new TcpServer(this);
		}
	}
}
