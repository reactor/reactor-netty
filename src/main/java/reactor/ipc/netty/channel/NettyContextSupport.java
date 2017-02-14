package reactor.ipc.netty.channel;

import java.util.Objects;
import java.util.function.Consumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;
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
	 * A no-op hook that can be used with {@link #addDecoderBeforeReactorEndHandlers(Channel, String, ChannelHandler, Consumer, Consumer)}
	 * or {@link #addEncoderAfterReactorCodecs(Channel, String, ChannelHandler, Consumer, Consumer)}
	 * if no onClose hook is required.
	 */
	public static final Consumer<Runnable> NO_ONCLOSE = r -> {};

	/**
	 * A no-op hook that can be used with {@link #addDecoderBeforeReactorEndHandlers(Channel, String, ChannelHandler, Consumer, Consumer)}
	 * or {@link #addEncoderAfterReactorCodecs(Channel, String, ChannelHandler, Consumer, Consumer)}
	 * if no onClose hook is required or no handler removal is necessary.
	 */
	public static final Consumer<String>   NO_HANDLER_REMOVE = name -> {};

	/**
	 * A common implementation for the {@link NettyContext#addDecoder(String, ChannelHandler)}
	 * method that can be reused by other implementors.
	 * <p>
	 * This implementation will look for reactor added handlers on the right hand side of
	 * the pipeline, provided they are identified with the {@link NettyPipeline#RIGHT}
	 * prefix, and add the handler just before the first of these.
	 *
	 * @param channel the channel on which to add the decoder.
	 * @param name the name of the decoder.
	 * @param handler the decoder to add before the final reactor-specific handlers.
	 * @param onCloseHook the {@link NettyContext#onClose(Runnable)} method, or similar
	 * hook, to be used to register {@code removeCallback} for cleanup. Ignored if
	 * {@link #shouldCleanupOnClose(Channel)} returns false.
	 * @param removeCallback a callback that can be used to safely remove a specific
	 * handler by name, to be called from the {@code onCloseHook}.
	 * @return
	 * @see NettyContext#addDecoder(String, ChannelHandler).
	 */
	public static void addDecoderBeforeReactorEndHandlers(Channel channel, String name, ChannelHandler handler,
			Consumer<Runnable> onCloseHook, Consumer<String> removeCallback) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		boolean registerForClose = shouldCleanupOnClose(channel);

		String before = null;
		for (String s : channel.pipeline().names()) {
			if (s.startsWith(NettyPipeline.RIGHT)) {
				before = s;
				break;
			}
		}

		if (handler instanceof ByteToMessageDecoder
				|| handler instanceof ByteToMessageCodec
				|| handler instanceof CombinedChannelDuplexHandler) {
			String extractorName = name + "$extract";
			if (before == null) {
				channel.pipeline().addLast(extractorName, ByteBufHolderHandler.INSTANCE);
				channel.pipeline().addLast(name, handler);
			}
			else {
				channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, extractorName, ByteBufHolderHandler.INSTANCE);
				channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, name, handler);
			}

			if (registerForClose) {
				onCloseHook.accept(() -> {
					removeCallback.accept(name);
					removeCallback.accept(extractorName);
				});
			}
		}
		else {
			if (before == null) {
				channel.pipeline().addLast(name, handler);
			}
			else {
				channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, name, handler);
			}

			if (registerForClose) {
				onCloseHook.accept(() -> removeCallback.accept(name));
			}
		}

		if (log.isDebugEnabled()) {
			log.debug("Added decoder [{}] at the end of the user pipeline, full pipeline: {}",
					name,
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
	 *
	 * @param channel the channel on which to add the encoder.
	 * @param name the name of the encoder.
	 * @param handler the encoder to add after the initial reactor-specific handlers.
	 * @param onCloseHook the {@link NettyContext#onClose(Runnable)} method, or similar
	 * hook, to be used to register {@code removeCallback} for cleanup. Ignored if
	 * {@link #shouldCleanupOnClose(Channel)} returns false.
	 * @param removeCallback a callback that can be used to safely remove a specific
	 * handler by name, to be called from the {@code onCloseHook}.
	 * @see NettyContext#addEncoder(String, ChannelHandler)
	 */
	public static void addEncoderAfterReactorCodecs(Channel channel, String name, ChannelHandler handler,
			Consumer<Runnable> onCloseHook, Consumer<String> removeCallback) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		boolean registerForClose = shouldCleanupOnClose(channel);

		String after = null;
		for (String s : channel.pipeline().names()) {
			if (s.startsWith(NettyPipeline.LEFT)) {
				after = s;
			}
		}

		if (handler instanceof ByteToMessageDecoder
				|| handler instanceof ByteToMessageCodec
				|| handler instanceof CombinedChannelDuplexHandler) {
			String extractorName = name + "$extract";
			if (after == null) {
				channel.pipeline().addFirst(name, handler);
				//place the extractor just before the encoder
				channel.pipeline().addFirst(extractorName, ByteBufHolderHandler.INSTANCE);
			}
			else {
				channel.pipeline().addAfter(after, name, handler);
				//place the extractor just before the encoder
				channel.pipeline().addAfter(after, extractorName, ByteBufHolderHandler.INSTANCE);
			}

			if (registerForClose) {
				onCloseHook.accept(() -> {
					removeCallback.accept(name);
					removeCallback.accept(extractorName);
				});
			}
		}
		else {
			if (after == null) {
				channel.pipeline().addFirst(name, handler);
			}
			else {
				channel.pipeline().addAfter(after, name, handler);
			}

			if (registerForClose) {
				onCloseHook.accept(() -> removeCallback.accept(name));
			}
		}

		if (log.isDebugEnabled()) {
			log.debug("Added encoder [{}] at the beginning of the user pipeline, full pipeline: {}",
					name,
					channel.pipeline().names());
		}
	}

	/**
	 * Determines if user-provided handlers registered on the given channel should
	 * automatically be registered for removal through a {@link NettyContext#onClose(Runnable)}
	 * (or similar on close hook). This depends on the {@link ContextHandler#CLOSE_CHANNEL} attribute.
	 */
	public static final boolean shouldCleanupOnClose(Channel channel) {
		boolean registerForClose = true;
		if (channel.attr(ContextHandler.CLOSE_CHANNEL).get() == Boolean.TRUE) {
			registerForClose = false;
		}
		return registerForClose;
	}

}
