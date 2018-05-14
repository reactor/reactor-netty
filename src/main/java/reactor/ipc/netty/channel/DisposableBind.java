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
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.DisposableServer;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

/**
 * @author Stephane Maldini
 */
final class DisposableBind
		implements DisposableServer, ChannelFutureListener, ConnectionObserver,
		           Consumer<Future<?>>, Connection {

	static final Logger log = Loggers.getLogger(DisposableBind.class);

	final ConnectionObserver selectorListener;

	ChannelFuture f;

	DisposableBind(ConnectionObserver selectorListener) {
		this.selectorListener = Objects.requireNonNull(selectorListener, "listener");
	}

	@SuppressWarnings("unchecked")
	@Override
	public void accept(Future<?> future) {
		Objects.requireNonNull(future, "future");

		if (this.f != null) {
			future.cancel(true);
			return;
		}
		this.f = (ChannelFuture) future;

		f.addListener(this);
	}

	@Override
	public Channel channel() {
		return f.channel();
	}

	@Override
	public Context currentContext() {
		return selectorListener.currentContext();
	}

	@Override
	public final void dispose() {
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
		selectorListener.onStateChange(connection, newState);
	}

	@Override
	public void onUncaughtException(Connection connection, Throwable error) {
		log.error("onUncaughtException(" + connection + ")", error);
		selectorListener.onUncaughtException(connection, error);
	}

	@Override
	public final void operationComplete(ChannelFuture f) {
		bind();
		if (!f.isSuccess()) {
			if (f.isCancelled()) {
				log.debug("Cancelled Server creation on {}",
						f.channel()
						 .toString());
				return;
			}
			if (f.cause() != null) {
				selectorListener.onUncaughtException(this, f.cause());
			}
			else {
				selectorListener.onUncaughtException(this, new IOException("error while binding server to " + f.channel().toString()));
			}
		}
		else {
			if (log.isDebugEnabled()) {
				log.debug("Started server on: {}",
						f.channel()
						 .toString());
			}

			selectorListener.onStateChange(this, State.CONNECTED);
		}
	}
}
