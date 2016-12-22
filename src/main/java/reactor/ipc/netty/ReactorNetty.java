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
package reactor.ipc.netty;

import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * Internal helpers for reactor-netty contracts
 *
 * @author Stephane Maldini
 */
final class ReactorNetty {
	ReactorNetty(){
	}

	static final class TerminatedHandlerEvent {
		@Override
		public String toString() {
			return "[Handler Terminated]";
		}
	}

	/**
	 * An appending write that delegates to its origin context and append the passed
	 * publisher after the origin success if any.
	 */
	static final class OutboundThen implements NettyOutbound {

		final NettyContext sourceContext;
		final Mono<Void>   thenMono;

		OutboundThen(NettyOutbound source, Publisher<Void> thenPublisher) {
			this.sourceContext = source.context();

			Mono<Void> parentMono = source.then();

			if (parentMono == Mono.<Void>empty()) {
				this.thenMono = Mono.from(thenPublisher);
			}
			else {
				this.thenMono = parentMono.thenEmpty(thenPublisher);
			}
		}

		@Override
		public NettyContext context() {
			return sourceContext;
		}

		@Override
		public Mono<Void> then() {
			return thenMono;
		}
	}

	final static class OutboundIdleStateHandler extends IdleStateHandler {

		final Runnable onWriteIdle;

		OutboundIdleStateHandler(long idleTimeout, Runnable onWriteIdle) {
			super(0, idleTimeout, 0, TimeUnit.MILLISECONDS);
			this.onWriteIdle = onWriteIdle;
		}

		@Override
		protected void channelIdle(ChannelHandlerContext ctx,
				IdleStateEvent evt) throws Exception {
			if (evt.state() == IdleState.WRITER_IDLE) {
				onWriteIdle.run();
			}
			super.channelIdle(ctx, evt);
		}
	}

	final static class InboundIdleStateHandler extends IdleStateHandler {

		final Runnable onReadIdle;

		InboundIdleStateHandler(long idleTimeout, Runnable onReadIdle) {
			super(idleTimeout, 0, 0, TimeUnit.MILLISECONDS);
			this.onReadIdle = onReadIdle;
		}

		@Override
		protected void channelIdle(ChannelHandlerContext ctx,
				IdleStateEvent evt) throws Exception {
			if (evt.state() == IdleState.READER_IDLE) {
				onReadIdle.run();
			}
			super.channelIdle(ctx, evt);
		}
	}

	static final Object TERMINATED = new TerminatedHandlerEvent();
}
