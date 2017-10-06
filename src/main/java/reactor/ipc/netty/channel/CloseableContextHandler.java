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

package reactor.ipc.netty.channel;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Objects;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import org.reactivestreams.Publisher;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.options.NettyOptions;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @param <CHANNEL> the channel type
 *
 * @author Stephane Maldini
 */
abstract class CloseableContextHandler<CHANNEL extends Channel>
		extends ContextHandler<CHANNEL> implements ChannelFutureListener {

	static final Logger log = Loggers.getLogger(CloseableContextHandler.class);

	ChannelFuture f;

	CloseableContextHandler(ChannelOperations.OnNew<CHANNEL> channelOpFactory,
			NettyOptions<?, ?> options,
			MonoSink<Connection> sink,
			LoggingHandler loggingHandler,
			SocketAddress providedAddress) {
		super(channelOpFactory, options, sink, loggingHandler, providedAddress);
	}

	@Override
	protected Publisher<Void> onCloseOrRelease(Channel channel) {
		return FutureMono.from(channel.closeFuture());
	}

	@Override
	public final void operationComplete(ChannelFuture f) throws Exception {
		if (!f.isSuccess()) {
			if(f.isCancelled()){
				log.debug("Cancelled {}", f.channel().toString());
				return;
			}
			if (f.cause() != null) {
				sink.error(f.cause());
			}
			else {
				sink.error(new IOException("error while connecting to " + f.channel()
				                                                           .toString()));
			}
		}
		else {
			doStarted(f.channel());
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public final void setFuture(Future<?> future) {
		Objects.requireNonNull(future, "future");
		if (this.f != null) {
			future.cancel(true);
			return;
		}
		if(log.isDebugEnabled()){
			log.debug("Connecting new channel: {}", future.toString());
		}
		this.f = (ChannelFuture) future;

		if(future.isDone()){
			try {
				operationComplete((ChannelFuture) future);
			}
			catch (Exception e){
				fireContextError(e);
			}
			return;
		}
		f.addListener(this);
	}

	@Override
	public final void dispose() {
		if (f == null){
			return;
		}
		if (f.channel()
		     .isActive()) {

				f.channel()
				 .close();
		}
		else if (!f.isDone()) {
			f.cancel(true);
		}
	}
}
