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

package reactor.ipc.netty.udp;

import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.NetUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.options.NettyOptions;

/**
 * A UDP server connector.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final public class UdpServer implements NettyConnector<UdpInbound, UdpOutbound> {

	/**
	 * Bind a new UDP server to the "localhost" address and {@link NettyOptions#DEFAULT_PORT port 12012}.
	 * Handlers will run on the same thread they have been receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @return a new {@link UdpServer}
	 */
	public static UdpServer create() {
		return create(NetUtil.LOCALHOST.getHostAddress());
	}

	/**
	 * Bind a new UDP server to the given bind address and {@link NettyOptions#DEFAULT_PORT port 12012}.
	 * Handlers will run on the same thread they have been receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on default port
	 * @return a new {@link UdpServer}
	 */
	public static UdpServer create(String bindAddress) {
		return create(bindAddress, NettyOptions.DEFAULT_PORT);
	}

	/**
	 * Bind a new UDP server to the "localhost" address and specified port.
	 * Handlers will run on the same thread they have been receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param port the port to listen on the localhost bind address
	 * @return a new {@link UdpServer}
	 */
	public static UdpServer create(int port) {
		return create(NetUtil.LOCALHOST.getHostAddress(), port);
	}

	/**
	 * Bind a new UDP server to the given bind address and port.
	 * Handlers will run on the same thread they have been receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the
	 * passed port
	 * @param port the port to listen on the passed bind address
	 * @return a new {@link UdpServer}
	 */
	public static UdpServer create(String bindAddress, int port) {
		return create(opts -> opts.host(bindAddress).port(port));
	}

	/**
	 * Bind a new UDP server to the bind address and port provided through the options.
	 * Handlers will run on the same thread they have been receiving IO events.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param options the configurator
	 * @return a new {@link UdpServer}
	 */
	public static UdpServer create(Consumer<? super ClientOptions.Builder<?>> options) {
		return builder().options(options).build();
	}

	/**
	 * Creates a builder for {@link UdpServer UdpServer}
	 *
	 * @return a new UdpServer builder
	 */
	public static UdpServer.Builder builder() {
		return new UdpServer.Builder();
	}

	final UdpServerOptions options;

	private UdpServer(UdpServer.Builder builder) {
		UdpServerOptions.Builder serverOptionsBuilder = UdpServerOptions.builder();
		if (Objects.nonNull(builder.options)) {
			builder.options.accept(serverOptionsBuilder);
		}
		if (!serverOptionsBuilder.isLoopAvailable()) {
			serverOptionsBuilder.loopResources(UdpResources.get());
		}
		this.options = serverOptionsBuilder.build();
	}

	@Override
	public Mono<? extends Connection> newHandler(BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler) {
		final BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>>
				targetHandler =
				null == handler ? ChannelOperations.noopHandler() : handler;

		return Mono.create(sink -> {
			Bootstrap b = options.get();
			SocketAddress adr = options.getAddress();
			if(adr == null){
				sink.error(new NullPointerException("Provided UdpServerOptions do not " +
						"define any address to bind to "));
				return;
			}
			b.localAddress(adr);
			ContextHandler<DatagramChannel> c = doHandler(targetHandler, sink, adr);
			b.handler(c);
			c.setFuture(b.bind());
		});
	}

	/**
	 * Create a {@link ContextHandler} for {@link Bootstrap#handler()}
	 *
	 * @param handler user provided in/out handler
	 * @param sink user provided bind handler
	 *
	 * @return a new {@link ContextHandler}
	 */
	protected ContextHandler<DatagramChannel> doHandler(BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler,
			MonoSink<Connection> sink,
			SocketAddress providedAddress) {
		return ContextHandler.newClientContext(sink,
				options,
				loggingHandler,
				false,
				providedAddress,
				(ch, c, msg) -> UdpOperations.bind(ch, handler, c));
	}


	static final LoggingHandler loggingHandler = new LoggingHandler(UdpServer.class);

	public static final class Builder {
		private Consumer<? super UdpServerOptions.Builder> options;

		private Builder() {
		}

		/**
		 * The options for the server, including address and port.
		 *
		 * @param options the options for the server, including address and port.
		 * @return {@code this}
		 */
		public final Builder options(Consumer<? super UdpServerOptions.Builder> options) {
			this.options = Objects.requireNonNull(options, "options");
			return this;
		}

		public UdpServer build() {
			return new UdpServer(this);
		}
	}
}
