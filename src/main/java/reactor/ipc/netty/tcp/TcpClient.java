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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.socket.SocketChannel;
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
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.options.NettyOptions;
import reactor.ipc.netty.resources.PoolResources;

/**
 * A TCP client connector.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public class TcpClient implements NettyConnector<NettyInbound, NettyOutbound> {

	/**
	 * Bind a new TCP client to the localhost on {@link NettyOptions#DEFAULT_PORT port 12012}.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @return a new {@link TcpClient}
	 */
	public static TcpClient create() {
		return create(NetUtil.LOCALHOST.getHostAddress());
	}

	/**
	 * Bind a new TCP client to the specified connect address and {@link NettyOptions#DEFAULT_PORT port 12012}.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param bindAddress the address to connect to on port 12012
	 * @return a new {@link TcpClient}
	 */
	public static TcpClient create(String bindAddress) {
		return create(bindAddress, NettyOptions.DEFAULT_PORT);
	}

	/**
	 * Bind a new TCP client to "localhost" on the the specified port.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param port the port to connect to on "localhost"
	 * @return a new {@link TcpClient}
	 */
	public static TcpClient create(int port) {
		return create(NetUtil.LOCALHOST.getHostAddress(), port);
	}

	/**
	 * Bind a new TCP client to the specified connect address and port.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param bindAddress the address to connect to
	 * @param port the port to connect to
	 * @return a new {@link TcpClient}
	 */
	public static TcpClient create(String bindAddress, int port) {
		return create(opts -> opts.host(bindAddress).port(port));
	}

	/**
	 * Bind a new TCP client to the specified connect address and port.
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 *
	 * @param options {@link ClientOptions} configuration input
	 * @return a new {@link TcpClient}
	 */
	public static TcpClient create(Consumer<? super ClientOptions.Builder<?>> options) {
		return builder().options(options).build();
	}

	/**
	 * Creates a builder for {@link TcpClient TcpClient}
	 *
	 * @return a new TcpClient builder
	 */
	public static TcpClient.Builder builder() {
		return new TcpClient.Builder();
	}

	final ClientOptions options;

	protected TcpClient(TcpClient.Builder builder) {
		ClientOptions.Builder<?> clientOptionsBuilder = ClientOptions.builder();
		if (Objects.nonNull(builder.options)) {
			builder.options.accept(clientOptionsBuilder);
		}
		if (!clientOptionsBuilder.isLoopAvailable()) {
			clientOptionsBuilder.loopResources(TcpResources.get());
		}
		if (!clientOptionsBuilder.isPoolAvailable() && !clientOptionsBuilder.isPoolDisabled()) {
			clientOptionsBuilder.poolResources(TcpResources.get());
		}
		this.options = clientOptionsBuilder.build();
	}

	protected TcpClient(ClientOptions options) {
		this.options = Objects.requireNonNull(options, "options");
	}

	@Override
	public final Mono<? extends Connection> newHandler(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return newHandler(handler, null, true, null);
	}

	/**
	 * Get a copy of the {@link ClientOptions} currently in effect.
	 *
	 * @return the client options
	 */
	public final ClientOptions options() {
		return this.options.duplicate();
	}

	@Override
	public String toString() {
		return "TcpClient: " + options.asSimpleString();
	}

	/**
	 * @param handler
	 * @param address
	 * @param secure
	 * @param onSetup
	 *
	 * @return a new Mono to connect on subscribe
	 */
	protected Mono<Connection> newHandler(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
			InetSocketAddress address,
			boolean secure,
			Consumer<? super Channel> onSetup) {

		final BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>>
				targetHandler =
				null == handler ? ChannelOperations.noopHandler() : handler;

		return Mono.create(sink -> {
			SocketAddress remote = address != null ? address : options.getAddress();

			ChannelPool pool = null;

			PoolResources poolResources = options.getPoolResources();
			if (poolResources != null) {
				pool = poolResources.selectOrCreate(remote, options,
						doHandler(null, sink, secure, remote, null, null),
						options.getLoopResources().onClient(options.preferNative()));
			}

			ContextHandler<SocketChannel> contextHandler =
					doHandler(targetHandler, sink, secure, remote, pool, onSetup);
			sink.onCancel(contextHandler);

			if (pool == null) {
				Bootstrap b = options.get();
				b.remoteAddress(remote);
				b.handler(contextHandler);
				contextHandler.setFuture(b.connect());
			}
			else {
				contextHandler.setFuture(pool.acquire());
			}
		});
	}

	/**
	 * Create a {@link ContextHandler} for {@link Bootstrap#handler()}
	 *
	 * @param handler user provided in/out handler
	 * @param sink user provided bind handler
	 * @param secure if operation should be secured
	 * @param pool if channel pool
	 * @param onSetup if operation has local setup callback
	 *
	 * @return a new {@link ContextHandler}
	 */
	protected ContextHandler<SocketChannel> doHandler(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
			MonoSink<Connection> sink,
			boolean secure,
			SocketAddress providedAddress,
			ChannelPool pool,
			Consumer<? super Channel> onSetup) {
		return ContextHandler.newClientContext(sink,
				options,
				loggingHandler,
				secure,
				providedAddress,
				pool,
				handler == null ? EMPTY :
						(ch, c, msg) -> ChannelOperations.bind(ch, handler, c));
	}

	protected static final ChannelOperations.OnNew EMPTY = (a,b,c) -> null;

	static final LoggingHandler loggingHandler = new LoggingHandler(TcpClient.class);


	public static final class Builder {
		private Consumer<? super ClientOptions.Builder<?>> options;

		private Builder() {
		}

		/**
		 * The options for the client, including address and port.
		 *
		 * @param options the options for the client, including address and port.
		 * @return {@code this}
		 */
		public final Builder options(Consumer<? super ClientOptions.Builder<?>> options) {
			this.options = Objects.requireNonNull(options, "options");
			return this;
		}

		public TcpClient build() {
			return new TcpClient(this);
		}
	}
}
