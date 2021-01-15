/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.quic;

import reactor.core.publisher.Mono;
import reactor.netty.transport.AddressUtils;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A QuicClient allows building in a safe immutable way a QUIC client that is materialized
 * and connecting when {@link #connect()} is ultimately called.
 * <p>
 * <p> Example:
 * <pre>
 * {@code
 *     QuicClient.create()
 *               .port(7777)
 *               .bindAddress(() -> new InetSocketAddress(0))
 *               .wiretap(true)
 *               .secure(clientCtx)
 *               .idleTimeout(Duration.ofSeconds(5))
 *               .initialSettings(spec ->
 *                   spec.maxData(10000000)
 *                       .maxStreamDataBidirectionalLocal(1000000))
 *               .connectNow();
 * }
 * </pre>
 *
 * @author Violeta Georgieva
 */
public abstract class QuicClient extends QuicTransport<QuicClient, QuicClientConfig> {

	/**
	 * Prepare a {@link QuicClient}
	 *
	 * @return a {@link QuicClient}
	 */
	public static QuicClient create() {
		return QuicClientConnect.INSTANCE;
	}

	/**
	 * Connect the {@link QuicClient} and return a {@link Mono} of {@link QuicConnection}. If
	 * {@link Mono} is cancelled, the underlying connection will be aborted. Once the
	 * {@link QuicConnection} has been emitted and is not necessary anymore, disposing must be
	 * done by the user via {@link QuicConnection#dispose()}.
	 *
	 * @return a {@link Mono} of {@link QuicConnection}
	 */
	public abstract Mono<? extends QuicConnection> connect();

	/**
	 * Block the {@link QuicClient} and return a {@link QuicConnection}. Disposing must be
	 * done by the user via {@link QuicConnection#dispose()}. The max connection
	 * timeout is 45 seconds.
	 *
	 * @return a {@link QuicConnection}
	 */
	public final QuicConnection connectNow() {
		return connectNow(Duration.ofSeconds(45));
	}

	/**
	 * Block the {@link QuicClient} and return a {@link QuicConnection}. Disposing must be
	 * done by the user via {@link QuicConnection#dispose()}.
	 *
	 * @param timeout connect timeout (resolution: ns)
	 * @return a {@link QuicConnection}
	 */
	public final QuicConnection connectNow(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		try {
			return Objects.requireNonNull(connect().block(timeout), "aborted");
		}
		catch (IllegalStateException e) {
			if (e.getMessage().contains("blocking read")) {
				throw new IllegalStateException("QuicClient couldn't be started within " + timeout.toMillis() + "ms");
			}
			throw e;
		}
	}

	/**
	 * Set or add a callback called when {@link QuicClient} is about to connect to the remote endpoint.
	 *
	 * @param doOnConnect a consumer observing connect events
	 * @return a {@link QuicClient} reference
	 */
	public final QuicClient doOnConnect(Consumer<? super QuicClientConfig> doOnConnect) {
		Objects.requireNonNull(doOnConnect, "doOnConnect");
		QuicClient dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<QuicClientConfig> current = (Consumer<QuicClientConfig>) configuration().doOnConnect;
		dup.configuration().doOnConnect = current == null ? doOnConnect : current.andThen(doOnConnect);
		return dup;
	}

	/**
	 * Set or add a callback called after {@link QuicConnection} has been connected.
	 *
	 * @param doOnConnected a consumer observing connected events
	 * @return a {@link QuicClient} reference
	 */
	public final QuicClient doOnConnected(Consumer<? super QuicConnection> doOnConnected) {
		Objects.requireNonNull(doOnConnected, "doOnConnected");
		QuicClient dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<QuicConnection> current = (Consumer<QuicConnection>) configuration().doOnConnected;
		dup.configuration().doOnConnected = current == null ? doOnConnected : current.andThen(doOnConnected);
		return dup;
	}

	/**
	 * Set or add a callback called after {@link QuicConnection} has been disconnected.
	 *
	 * @param doOnDisconnected a consumer observing disconnected events
	 * @return a {@link QuicClient} reference
	 */
	public final QuicClient doOnDisconnected(Consumer<? super QuicConnection> doOnDisconnected) {
		Objects.requireNonNull(doOnDisconnected, "doOnDisconnected");
		QuicClient dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<QuicConnection> current = (Consumer<QuicConnection>) configuration().doOnDisconnected;
		dup.configuration().doOnDisconnected = current == null ? doOnDisconnected : current.andThen(doOnDisconnected);
		return dup;
	}

	/**
	 * The host to which this client should connect.
	 *
	 * @param host the host to connect to
	 * @return a {@link QuicClient} reference
	 */
	public final QuicClient host(String host) {
		Objects.requireNonNull(host, "host");
		return remoteAddress(() -> AddressUtils.updateHost(configuration().remoteAddress(), host));
	}

	/**
	 * The port to which this client should connect.
	 *
	 * @param port the port to connect to
	 * @return a {@link QuicClient} reference
	 */
	public final QuicClient port(int port) {
		return remoteAddress(() -> AddressUtils.updatePort(configuration().remoteAddress(), port));
	}

	/**
	 * The address to which this client should connect on each subscribe.
	 *
	 * @param remoteAddressSupplier A supplier of the address to connect to.
	 * @return a {@link QuicClient}
	 */
	public final QuicClient remoteAddress(Supplier<? extends SocketAddress> remoteAddressSupplier) {
		Objects.requireNonNull(remoteAddressSupplier, "remoteAddressSupplier");
		QuicClient dup = duplicate();
		dup.configuration().remoteAddress = remoteAddressSupplier;
		return dup;
	}
}
