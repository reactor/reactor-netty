/*
 * Copyright (c) 2021-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicChannelBootstrap;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.TransportConnector;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static reactor.netty.ConnectionObserver.State.CONFIGURED;
import static reactor.netty.ReactorNetty.format;

/**
 * Provides the actual {@link QuicClient} instance.
 *
 * @author Violeta Georgieva
 */
final class QuicClientConnect extends QuicClient {

	static final QuicClientConnect INSTANCE = new QuicClientConnect();

	final QuicClientConfig config;

	QuicClientConnect() {
		this.config = new QuicClientConfig(
				Collections.emptyMap(),
				Collections.singletonMap(ChannelOption.AUTO_READ, false),
				() -> new InetSocketAddress(NetUtil.LOCALHOST, 0),
				() -> new InetSocketAddress(NetUtil.LOCALHOST, DEFAULT_PORT));
	}

	QuicClientConnect(QuicClientConfig config) {
		this.config = config;
	}

	@Override
	public QuicClientConfig configuration() {
		return config;
	}

	@Override
	public Mono<? extends QuicConnection> connect() {
		QuicClientConfig config = configuration();
		validate(config);

		Mono<? extends QuicConnection> mono = Mono.create(sink -> {
			Supplier<? extends SocketAddress> bindAddress = Objects.requireNonNull(config.bindAddress());
			SocketAddress local = Objects.requireNonNull(bindAddress.get(), "Bind Address supplier returned null");
			if (local instanceof InetSocketAddress) {
				InetSocketAddress localInet = (InetSocketAddress) local;

				if (localInet.isUnresolved()) {
					local = AddressUtils.createResolved(localInet.getHostName(), localInet.getPort());
				}
			}

			DisposableConnect disposableConnect = new DisposableConnect(config, local, sink);
			TransportConnector.bind(config, config.parentChannelInitializer(), local, false)
			                  .subscribe(disposableConnect);
		});

		Consumer<? super QuicClientConfig> doOnConnect = config.doOnConnect;
		if (doOnConnect != null) {
			mono = mono.doOnSubscribe(s -> doOnConnect.accept(config));
		}

		return mono;
	}

	@Override
	protected QuicClient duplicate() {
		return new QuicClientConnect(new QuicClientConfig(config));
	}

	static void validate(QuicClientConfig config) {
		Objects.requireNonNull(config.bindAddress(), "bindAddress");
		Objects.requireNonNull(config.remoteAddress, "remoteAddress");
		Objects.requireNonNull(config.sslEngineProvider, "sslEngineProvider");
	}

	static final class DisposableConnect implements CoreSubscriber<Channel>, Disposable {

		final Map<AttributeKey<?>, ?>           attributes;
		final SocketAddress                     bindAddress;
		final Context                           currentContext;
		final @Nullable ChannelHandler          loggingHandler;
		final Map<ChannelOption<?>, ?>          options;
		final ChannelInitializer<Channel>       quicChannelInitializer;
		final Supplier<? extends SocketAddress> remoteAddress;
		final MonoSink<QuicConnection>          sink;
		final Map<AttributeKey<?>, ?>           streamAttrs;
		final ConnectionObserver                streamObserver;
		final Map<ChannelOption<?>, ?>          streamOptions;

		// Never null when accessed - only via dispose()
		// which is registered into sink.onCancel() callback.
		// See onSubscribe(Subscription).
		@SuppressWarnings("NullAway")
		Subscription subscription;

		DisposableConnect(QuicClientConfig config, SocketAddress bindAddress, MonoSink<QuicConnection> sink) {
			this.attributes = config.attributes();
			this.bindAddress = bindAddress;
			this.currentContext = Context.of(sink.contextView());
			this.loggingHandler = config.loggingHandler();
			this.options = config.options();
			ConnectionObserver observer = new QuicChannelObserver(
					config.defaultConnectionObserver().then(config.connectionObserver()),
					sink);
			this.quicChannelInitializer = config.channelInitializer(observer, null, false);
			this.remoteAddress = config.remoteAddress;
			this.sink = sink;
			this.streamAttrs = config.streamAttrs;
			this.streamObserver =
					config.streamObserver.then(new QuicTransportConfig.QuicStreamChannelObserver(config.streamHandler));
			this.streamOptions = config.streamOptions;
		}

