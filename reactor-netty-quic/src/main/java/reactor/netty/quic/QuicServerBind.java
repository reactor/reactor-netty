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

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.util.NetUtil;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.TransportConnector;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Objects;

import static reactor.netty.ReactorNetty.format;

/**
 * Provides the actual {@link QuicServer} instance.
 *
 * @author Violeta Georgieva
 */
final class QuicServerBind extends QuicServer {

	static final QuicServerBind INSTANCE = new QuicServerBind();

	final QuicServerConfig config;

	QuicServerBind() {
		this.config = new QuicServerConfig(
				Collections.emptyMap(),
				Collections.singletonMap(ChannelOption.AUTO_READ, false),
				() -> new InetSocketAddress(NetUtil.LOCALHOST, 0));
	}

	QuicServerBind(QuicServerConfig config) {
		this.config = config;
	}

	@Override
	public Mono<? extends Connection> bind() {
		QuicServerConfig config = configuration();
		validate(config);

		Mono<? extends Connection> mono = Mono.create(sink -> {
			SocketAddress local = Objects.requireNonNull(config.bindAddress().get(), "Bind Address supplier returned null");
			if (local instanceof InetSocketAddress) {
				InetSocketAddress localInet = (InetSocketAddress) local;

				if (localInet.isUnresolved()) {
					local = AddressUtils.createResolved(localInet.getHostName(), localInet.getPort());
				}
			}

			DisposableBind disposableBind = new DisposableBind(local, sink);
			TransportConnector.bind(config, config.parentChannelInitializer(), local, false)
			                  .subscribe(disposableBind);
		});

		if (config.doOnBind() != null) {
			mono = mono.doOnSubscribe(s -> config.doOnBind().accept(config));
		}

		return mono;
	}

	@Override
	public QuicServerConfig configuration() {
		return config;
	}

	@Override
	protected QuicServer duplicate() {
		return new QuicServerBind(new QuicServerConfig(config));
	}

	static void validate(QuicServerConfig config) {
		Objects.requireNonNull(config.bindAddress(), "bindAddress");
		Objects.requireNonNull(config.sslContext, "sslContext");
		Objects.requireNonNull(config.tokenHandler, "tokenHandler");
	}

	static final class DisposableBind implements CoreSubscriber<Channel>, Disposable {

		final SocketAddress        bindAddress;
		final MonoSink<Connection> sink;

		Subscription subscription;

		DisposableBind(SocketAddress bindAddress, MonoSink<Connection> sink) {
			this.bindAddress = bindAddress;
			this.sink = sink;
		}

		@Override
		public Context currentContext() {
			return sink.currentContext();
		}

		@Override
		public void dispose() {
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
			sink.success(Connection.from(channel));
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(subscription, s)) {
				this.subscription = s;
				sink.onCancel(this);
				s.request(Long.MAX_VALUE);
			}
		}
	}
}
