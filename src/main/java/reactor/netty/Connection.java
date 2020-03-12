/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty;

import javax.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOutboundHandler;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
/**
 * Hold contextual information for the underlying {@link Channel}
 *
 * @author Stephane Maldini
 * @since 0.8
 */
@FunctionalInterface
public interface Connection extends DisposableChannel {

	/**
	 * Return an existing {@link Connection} wrapper or create a simple new one
	 *
	 * @param channel channel to retrieve the connection reference from
	 *
	 * @return an existing {@link Connection} wrapper or create a simple new one
	 */
	static Connection from(Channel channel) {
		if(channel.hasAttr(ReactorNetty.CONNECTION)) {
			return channel.attr(ReactorNetty.CONNECTION)
			              .get();
		}
		return new ReactorNetty.SimpleConnection(channel).bind();
	}

	/**
	 * Return an existing {@link Connection} that must match the given type wrapper or
	 * null.
	 *
	 * @param clazz connection type to match to
	 *
	 * @return a matching {@link Connection} reference or null
	 */
	@Nullable
	default  <T extends Connection> T as(Class<T> clazz) {
		if(clazz.isAssignableFrom(this.getClass())) {
			@SuppressWarnings("unchecked")
			T thiz = (T) this;
			return thiz;
		}
		return null;
	}

	/**
	 * Add a {@link ChannelHandler} with {@link #addHandlerFirst} if of type of
	 * {@link io.netty.channel.ChannelOutboundHandler} otherwise with
	 * {@link #addHandlerLast}. Implementation may add more auto handling in particular
	 * HTTP based context will prepend an HttpContent body extractor.
	 * <p>
	 * {@code [ [reactor codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [reactor handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 * As the Connection object is available once the channel is in active state, events prior this state
	 * will not be available (i.e. {@code channelRegistered}, {@code initChannel}, {@code channelActive}, etc.)
	 *
	 * @param handler handler instance
	 *
	 * @return this Connection

	 */
	default Connection addHandler(ChannelHandler handler){
		return addHandler(handler.getClass().getSimpleName(), handler);
	}

	/**
	 * Add a {@link ChannelHandler} with {@link #addHandlerFirst} if of type of
	 * {@link io.netty.channel.ChannelOutboundHandler} otherwise with
	 * {@link #addHandlerLast}. Implementation may add more auto handling in particular
	 * HTTP based context will prepend an HttpContent body extractor.
	 * <p>
	 * {@code [ [reactor codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [reactor handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 * As the Connection object is available once the channel is in active state, events prior this state
	 * will not be available (i.e. {@code channelRegistered}, {@code initChannel}, {@code channelActive}, etc.)
	 *
	 * @param name handler name
	 * @param handler handler instance
	 *
	 * @return this Connection
	 */
	default Connection addHandler(String name, ChannelHandler handler){
		if(handler instanceof ChannelOutboundHandler){
			addHandlerFirst(name, handler);
		}
		else {
			addHandlerLast(name, handler);
		}
		return this;
	}

	/**
	 * Add a {@link ChannelHandler} to the end of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just before the reactor-added handlers (like {@link NettyPipeline#ReactiveBridge}.
	 * If a handler with a similar name already exists, this operation is skipped.
	 * <p>
	 * {@code [ [reactor codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [reactor handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 * As the Connection object is available once the channel is in active state, events prior this state
	 * will not be available (i.e. {@code channelRegistered}, {@code initChannel}, {@code channelActive}, etc.)
	 *
	 * @param handler handler instance
	 *
	 * @return this Connection

	 */
	default Connection addHandlerLast(ChannelHandler handler){
		return addHandlerLast(handler.getClass().getSimpleName(), handler);
	}

	/**
	 * Add a {@link ChannelHandler} to the end of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just before the reactor-added handlers (like {@link NettyPipeline#ReactiveBridge}.
	 * If a handler with a similar name already exists, this operation is skipped.
	 * <p>
	 * {@code [ [reactor codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [reactor handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 * As the Connection object is available once the channel is in active state, events prior this state
	 * will not be available (i.e. {@code channelRegistered}, {@code initChannel}, {@code channelActive}, etc.)
	 *
	 * @param name handler name
	 * @param handler handler instance
	 *
	 * @return this Connection
	 */
	default Connection addHandlerLast(String name, ChannelHandler handler){
		ReactorNetty.addHandlerBeforeReactorEndHandlers(this, name, handler);
		return this;
	}

	/**
	 * Add a {@link ChannelHandler} to the beginning of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just after the reactor-added codecs. If a handler with a similar name already
	 * exists, this operation is skipped. 
	 * <p>
	 * {@code [ [reactor codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [reactor handlers] ]}.
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 * As the Connection object is available once the channel is in active state, events prior this state
	 * will not be available (i.e. {@code channelRegistered}, {@code initChannel}, {@code channelActive}, etc.)
	 *
	 * @param handler handler instance
	 *
	 * @return this Connection
	 */
	default Connection addHandlerFirst(ChannelHandler handler){
		return addHandlerFirst(handler.getClass().getSimpleName(), handler);
	}

	/**
	 * Add a {@link ChannelHandler} to the beginning of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just after the reactor-added codecs. If a handler with a similar name already
	 * exists, this operation is skipped. 
	 * <p>
	 * {@code [ [reactor codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [reactor handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 * As the Connection object is available once the channel is in active state, events prior this state
	 * will not be available (i.e. {@code channelRegistered}, {@code initChannel}, {@code channelActive}, etc.)
	 *
	 * @param name handler name
	 * @param handler handler instance
	 *
	 * @return this Connection
	 */
	default Connection addHandlerFirst(String name, ChannelHandler handler){
		ReactorNetty.addHandlerAfterReactorCodecs(this, name, handler);
		return this;
	}

