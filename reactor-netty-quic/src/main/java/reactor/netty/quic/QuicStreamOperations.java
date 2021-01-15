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

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.incubator.codec.quic.DefaultQuicStreamFrame;
import io.netty.incubator.codec.quic.QuicStreamFrame;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.NettyOutbound;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/**
 * An QUIC stream ready {@link ChannelOperations} with state management for FIN.
 * Send operation will try (when possible) to write and flush the message and FIN with one operation.
 *
 * @author Violeta Georgieva
 */
class QuicStreamOperations extends ChannelOperations<QuicInbound, QuicOutbound> implements QuicInbound, QuicOutbound {

	static final AtomicIntegerFieldUpdater<QuicStreamOperations> FIN_SENT =
			AtomicIntegerFieldUpdater.newUpdater(QuicStreamOperations.class, "finSent");
	volatile int finSent;

	static final Logger log = Loggers.getLogger(QuicStreamOperations.class);

	QuicStreamOperations(Connection connection, ConnectionObserver listener) {
		super(connection, listener);
		markPersistent(false);
	}

	@Override
	@SuppressWarnings("unchecked")
	public NettyOutbound send(Publisher<? extends ByteBuf> dataStream, Predicate<ByteBuf> predicate) {
		requireNonNull(predicate, "predicate");
		if (!channel().isActive()) {
			return then(Mono.error(AbortedException.beforeSend()));
		}
		if (dataStream instanceof Mono) {
			return then(((Mono<ByteBuf>) dataStream)
					.flatMap(m -> {
						if (markFinSent()) {
							return FutureMono.from(channel().writeAndFlush(new DefaultQuicStreamFrame(m, true)));
						}
						return FutureMono.from(channel().writeAndFlush(m));
					})
					.doOnDiscard(ByteBuf.class, ByteBuf::release));
		}
		return super.send(dataStream, predicate);
	}

	@Override
	public NettyOutbound sendObject(Object message) {
		if (!channel().isActive()) {
			ReactorNetty.safeRelease(message);
			return then(Mono.error(AbortedException.beforeSend()));
		}
		if (!(message instanceof ByteBuf)) {
			return super.sendObject(message);
		}
		ByteBuf buffer = (ByteBuf) message;
		return then(
				FutureMono.deferFuture(() -> {
					if (markFinSent()) {
						return connection().channel().writeAndFlush(new DefaultQuicStreamFrame(buffer, true));
					}
					return connection().channel().writeAndFlush(buffer);
				}),
				() -> ReactorNetty.safeRelease(buffer));
	}

	@Override
	protected void onInboundCancel() {
		if (log.isDebugEnabled()) {
			log.debug("Cancelling inbound stream. Sending WRITE_FIN.");
		}

		sendFinNow(f -> terminate());
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if (log.isDebugEnabled()) {
			log.debug("Outbound error happened. Sending WRITE_FIN.", err);
		}

		sendFinNow(f -> terminate());
	}

	@Override
	protected final void onInboundComplete() {
		super.onInboundComplete();
	}

	final boolean markFinSent() {
		return FIN_SENT.compareAndSet(this, 0, 1);
	}

	final void sendFinNow() {
		sendFinNow(null);
	}

	final void sendFinNow(@Nullable ChannelFutureListener listener) {
		if (markFinSent()) {
			ChannelFuture f = channel().writeAndFlush(QuicStreamFrame.EMPTY_FIN);
			if (listener != null) {
				f.addListener(listener);
			}
		}
	}

	static void callTerminate(Channel ch) {
		ChannelOperations<?, ?> ops = get(ch);

		if (ops == null) {
			return;
		}

		((QuicStreamOperations) ops).terminate();
	}
}
