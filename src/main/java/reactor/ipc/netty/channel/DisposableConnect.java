/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.Future;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

/**
 * A {@link DisposableConnect} is bound to a user-facing {@link MonoSink}
 */
final class DisposableConnect
		implements Connection, ConnectionObserver, ChannelFutureListener, Consumer<Future<?>> {

	static final Logger log = Loggers.getLogger(DisposableConnect.class);

	final ConnectionObserver listener;

	ChannelFuture f;
	Channel channel;

	DisposableConnect(ConnectionObserver listener) {
		this.listener = listener;
	}

	@Override
	public void accept(Future<?> future) {
		Objects.requireNonNull(future, "future");
		if (this.f != null) {
			future.cancel(true);
			return;
		}
		if (log.isDebugEnabled()) {
			log.debug("Connecting new channel");
		}
		this.f = (ChannelFuture) future;

		f.addListener(this);
	}

	@Override
	public Channel channel() {
		return channel;
	}

	@Override
	public Context currentContext() {
		return listener.currentContext();
	}

	@Override
	public final void dispose() {
		if (f == null) {
			return;
		}
		f.removeListener(this);

		if (f.channel()
		     .isActive()) {

			f.channel()
			 .close();
		}
		else if (!f.isDone()) {
			f.cancel(true);
		}
	}

	@Override
	public void onStateChange(Connection connection, State newState) {
		log.debug("onStateChange({}, {})", newState, connection);
		if (newState == State.DISCONNECTING) {
			if (channel.isActive()) {
				channel.close();
			}
		}
		listener.onStateChange(connection, newState);
	}

	@Override
	public void onUncaughtException(Connection connection, Throwable error) {
		log.error("onUncaughtException("+connection+")", connection, error);
		listener.onUncaughtException(connection, error);
	}

	@Override
	public final void operationComplete(ChannelFuture f) {
		this.channel = f.channel();
		bind();
		if (!f.isSuccess()) {
			if (f.isCancelled()) {
				log.debug("Cancelled {}", f.channel());
				return;
			}
			if (f.cause() != null) {
				listener.onUncaughtException(this, f.cause());
			}
			else {
				listener.onUncaughtException(this, new IOException("error while connecting to " + f.channel()));
			}
		}
		else if (log.isDebugEnabled()) {
			log.debug("Connected new channel {}", f.channel());
		}
	}
}
