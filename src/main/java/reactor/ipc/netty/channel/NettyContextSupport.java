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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.ByteToMessageDecoder;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * A support class to help with implementing a {@link NettyContext}.
 *
 * @author Simon Baslé
 */
class NettyContextSupport {

	static final Logger log = Loggers.getLogger(NettyContextSupport.class);

	/**
	 * A no-op hook that can be used with {@link #addDecoderBeforeReactorEndHandlers(Channel, String, ChannelHandler, Consumer, Consumer, boolean)}
	 * or {@link #addEncoderAfterReactorCodecs(Channel, String, ChannelHandler, Consumer, Consumer, boolean)}
	 * if no onClose hook is required.
	 */
	static final Consumer<Runnable> NO_ONCLOSE = r -> {};

	/**
	 * A no-op hook that can be used with {@link #addDecoderBeforeReactorEndHandlers(Channel, String, ChannelHandler, Consumer, Consumer, boolean)}
	 * or {@link #addEncoderAfterReactorCodecs(Channel, String, ChannelHandler, Consumer, Consumer, boolean)}
	 * if no onClose hook is required or no handler removal is necessary.
	 */
	static final Consumer<String>   NO_HANDLER_REMOVE = name -> {};

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
	 * @param channel the channel on which to add the decoder.
	 * @param name the name of the decoder.
	 * @param handler the decoder to add before the final reactor-specific handlers.
	 * @param onCloseHook the {@link NettyContext#onClose(Runnable)} method, or similar
	 * hook, to be used to register {@code removeCallback} for cleanup. Ignored if
	 * {@link #shouldCleanupOnClose(Channel)} returns false.
	 * @param removeCallback a callback that can be used to safely remove a specific
	 * handler by name, to be called from the {@code onCloseHook}.
	 * @param skipIfExist true to skip adding the handler if it exists (default) or false
	 * to replace the existing handler.
	 * @return
	 * @see NettyContext#addDecoder(String, ChannelHandler).
	 */
	static void addDecoderBeforeReactorEndHandlers(Channel channel, String name, ChannelHandler handler,
			Consumer<Runnable> onCloseHook, Consumer<String> removeCallback, boolean skipIfExist) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		String extractorName = name + "$extract";
		boolean exists = channel.pipeline().get(name) != null;
		boolean extractorExists = channel.pipeline().get(extractorName) != null;

		if (skipIfExist && exists) {
			if (log.isDebugEnabled()) {
				log.debug("Handler [{}] already exists in the pipeline, decoder has been skipped", name);
			}
			return;
		}

		boolean addExtractor = handler instanceof ByteToMessageDecoder
				|| handler instanceof ByteToMessageCodec
				|| handler instanceof CombinedChannelDuplexHandler;

		if (exists) {
			channel.pipeline().replace(name, name, handler);
			if (log.isDebugEnabled()) {
				log.debug("Handler [{}] was already present in the pipeline and has been replaced by the decoder, at the same position", name);
			}

			if (!addExtractor && extractorExists) {
				channel.pipeline().remove(extractorName);
				if (log.isDebugEnabled()) {
					log.debug("Unneeded extractor of replaced decoder removed");
				}
			}
			else if (addExtractor && !extractorExists) {
				//place the extractor just before the decoder and register for cleanup
				channel.pipeline().addBefore(name, extractorName, ByteBufHolderHandler.INSTANCE);
				registerForClose(shouldCleanupOnClose(channel), true, name, extractorName, onCloseHook, removeCallback);
				if (log.isDebugEnabled()) {
					log.debug("Missing extractor added for replacing decoder");
				}
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
			if (addExtractor) channel.pipeline().addLast(extractorName, ByteBufHolderHandler.INSTANCE);
			channel.pipeline().addLast(name, handler);
		}
		else {
			if (addExtractor) channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, extractorName, ByteBufHolderHandler.INSTANCE);
			channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, name, handler);
		}

		registerForClose(shouldCleanupOnClose(channel), addExtractor, name, extractorName, onCloseHook, removeCallback);

		if (log.isDebugEnabled()) {
			log.debug("Added decoder [{}]{} at the end of the user pipeline, full pipeline: {}",
					name, addExtractor ? " and extractor" : "",
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
	 * @param channel the channel on which to add the encoder.
	 * @param name the name of the encoder.
	 * @param handler the encoder to add after the initial reactor-specific handlers.
	 * @param onCloseHook the {@link NettyContext#onClose(Runnable)} method, or similar
	 * hook, to be used to register {@code removeCallback} for cleanup. Ignored if
	 * {@link #shouldCleanupOnClose(Channel)} returns false.
	 * @param removeCallback a callback that can be used to safely remove a specific
	 * handler by name, to be called from the {@code onCloseHook}.
	 * @param skipIfExist true to skip adding the handler if it exists (default) or false
	 * to replace the existing handler.
	 * @see NettyContext#addEncoder(String, ChannelHandler)
	 */
	static void addEncoderAfterReactorCodecs(Channel channel, String name, ChannelHandler handler,
			Consumer<Runnable> onCloseHook, Consumer<String> removeCallback, boolean skipIfExist) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		String extractorName = name + "$extract";
		boolean exists = channel.pipeline().get(name) != null;
		boolean extractorExists = channel.pipeline().get(extractorName) != null;

		if (skipIfExist && exists) {
			if (log.isDebugEnabled()) {
				log.debug("Handler [{}] already exists in the pipeline, encoder has been skipped", name);
			}
			return;
		}

		boolean addExtractor = handler instanceof ByteToMessageDecoder
				|| handler instanceof ByteToMessageCodec
				|| handler instanceof CombinedChannelDuplexHandler;

		if (exists) {
			channel.pipeline().replace(name, name, handler);
			if (log.isDebugEnabled()) {
				log.debug("Handler [{}] was already present in the pipeline and has been replaced by the encoder, at the same position", name);
			}

			if (!addExtractor && extractorExists) {
				channel.pipeline().remove(extractorName);
				if (log.isDebugEnabled()) {
					log.debug("Unneeded extractor of replaced encoder removed");
				}
			}
			else if (addExtractor && !extractorExists) {
				//place the extractor just before the decoder and register for cleanup
				channel.pipeline().addBefore(name, extractorName, ByteBufHolderHandler.INSTANCE);
				registerForClose(shouldCleanupOnClose(channel), true, name, extractorName, onCloseHook, removeCallback);
				if (log.isDebugEnabled()) {
					log.debug("Missing extractor added for replacing encoder");
				}
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
				//place the extractor just before the encoder
			if (addExtractor) channel.pipeline().addFirst(extractorName, ByteBufHolderHandler.INSTANCE);
		}
		else {
			channel.pipeline().addAfter(after, name, handler);
				//place the extractor just before the encoder
			if (addExtractor) channel.pipeline().addAfter(after, extractorName, ByteBufHolderHandler.INSTANCE);
		}

		registerForClose(shouldCleanupOnClose(channel), addExtractor, name, extractorName, onCloseHook, removeCallback);

		if (log.isDebugEnabled()) {
			log.debug("Added encoder [{}]{} at the beginning of the user pipeline, full pipeline: {}",
					name, addExtractor ? " and extractor" : "",
					channel.pipeline().names());
		}
	}

	static void registerForClose(boolean shouldCleanupOnClose, boolean addExtractor,
			String name, String extractorName,
			Consumer<Runnable> onCloseHook, Consumer<String> removeCallback) {
		if (!shouldCleanupOnClose) return;

		if (addExtractor) {
			onCloseHook.accept(() -> {
				removeCallback.accept(name);
				removeCallback.accept(extractorName);
			});
		}
		else {
			onCloseHook.accept(() -> removeCallback.accept(name));
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
