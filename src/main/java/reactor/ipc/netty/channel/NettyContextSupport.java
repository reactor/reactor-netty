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

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.LastHttpContent;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * A support class to help with implementing a {@link NettyContext}.
 *
 * @author Simon Baslé
 */
public class NettyContextSupport {

	static final Logger log = Loggers.getLogger(NettyContextSupport.class);

	/**
	 * A function used to add an extra handler for encoders/decoders that take ByteBuf as input.
	 * The handler will extract the content of messages in the pipeline provided they
	 * implement {@link ByteBufHolder}. Warning: depending on the whole pipeline, this can
	 * potentially result in a never completing sequence as {@link NettyPipeline#ReactiveBridge}
	 * uses the {@link LastHttpContent} to detect the flux boundary.
	 */
	public static final Function<ChannelHandler, ChannelHandler> ADD_EXTRACTOR = handler -> (handler instanceof ByteToMessageDecoder
			|| handler instanceof ByteToMessageCodec
			|| handler instanceof CombinedChannelDuplexHandler) ? ByteBufHolderHandler.INSTANCE : null;

	/**
	 * A function used to add an extra handler for encoders/decoders that take ByteBuf as input.
	 * The handler will extract the content of messages in the pipeline provided they
	 * implement {@link ByteBufHolder}, and will additionally preserve {@link LastHttpContent}
	 * messages at the end (first extracting the content of such a message then outputting an
	 * empty {@link LastHttpContent}).
	 */
	public static final Function<ChannelHandler, ChannelHandler> HTTP_EXTRACTOR = handler ->
			(handler instanceof ByteToMessageDecoder
			|| handler instanceof ByteToMessageCodec
			|| handler instanceof CombinedChannelDuplexHandler) ? new ByteBufHolderHandler(ByteBufHolderHandler.HTTP_LAST_REPLAY) : null;

	public static void removeHandler(Channel channel, String name){
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

	/**
	 * A common implementation for the {@link NettyContext#addDecoder(String, ChannelHandler)}
	 * method that can be reused by other implementors.
	 * <p>
	 * This implementation will look for reactor added handlers on the right hand side of
	 * the pipeline, provided they are identified with the {@link NettyPipeline#RIGHT}
	 * prefix, and add the handler just before the first of these.
	 * <p>
	 * It will also add a ByteBuf extractor for relevant encoders (and add/remove it as
	 * relevant if the handler is replaced rather than added/skipped).
	 *
	 * @param context the {@link NettyContext} on which to add the decoder.
	 * @param name the name of the decoder.
	 * @param handler the decoder to add before the final reactor-specific handlers.
	 * @param addExtractor a function to decide whether or not to also add an extractor handler (see {@link #ADD_EXTRACTOR}).
	 * @see NettyContext#addDecoder(String, ChannelHandler).
	 */
	public static final void addDecoderBeforeReactorEndHandlers(NettyContext context, String
			name,	ChannelHandler handler,
			Function<ChannelHandler, ChannelHandler> addExtractor) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		String extractorName = name + "$extract";
		Channel channel = context.channel();
		boolean exists = channel.pipeline().get(name) != null;

		if (exists) {
			if (log.isDebugEnabled()) {
				log.debug("Handler [{}] already exists in the pipeline, decoder has been skipped", name);
			}
			return;
		}

		ChannelHandler extractor = addExtractor.apply(handler);
		boolean shouldAddExtractor = extractor != null;

		//we need to find the correct position
		String before = null;
		for (String s : channel.pipeline().names()) {
			if (s.startsWith(NettyPipeline.RIGHT)) {
				before = s;
				break;
			}
		}

		if (before == null) {
			if (shouldAddExtractor) channel.pipeline().addLast(extractorName, extractor);
			channel.pipeline().addLast(name, handler);
		}
		else {
			if (shouldAddExtractor) channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, extractorName, extractor);
			channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, name, handler);
		}

		registerForClose(shouldCleanupOnClose(channel), shouldAddExtractor, name,
				extractorName, context);

		if (log.isDebugEnabled()) {
			log.debug("Added decoder [{}]{} at the end of the user pipeline, full pipeline: {}",
					name, shouldAddExtractor ? " and extractor" : "",
					channel.pipeline().names());
		}
	}

	/**
	 * A common implementation for the {@link NettyContext#addEncoder(String, ChannelHandler)}
	 * method that can be reused by other implementors.
	 * <p>
	 * This implementation will look for reactor added handlers on the left hand side of
	 * the pipeline, provided they are identified with the {@link NettyPipeline#LEFT}
	 * prefix, and add the handler just after the last of these.
	 * <p>
	 * It will also add a ByteBuf extractor for relevant encoders (and add/remove it as
	 * relevant if the handler is replaced rather than added/skipped).
	 *
	 * @param context the {@link NettyContext} on which to add the decoder.
	 * @param name the name of the encoder.
	 * @param handler the encoder to add after the initial reactor-specific handlers.
	 * @param addExtractor a function to decide whether or not to also add an extractor handler (see {@link #ADD_EXTRACTOR}).
	 * @see NettyContext#addEncoder(String, ChannelHandler)
	 */
	public static final void addEncoderAfterReactorCodecs(NettyContext context, String
			name,
			ChannelHandler handler,
			Function<ChannelHandler, ChannelHandler> addExtractor) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		String extractorName = name + "$extract";
		Channel channel = context.channel();
		boolean exists = channel.pipeline().get(name) != null;

		if (exists) {
			if (log.isDebugEnabled()) {
				log.debug("Handler [{}] already exists in the pipeline, encoder has been skipped", name);
			}
			return;
		}

		ChannelHandler extractor = addExtractor.apply(handler);
		boolean shouldAddExtractor = extractor != null;

		//we need to find the correct position
		String after = null;
		for (String s : channel.pipeline().names()) {
			if (s.startsWith(NettyPipeline.LEFT)) {
				after = s;
			}
		}

		if (after == null) {
			channel.pipeline().addFirst(name, handler);
				//place the extractor just before the encoder
			if (shouldAddExtractor) channel.pipeline().addFirst(extractorName, extractor);
		}
		else {
			channel.pipeline().addAfter(after, name, handler);
				//place the extractor just before the encoder
			if (shouldAddExtractor) channel.pipeline().addAfter(after, extractorName, extractor);
		}

		registerForClose(shouldCleanupOnClose(channel), shouldAddExtractor, name, extractorName, context);

		if (log.isDebugEnabled()) {
			log.debug("Added encoder [{}]{} at the beginning of the user pipeline, full pipeline: {}",
					name, shouldAddExtractor ? " and extractor" : "",
					channel.pipeline().names());
		}
	}

	static void registerForClose(boolean shouldCleanupOnClose, boolean addExtractor,
			String name, String extractorName,
			NettyContext context) {
		if (!shouldCleanupOnClose) return;

		if (addExtractor) {
			context.onClose(() -> {
				context.removeHandler(name);
				context.removeHandler(extractorName);
			});
		}
		else {
			context.onClose(() -> context.removeHandler(name));
		}
	}

	/**
	 * Determines if user-provided handlers registered on the given channel should
	 * automatically be registered for removal through a {@link NettyContext#onClose(Runnable)}
	 * (or similar on close hook). This depends on the {@link ContextHandler#CLOSE_CHANNEL} attribute.
	 */
	static final boolean shouldCleanupOnClose(Channel channel) {
		boolean registerForClose = true;
		if (channel.attr(ContextHandler.CLOSE_CHANNEL).get() == Boolean.TRUE) {
			registerForClose = false;
		}
		return registerForClose;
	}

}
