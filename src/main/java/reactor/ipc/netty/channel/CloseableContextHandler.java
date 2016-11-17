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
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import reactor.core.Cancellation;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.ChannelFutureMono;
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
	public final void fireContextActive(NettyContext context) {
		context = context != null ? context : this;
		sink.success(context);
	}

	@Override
	public final InetSocketAddress address() {
		Channel c = f.channel();
		if (c instanceof SocketChannel) {
			return ((SocketChannel) c).remoteAddress();
		}
		if (c instanceof ServerSocketChannel) {
			return ((ServerSocketChannel) c).localAddress();
		}
		if (c instanceof DatagramChannel) {
			return ((DatagramChannel) c).localAddress();
		}
		throw new IllegalStateException("Does not have an InetSocketAddress");
	}

	@Override
	public final Channel channel() {
		return f.channel();
	}

	@Override
	public final Mono<Void> onClose() {
		return ChannelFutureMono.from(f.channel().closeFuture());
	}

	@Override
	public final void operationComplete(ChannelFuture f) throws Exception {
		if (!f.isSuccess()) {
			if (f.cause() != null) {
				sink.error(f.cause());
			}
			else {
				sink.error(new IOException("error while connecting to " + f.channel()
				                                                           .toString()));
			}
		}
		else {
			log.debug("started {}", f.channel().toString());
			doStarted(f.channel());
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public final void setFuture(Future<?> future) {
		Objects.requireNonNull(future, "future");
		if (this.f != null) {
			future.cancel(true);
		}
		this.f = (ChannelFuture) future;
		f.addListener(this);
		sink.setCancellation(this);
	}

	@Override
	public final void dispose() {
		if (f.channel()
		     .isOpen()) {
			try {
				f.channel()
				 .close()
				 .sync();
			}
			catch (InterruptedException e) {
				log.error("error while disposing the channel", e);
			}
		}
		else if (!f.isDone()) {
			f.cancel(true);
		}
	}
}
