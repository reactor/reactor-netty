/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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

package reactor.netty.tcp;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.netty.handler.ssl.SslContext;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ClientTransport;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty.ReactorNetty.format;

/**
 * A TcpClient allows to build in a safe immutable way a TCP client that
 * is materialized and connecting when {@link #connect()} is ultimately called.
 * <p>
 * <p> Example:
 * <pre>
 * {@code
 * TcpClient.create()
 *          .doOnConnect(connectMetrics)
 *          .doOnConnected(connectedMetrics)
 *          .doOnDisconnected(disconnectedMetrics)
 *          .host("127.0.0.1")
 *          .port(1234)
 *          .secure()
 *          .connect()
 *          .block()
 * }
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public abstract class TcpClient extends ClientTransport<TcpClient, TcpClientConfig> {

	/**
	 * Prepare a pooled {@link TcpClient}
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient create() {
		return create(TcpResources.get());
	}


	/**
	 * Prepare a {@link TcpClient}
	 *
	 * @param provider a {@link ConnectionProvider} to acquire connections
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient create(ConnectionProvider provider) {
		return new TcpClientConnect(provider);
	}

	/**
	 * Prepare a non pooled {@link TcpClient}
	 *
	 * @return a {@link TcpClient}
	 */
	public static TcpClient newConnection() {
		return TcpClientConnect.INSTANCE;
	}

	@Override
	public final Mono<? extends Connection> connect() {
		return super.connect();
	}

	@Override
	public final Connection connectNow() {
		return super.connectNow();
	}

	@Override
	public final Connection connectNow(Duration timeout) {
		return super.connectNow(timeout);
	}

	/**
	 * Attach an IO handler to react on connected client
	 *
	 * @param handler an IO handler that can dispose underlying connection when {@link
	 * Publisher} terminates.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient handle(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return doOnConnected(new OnConnectedHandle(handler));
	}

	@Override
	public final TcpClient metrics(boolean enable) {
		return super.metrics(enable);
	}

	/**
	 * Remove any previously applied SSL configuration customization
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient noSSL() {
		if (configuration().isSecure()) {
			TcpClient dup = duplicate();
			dup.configuration().sslProvider = null;
			return dup;
		}
		return this;
	}

	/**
	 * Enable default sslContext support. The default {@link SslContext} will be
	 * assigned to
	 * with a default value of {@code 10} seconds handshake timeout unless
	 * the environment property {@code reactor.netty.tcp.sslHandshakeTimeout} is set.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient secure() {
		TcpClient dup = duplicate();
		dup.configuration().sslProvider = SslProvider.defaultClientProvider();
		return dup;
	}

	/**
	 * Apply an SSL configuration customization via the passed builder. The builder
	 * will produce the {@link SslContext} to be passed to with a default value of
	 * {@code 10} seconds handshake timeout unless the environment property {@code
	 * reactor.netty.tcp.sslHandshakeTimeout} is set.
	 *
	 * @param sslProviderBuilder builder callback for further customization of SslContext.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		Objects.requireNonNull(sslProviderBuilder, "sslProviderBuilder");
		TcpClient dup = duplicate();
		SslProvider.SslContextSpec builder = SslProvider.builder();
		sslProviderBuilder.accept(builder);
		dup.configuration().sslProvider = ((SslProvider.Builder) builder).build();
		return dup;
	}

	/**
	 * Apply an SSL configuration via the passed {@link SslProvider}.
	 *
	 * @param sslProvider The provider to set when configuring SSL
	 *
	 * @return a new {@link TcpClient}
	 */
	public final TcpClient secure(SslProvider sslProvider) {
		Objects.requireNonNull(sslProvider, "sslProvider");
		TcpClient dup = duplicate();
		dup.configuration().sslProvider = sslProvider;
		return dup;
	}

	static final Logger log = Loggers.getLogger(TcpClient.class);

	static final class OnConnectedHandle implements Consumer<Connection> {

		final BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler;

		OnConnectedHandle(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
			this.handler = handler;
		}

		@Override
		public void accept(Connection c) {
			if (log.isDebugEnabled()) {
				log.debug(format(c.channel(), "Handler is being applied: {}"), handler);
			}

			Mono.fromDirect(handler.apply((NettyInbound) c, (NettyOutbound) c))
			    .subscribe(c.disposeSubscriber());
		}
	}
}
