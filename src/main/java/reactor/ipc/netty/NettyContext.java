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

import java.net.InetSocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.channel.NettyContextSupport;

import static reactor.ipc.netty.channel.NettyContextSupport.ADD_EXTRACTOR;

/**
 * Hold contextual information for the underlying {@link Channel}
 *
 * @author Stephane Maldini
 * @since 0.6
 */
@FunctionalInterface
public interface NettyContext extends Disposable {

	/**
	 * Add a {@link ChannelHandler} to the end of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just before the reactor-added handlers (like {@link NettyPipeline#ReactiveBridge}.
	 * If a handler with a similar name already exists, this operation is skipped.
	 * Some handlers that are identified as taking a {@link io.netty.buffer.ByteBuf} as
	 * input will additionally have an extractor handler added before them.
	 * <p>
	 * {@code [ [reactor codecs], [<- user ENCODERS added here, user DECODERS added here ->], [reactor handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 *
	 * @param handler handler instance
	 *
	 * @return this NettyContext

	 */
	default NettyContext addDecoder(ChannelHandler handler){
		return addDecoder(handler.getClass().getSimpleName(), handler);
	}

	/**
	 * Add a {@link ChannelHandler} to the end of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just before the reactor-added handlers (like {@link NettyPipeline#ReactiveBridge}.
	 * If a handler with a similar name already exists, this operation is skipped.
	 * Some handlers that are identified as taking a {@link io.netty.buffer.ByteBuf} as
	 * input will additionally have an extractor handler added before them.
	 * <p>
	 * {@code [ [reactor codecs], [<- user ENCODERS added here, user DECODERS added here ->], [reactor handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 *
	 * @param name handler name
	 * @param handler handler instance
	 *
	 * @return this NettyContext
	 */
	default NettyContext addDecoder(String name, ChannelHandler handler){
		NettyContextSupport.addDecoderBeforeReactorEndHandlers(this, name, handler, ADD_EXTRACTOR);
		return this;
	}

	/**
	 * Add a {@link ChannelHandler} to the beginning of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just after the reactor-added codecs. If a handler with a similar name already
	 * exists, this operation is skipped. Some handlers that are identified as taking a
	 * {@link io.netty.buffer.ByteBuf} as input will additionally have an extractor handler
	 * added before them.
	 * <p>
	 * {@code [ [reactor codecs], [<- user ENCODERS added here, user DECODERS added here ->], [reactor handlers] ]}.
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 *
	 * @param handler handler instance
	 *
	 * @return this NettyContext
	 */
	default NettyContext addEncoder(ChannelHandler handler){
		return addEncoder(handler.getClass().getSimpleName(), handler);
	}

	/**
	 * Add a {@link ChannelHandler} to the beginning of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just after the reactor-added codecs. If a handler with a similar name already
	 * exists, this operation is skipped. Some handlers that are identified as taking a
	 * {@link io.netty.buffer.ByteBuf} as input will additionally have an extractor handler
	 * added before them.
	 * <p>
	 * {@code [ [reactor codecs], [<- user ENCODERS added here, user DECODERS added here ->], [reactor handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 *
	 * @param name handler name
	 * @param handler handler instance
	 *
	 * @return this NettyContext
	 */
	default NettyContext addEncoder(String name, ChannelHandler handler){
		NettyContextSupport.addEncoderAfterReactorCodecs(this, name, handler, ADD_EXTRACTOR);
		return this;
	}

	/**
	 * Return remote address if remote channel {@link NettyContext} otherwise local
	 * address if server selector channel.
	 *
	 * @return remote or local {@link InetSocketAddress}
	 */
	default InetSocketAddress address(){
		Channel c = channel();
		if (c instanceof SocketChannel) {
			return ((SocketChannel) c).remoteAddress();
		}
		if (c instanceof ServerSocketChannel) {
			return ((ServerSocketChannel) c).localAddress();
		}
		throw new IllegalStateException("Does not have an InetSocketAddress");
	}

	/**
	 * Return the underlying {@link Channel}. Direct interaction might be considered
	 * insecure if that affects the
	 * underlying IO processing such as read, write or close or state such as pipeline
	 * handler addition/removal.
	 *
	 * @return the underlying {@link Channel}
	 */
	Channel channel();

	@Override
	default void dispose() {
		channel().close();
	}

	@Override
	default boolean isDisposed() {
		return !channel().isActive();
	}

	/**
	 * Return an observing {@link Mono} terminating with success when shutdown
	 * successfully
	 * or error.
	 *
	 * @return a {@link Mono} terminating with success if shutdown successfully or error
	 */
	default Mono<Void> onClose(){
		return FutureMono.from(channel().closeFuture());
	}

	/**
	 * Assign a {@link Runnable} to be invoked when the channel is closed.
	 *
	 * @param onClose the close event handler
	 *
	 * @return {@literal this}
	 */
	default NettyContext onClose(Runnable onClose){
		onClose().subscribe(null, e -> onClose.run(), onClose);
		return this;
	}

	/**
	 * Remove a named handler if present and return this context
	 *
	 * @param name handler name
	 *
	 * @return this NettyContext
	 */
	default NettyContext removeHandler(String name) {
		NettyContextSupport.removeHandler(channel(), name);
		return this;
	}
}