		@Override
		public Context currentContext() {
			return currentContext;
		}

		@Override
		public void dispose() {
			// sink.onCancel() registration happens in onSubscribe()
			subscription.cancel();
		}

		@Override
		public void onComplete() {
		}

		@Override
		public void onError(Throwable t) {
			if (t instanceof BindException ||
					// With epoll/kqueue transport it is
					// io.netty.channel.unix.Errors$NativeIoException: bind(..) failed: Address already in use
					(t instanceof IOException && t.getMessage() != null && t.getMessage().contains("bind(..)"))) {
				sink.error(ChannelBindException.fail(bindAddress, null));
			}
			else {
				sink.error(t);
			}
		}

		@Override
		public void onNext(Channel channel) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Bound new channel"));
			}

			final SocketAddress remote = Objects.requireNonNull(remoteAddress.get(), "Remote Address supplier returned null");

			QuicChannelBootstrap bootstrap =
					QuicChannel.newBootstrap(channel)
					           .remoteAddress(remote)
					           .handler(quicChannelInitializer)
					           .streamHandler(
					               QuicTransportConfig.streamChannelInitializer(loggingHandler, streamObserver, true));

			attributes(bootstrap, attributes);
			channelOptions(bootstrap, options);
			streamAttributes(bootstrap, streamAttrs);
			streamChannelOptions(bootstrap, streamOptions);

			bootstrap.connect()
			         .addListener(f -> {
			             // We don't need to handle success case, we've already configured QuicChannelObserver
			             if (!f.isSuccess()) {
			                 if (f.cause() != null) {
			                     sink.error(f.cause());
			                 }
			                 else {
			                     sink.error(new IOException("Cannot connect to [" + remote + "]"));
			                 }
			             }
			         });
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(subscription, s)) {
				this.subscription = s;
				sink.onCancel(this);
				s.request(Long.MAX_VALUE);
			}
		}

		@SuppressWarnings("unchecked")
		static void attributes(QuicChannelBootstrap bootstrap, Map<AttributeKey<?>, ?> attrs) {
			for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
				bootstrap.attr((AttributeKey<Object>) e.getKey(), e.getValue());
			}
		}

		@SuppressWarnings("unchecked")
		static void channelOptions(QuicChannelBootstrap bootstrap, Map<ChannelOption<?>, ?> options) {
			for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
				bootstrap.option((ChannelOption<Object>) e.getKey(), e.getValue());
			}
		}

		@SuppressWarnings("unchecked")
		static void streamAttributes(QuicChannelBootstrap bootstrap, Map<AttributeKey<?>, ?> attrs) {
			for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
				bootstrap.streamAttr((AttributeKey<Object>) e.getKey(), e.getValue());
			}
		}

		@SuppressWarnings("unchecked")
		static void streamChannelOptions(QuicChannelBootstrap bootstrap, Map<ChannelOption<?>, ?> options) {
			for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
				bootstrap.streamOption((ChannelOption<Object>) e.getKey(), e.getValue());
			}
		}
	}

	static final class QuicChannelObserver implements ConnectionObserver {

		final ConnectionObserver       childObs;
		final MonoSink<QuicConnection> sink;

		QuicChannelObserver(ConnectionObserver childObs, MonoSink<QuicConnection> sink) {
			this.childObs = childObs;
			this.sink = sink;
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == CONFIGURED) {
				sink.success((QuicConnection) Connection.from(connection.channel()));
			}

			childObs.onStateChange(connection, newState);
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			sink.error(error);
			childObs.onUncaughtException(connection, error);
		}
	}

	/**
	 * The default port for reactor-netty QUIC clients. Defaults to 12012 but can be tuned via
	 * the {@code QUIC_PORT} <b>environment variable</b>.
	 */
	static final int DEFAULT_PORT;
	static {
		int port;
		String portStr = null;
		try {
			portStr = System.getenv("QUIC_PORT");
			port = portStr != null ? Integer.parseInt(portStr) : 12012;
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid environment variable [QUIC_PORT=" + portStr + "].", e);
		}
		DEFAULT_PORT = port;
	}
}