	/**
	 * Bind the {@link Connection} to the channel scope via an attribute. Can be
	 * retrieved later by {@link #from}. If a previous reference {@link Connection} was
	 * bound, this instance will take precedence.
	 *
	 * @return this {@link Connection}.
	 */
	default Connection bind() {
		channel().attr(ReactorNetty.CONNECTION)
		         .set(this);
		return this;
	}

	/**
	 * Return the {@link NettyInbound} read API from this connection. If
	 * {@link Connection} has not been configured with a supporting bridge, receive
	 * operations will be unavailable.
	 *
	 * @return  the {@link NettyInbound} read API from this connection.
	 */
	default NettyInbound inbound() {
		return ReactorNetty.unavailableInbound(this);
	}



	/**
	 * Return false if it will force a close on terminal protocol events thus defeating
	 * any pooling strategy
	 * Return true (default) if it will release on terminal protocol events thus
	 * keeping alive the channel if possible.
	 *
	 * @return whether or not the underlying {@link Connection} will be disposed on
	 * terminal handler event
	 */
	default boolean isPersistent() {
		return !channel().hasAttr(ReactorNetty.PERSISTENT_CHANNEL) ||
				channel().attr(ReactorNetty.PERSISTENT_CHANNEL).get();
	}


	/**
	 * Mark the underlying channel as persistent or not.
	 * If false, it will force a close on terminal protocol events thus defeating
	 * any pooling strategy
	 * if true (default), it will release on terminal protocol events thus
	 * keeping alive the channel if possible.
	 *
	 * @param persist the boolean flag to mark the {@link Channel} as fully disposable
	 * or reusable when a user handler has terminated
	 *
	 * @return this Connection
	 */
	default Connection markPersistent(boolean persist){
		if(persist && !channel().hasAttr(ReactorNetty.PERSISTENT_CHANNEL)) {
			return this;
		}
		else {
			channel().attr(ReactorNetty.PERSISTENT_CHANNEL)
			         .set(persist);
		}
		return this;
	}

	@Override
	default Connection onDispose(Disposable onDispose) {
		DisposableChannel.super.onDispose(onDispose);
		return this;
	}

	/**
	 * Assign a {@link Runnable} to be invoked when reads have become idle for the given
	 * timeout. This replaces any previously set idle callback.
	 *
	 * @param idleTimeout the idle timeout
	 * @param onReadIdle the idle timeout handler
	 *
	 * @return {@literal this}
	 */
	default Connection onReadIdle(long idleTimeout, Runnable onReadIdle) {
		return removeHandler(NettyPipeline.OnChannelReadIdle)
				 .addHandlerFirst(NettyPipeline.OnChannelReadIdle,
						 new ReactorNetty.InboundIdleStateHandler(idleTimeout, onReadIdle));
	}

	/**
	 * Return a Mono succeeding when a {@link Connection} is not used anymore by any
	 * current operations. A typical example is when a pooled connection is released or
	 * an operations bridge has terminated.
	 *
	 * @return a Mono succeeding when a {@link Connection} has been terminated
	 */
	default Mono<Void> onTerminate() {
		return onDispose();
	}

	/**
	 * Assign a {@link Runnable} to be invoked when writes have become idle for the given
	 * timeout. This replaces any previously set idle callback.
	 *
	 * @param idleTimeout the idle timeout
	 * @param onWriteIdle the idle timeout handler
	 *
	 * @return {@literal this}
	 */
	default Connection onWriteIdle(long idleTimeout, Runnable onWriteIdle) {
		return removeHandler(NettyPipeline.OnChannelWriteIdle)
				 .addHandlerFirst(NettyPipeline.OnChannelWriteIdle,
						 new ReactorNetty.OutboundIdleStateHandler(idleTimeout, onWriteIdle));
	}

	/**
	 * Return the {@link NettyOutbound} write API from this connection. If
	 * {@link Connection} has not been configured with a supporting bridge, send
	 * operations will be unavailable.
	 *
	 * @return  the {@link NettyOutbound} read API from this connection.
	 */
	default NettyOutbound outbound() {
		return ReactorNetty.unavailableOutbound(this);
	}

	/**
	 * Bind a new {@link Connection} reference or null to the channel
	 * attributes only if this instance is currently bound.
	 *
	 * @param connection a new connection reference
	 *
	 * @return true if bound
	 * @see #bind
	 */
	default boolean rebind(@Nullable Connection connection) {
		return channel().attr(ReactorNetty.CONNECTION)
		                .compareAndSet(this, connection);
	}

	/**
	 * Remove a named handler if present and return this context
	 *
	 * @param name handler name
	 *
	 * @return this Connection
	 */
	default Connection removeHandler(String name) {
		ReactorNetty.removeHandler(channel(), name);
		return this;
	}

	/**
	 * Replace a named handler if present and return this context.
	 * If handler wasn't present, an {@link RuntimeException} will be thrown.
	 * <p>
	 *     Note: if the new handler is of different type, dependent handling like
	 *     the "extractor" introduced via HTTP-based {@link #addHandler} might not
	 *     expect/support the new messages type.
	 *
	 * @param name handler name
	 *
	 * @return this Connection
	 */
	default Connection replaceHandler(String name, ChannelHandler handler) {
		ReactorNetty.replaceHandler(channel(), name, handler);
		return this;
	}
}