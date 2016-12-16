/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyContext;
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

	CloseableContextHandler(BiFunction<? super CHANNEL,? super ContextHandler<CHANNEL>, ? extends ChannelOperations<?, ?>> channelOpSelector,
			NettyOptions<?, ?> options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler) {
		super(channelOpSelector, options, sink, loggingHandler);
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
		f.addListener(this);
		sink.setCancellation(this);
	}

	@Override
	public final void dispose() {
		if (f.channel()
		     .isOpen()) {
				f.channel()
				 .close();
		}
		else if (!f.isDone()) {
			f.cancel(true);
		}
	}
}
