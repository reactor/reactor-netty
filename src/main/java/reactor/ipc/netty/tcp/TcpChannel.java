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

package reactor.ipc.netty.tcp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.Loopback;
import reactor.core.Receiver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.Trackable;
import reactor.core.publisher.Operators;
import reactor.ipc.Channel;
import reactor.ipc.netty.common.NettyChannel;
import reactor.ipc.netty.common.NettyChannelHandler;
import reactor.ipc.netty.common.NettyOutbound;

/**
 * {@link Channel} implementation that delegates to Netty.
 * @author Stephane Maldini
 * @since 2.5
 */
public class TcpChannel extends Mono<Void>
		implements NettyChannel, Loopback, Trackable {

	final io.netty.channel.Channel ioChannel;
	final Flux<Object>             input;

	volatile boolean flushEach;

	public TcpChannel(io.netty.channel.Channel ioChannel, Flux<Object> input) {
		this.input = input;
		this.ioChannel = ioChannel;
	}

	@Override
	public Mono<Void> send(final Publisher<? extends ByteBuf> dataStream) {
		return new PostWritePublisher(dataStream);
	}

	@Override
	public Mono<Void> sendObject(final Publisher<?> dataStream) {
		return new PostWritePublisher(dataStream);
	}

	@Override
	public Flux<Object> receiveObject() {
		return input;
	}

	@Override
	public Object connectedInput() {
		return input;
	}

	@Override
	public Object connectedOutput() {
		io.netty.channel.Channel parent = ioChannel.parent();
		SocketAddress remote = ioChannel.remoteAddress();
		SocketAddress local = ioChannel.localAddress();
		String src = local != null ? local.toString() : "";
		String dst = remote != null ? remote.toString() : "";
		if (parent != null) {
		} else {
			String _src = src;
			src = dst;
			dst = _src;
		}

		return src.replaceFirst("localhost", "") +":"+dst.replaceFirst("localhost", "");
	}

	@Override
	public void subscribe(Subscriber<? super Void> subscriber) {
		Mono.<Void>empty().subscribe(subscriber);
	}

	@Override
	public boolean isStarted() {
		return ioChannel.isActive();
	}

	@Override
	public boolean isTerminated() {
		return !ioChannel.isOpen();
	}

	final protected void emitWriter(final Publisher<?> encodedWriter,
			final Subscriber<? super Void> postWriter) {

		final ChannelFutureListener postWriteListener = future -> {
			postWriter.onSubscribe(Operators.emptySubscription());
			if (future.isSuccess()) {
				postWriter.onComplete();
			}
			else {
				postWriter.onError(future.cause());
			}
		};

		NettyChannelHandler.FlushMode mode;
		if (flushEach) {
			mode = NettyChannelHandler.FlushMode.AUTO_EACH;
		}
		else {
			mode = NettyChannelHandler.FlushMode.MANUAL_COMPLETE;
		}

		NettyChannelHandler.ChannelWriter writer =
				new NettyChannelHandler.ChannelWriter(encodedWriter, mode);

		if (ioChannel.eventLoop()
		             .inEventLoop()) {

			ioChannel.write(writer)
			         .addListener(postWriteListener);
		}
		else {
			ioChannel.eventLoop()
			         .execute(() -> ioChannel.write(writer)
	                                 .addListener(postWriteListener));
		}
	}

	@Override
	public String toString() {
		return ioChannel.toString();
	}

	@Override
	public InetSocketAddress remoteAddress() {
		return (InetSocketAddress) ioChannel.remoteAddress();
	}

	@Override
	public Lifecycle on() {
		return new NettyLifecycle();
	}

	@Override
	public io.netty.channel.Channel delegate() {
		return ioChannel;
	}

	@Override
	public NettyOutbound flushEach() {
		flushEach = true;
		return this;
	}

	final class NettyLifecycle implements Lifecycle {

		@Override
		public Lifecycle close(final Runnable onClose) {
			ioChannel.pipeline()
			         .addLast(new ChannelDuplexHandler() {
				         @Override
				         public void channelInactive(ChannelHandlerContext ctx)
						         throws Exception {
					         onClose.run();
					         super.channelInactive(ctx);
				         }
			         });
			return this;
		}

		@Override
		public Lifecycle readIdle(long idleTimeout, final Runnable onReadIdle) {
			ioChannel.pipeline()
			         .addFirst(new IdleStateHandler(idleTimeout, 0, 0, TimeUnit.MILLISECONDS) {
				         @Override
				         protected void channelIdle(ChannelHandlerContext ctx,
						         IdleStateEvent evt) throws Exception {
					         if (evt.state() == IdleState.READER_IDLE) {
						         onReadIdle.run();
					         }
					         super.channelIdle(ctx, evt);
				         }
			         });
			return this;
		}

		@Override
		public Lifecycle writeIdle(long idleTimeout,
				final Runnable onWriteIdle) {
			ioChannel.pipeline()
			         .addLast(new IdleStateHandler(0, idleTimeout, 0, TimeUnit.MILLISECONDS) {
				         @Override
				         protected void channelIdle(ChannelHandlerContext ctx,
						         IdleStateEvent evt) throws Exception {
					         if (evt.state() == IdleState.WRITER_IDLE) {
						         onWriteIdle.run();
					         }
					         super.channelIdle(ctx, evt);
				         }
			         });
			return this;
		}
	}

	final class PostWritePublisher extends Mono<Void> implements Receiver, Loopback {

		private final Publisher<?> dataStream;

		public PostWritePublisher(Publisher<?> dataStream) {
			this.dataStream = dataStream;
		}

		@Override
		public void subscribe(Subscriber<? super Void> s) {
			try {
				emitWriter(dataStream, s);
			}
			catch (Throwable throwable) {
				Operators.error(s, throwable);
			}
		}

		@Override
		public Object upstream() {
			return dataStream;
		}

		@Override
		public Object connectedInput() {
			return TcpChannel.this;
		}

		@Override
		public Object connectedOutput() {
			return TcpChannel.this;
		}
	}
}
