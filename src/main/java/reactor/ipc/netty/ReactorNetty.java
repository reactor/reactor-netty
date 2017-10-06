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
package reactor.ipc.netty;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Internal helpers for reactor-netty contracts
 *
 * @author Stephane Maldini
 */
final class ReactorNetty {

	static final AttributeKey<Boolean> PERSISTENT_CHANNEL = AttributeKey.newInstance("PERSISTENT_CHANNEL");

	/**
	 * A common implementation for the {@link Connection#addHandlerLast(String, ChannelHandler)}
	 * method that can be reused by other implementors.
	 * <p>
	 * This implementation will look for reactor added handlers on the right hand side of
	 * the pipeline, provided they are identified with the {@link NettyPipeline#RIGHT}
	 * prefix, and add the handler just before the first of these.
	 *
	 * @param context the {@link Connection} on which to add the decoder.
	 * @param name the name of the decoder.
	 * @param handler the decoder to add before the final reactor-specific handlers.
	 * @see Connection#addHandlerLast(String, ChannelHandler).
	 */
	static void addHandlerBeforeReactorEndHandlers(Connection context, String
			name,	ChannelHandler handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		Channel channel = context.channel();
		boolean exists = channel.pipeline().get(name) != null;

		if (exists) {
			if (log.isDebugEnabled()) {
				log.debug("Handler [{}] already exists in the pipeline, decoder has been skipped", name);
			}
			return;
		}

		//we need to find the correct position
		String before = null;
		for (String s : channel.pipeline().names()) {
			if (s.startsWith(NettyPipeline.RIGHT)) {
				before = s;
				break;
			}
		}

		if (before == null) {
			channel.pipeline().addLast(name, handler);
		}
		else {
			channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, name, handler);
		}

		registerForClose(shouldCleanupOnClose(channel),  name, context);

		if (log.isDebugEnabled()) {
			log.debug("Added decoder [{}] at the end of the user pipeline, full pipeline: {}",
					name,
					channel.pipeline().names());
		}
	}

	/**
	 * A common implementation for the {@link Connection#addHandlerFirst(String, ChannelHandler)}
	 * method that can be reused by other implementors.
	 * <p>
	 * This implementation will look for reactor added handlers on the left hand side of
	 * the pipeline, provided they are identified with the {@link NettyPipeline#LEFT}
	 * prefix, and add the handler just after the last of these.
	 *
	 * @param context the {@link Connection} on which to add the decoder.
	 * @param name the name of the encoder.
	 * @param handler the encoder to add after the initial reactor-specific handlers.
	 * @see Connection#addHandlerFirst(String, ChannelHandler)
	 */
	static void addHandlerAfterReactorCodecs(Connection context, String
			name,
			ChannelHandler handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		Channel channel = context.channel();
		boolean exists = channel.pipeline().get(name) != null;

		if (exists) {
			if (log.isDebugEnabled()) {
				log.debug("Handler [{}] already exists in the pipeline, encoder has been skipped", name);
			}
			return;
		}

		//we need to find the correct position
		String after = null;
		for (String s : channel.pipeline().names()) {
			if (s.startsWith(NettyPipeline.LEFT)) {
				after = s;
			}
		}

		if (after == null) {
			channel.pipeline().addFirst(name, handler);
		}
		else {
			channel.pipeline().addAfter(after, name, handler);
		}

		registerForClose(shouldCleanupOnClose(channel), name, context);

		if (log.isDebugEnabled()) {
			log.debug("Added encoder [{}] at the beginning of the user pipeline, full pipeline: {}",
					name,
					channel.pipeline().names());
		}
	}

	static void registerForClose(boolean shouldCleanupOnClose,
			String name,
			Connection context) {
		if (!shouldCleanupOnClose) return;
		context.onClose(() -> context.removeHandler(name));
	}

	static void removeHandler(Channel channel, String name){
		if (channel.isActive() && channel.pipeline()
		                                 .context(name) != null) {
			channel.pipeline()
			       .remove(name);
			if (log.isDebugEnabled()) {
				log.debug("{} Removed handler: {}, pipeline: {}",
						channel,
						name,
						channel.pipeline());
			}
		}
		else if (log.isDebugEnabled()) {
			log.debug(" Non Removed handler: {}, context: {}, pipeline: {}",
					channel,
					name,
					channel.pipeline()
					       .context(name),
					channel.pipeline());
		}
	}

	static void replaceHandler(Channel channel, String name, ChannelHandler handler){
		if (channel.isActive() && channel.pipeline()
		                                 .context(name) != null) {
			channel.pipeline()
			       .replace(name, name, handler);
			if (log.isDebugEnabled()) {
				log.debug("{} Replaced handler: {}, pipeline: {}",
						channel,
						name,
						channel.pipeline());
			}
		}
		else if (log.isDebugEnabled()) {
			log.debug(" Non Replaced handler: {}, context: {}, pipeline: {}",
					channel,
					name,
					channel.pipeline()
					       .context(name),
					channel.pipeline());
		}
	}

	/**
	 * Determines if user-provided handlers registered on the given channel should
	 * automatically be registered for removal through a {@link Connection#onClose(Runnable)}
	 * (or similar on close hook). This depends on the
	 * {@link Connection#isPersistent(Channel)} ()}
	 * attribute.
	 */
	static boolean shouldCleanupOnClose(Channel channel) {
		boolean registerForClose = true;
		if (!Connection.isPersistent(channel)) {
			registerForClose = false;
		}
		return registerForClose;
	}

	ReactorNetty(){
	}

	static final class TerminatedHandlerEvent {
		@Override
		public String toString() {
			return "[Handler Terminated]";
		}
	}

	static final class ResponseWriteCompleted {
		@Override
		public String toString() {
			return "[Response Write Completed]";
		}
	}

	/**
	 * An appending write that delegates to its origin context and append the passed
	 * publisher after the origin success if any.
	 */
	static final class OutboundThen implements NettyOutbound {

		final Connection sourceContext;
		final Mono<Void> thenMono;

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
		public Connection context() {
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

	static final Object TERMINATED                 = new TerminatedHandlerEvent();
	static final Object RESPONSE_COMPRESSION_EVENT = new ResponseWriteCompleted();
	static final Logger log                        = Loggers.getLogger(ReactorNetty.class);

	/**
	 * A handler that can be used to extract {@link ByteBuf} out of {@link ByteBufHolder},
	 * optionally also outputting additional messages
	 *
	 * @author Stephane Maldini
	 * @author Simon Baslé
	 */
	@ChannelHandler.Sharable
	static final class ExtractorHandler extends ChannelInboundHandlerAdapter {

		final BiConsumer<? super ChannelHandlerContext, Object> extractor;

		ExtractorHandler(BiConsumer<? super ChannelHandlerContext, Object> extractor) {
			this.extractor = Objects.requireNonNull(extractor, "extractor");
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			extractor.accept(ctx, msg);
		}
	}
}
