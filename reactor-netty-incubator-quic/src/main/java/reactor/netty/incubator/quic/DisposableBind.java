/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.incubator.quic;

import io.netty.channel.Channel;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.BindException;
import java.net.SocketAddress;

import static reactor.netty.ReactorNetty.format;

public final class DisposableBind implements CoreSubscriber<Channel>, Disposable {

	final SocketAddress bindAddress;
	final Context currentContext;
	final MonoSink<Connection> sink;

	Subscription subscription;

	private static final Logger log = Loggers.getLogger(DisposableBind.class);

	DisposableBind(SocketAddress bindAddress, MonoSink<Connection> sink) {
		this.bindAddress = bindAddress;
		this.currentContext = Context.of(sink.contextView());
		this.sink = sink;
	}

	@Override
	public Context currentContext() {
		return currentContext;
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
