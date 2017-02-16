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
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Hold contextual information for the underlying {@link Channel}
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public interface NettyContext extends Disposable {

	/**
	 * Add a {@link ChannelHandler} to the beginning of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just after the reactor-added codecs. If a handler with a similar name already
	 * exists, this operation is skipped.
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
	 * exists, this operation is skipped.
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
	NettyContext addEncoder(String name, ChannelHandler handler);

	/**
	 * Add a {@link ChannelHandler} to the beginning of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just after the reactor-added codecs. If a handler with a similar name already
	 * exists, this operation replaces it.
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
	NettyContext setEncoder(String name, ChannelHandler handler);

	/**
	 * Add a {@link ChannelHandler} to the end of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just before the reactor-added handlers (like {@link NettyPipeline#ReactiveBridge}.
	 * If a handler with a similar name already exists, this operation is skipped.
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
	NettyContext addDecoder(String name, ChannelHandler handler);

	/**
	 * Add a {@link ChannelHandler} to the end of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just before the reactor-added handlers (like {@link NettyPipeline#ReactiveBridge}.
	 * If a handler with a similar name already exists, this operation replaces it.
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
	NettyContext setDecoder(String name, ChannelHandler handler);

	/**
	 * Return remote address if remote channel {@link NettyContext} otherwise local
	 * address if server selector channel.
	 *
	 * @return remote or local {@link InetSocketAddress}
	 */
	InetSocketAddress address();

	/**
	 * Return the underlying {@link Channel}. Direct interaction might be considered
	 * insecure if that affects the
	 * underlying IO processing such as read, write or close or state such as pipeline
	 * handler addition/removal.
	 *
	 * @return the underlying {@link Channel}
	 */
	Channel channel();

	/**
	 * Return an observing {@link Mono} terminating with success when shutdown
	 * successfully
	 * or error.
	 *
	 * @return a {@link Mono} terminating with success if shutdown successfully or error
	 */
	Mono<Void> onClose();

	/**
	 * Assign a {@link Runnable} to be invoked when the channel is closed.
	 *
	 * @param onClose the close event handler
	 *
	 * @return {@literal this}
	 */
	NettyContext onClose(Runnable onClose);
}
