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
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Hold contextual information for the underlying channel and provide for
 * non-blocking resource disposing API
 *
 * @author Stephane Maldini
 * @since 0.7
 */
@FunctionalInterface
public interface DisposableChannel extends Disposable {

	/**
	 * Return local server selector channel address.
	 *
	 * @return local {@link InetSocketAddress}
	 */
	default InetSocketAddress address(){
		Channel c = channel();
		if (c instanceof SocketChannel) {
			return ((SocketChannel) c).remoteAddress();
		}
		if (c instanceof ServerSocketChannel) {
			return ((ServerSocketChannel) c).localAddress();
		}
		if (c instanceof DatagramChannel) {
			InetSocketAddress a = ((DatagramChannel) c).remoteAddress();
			return a != null ? a : ((DatagramChannel)c ).localAddress();
		}
		throw new IllegalStateException("Does not have an InetSocketAddress");
	}

	/**
	 * Return the underlying {@link Channel}. Direct interaction might be considered
	 * insecure if that affects the underlying IO processing such as read, write or close
	 * or state such as pipeline handler addition/removal.
	 *
	 * @return the underlying {@link Channel}
	 */
	Channel channel();

	/**
	 * Release or close the underlying {@link Channel}
	 */
	@Override
	default void dispose() {
		channel().close();
	}

	/**
	 * Release or close the underlying {@link Channel} in a blocking fashion with 3
	 * seconds default timeout.
	 */
	default void disposeNow() {
		disposeNow(Duration.ofSeconds(3));
	}

	/**
	 * Release or close the underlying {@link Channel} in a blocking fashion
	 */
	default void disposeNow(Duration timeout) {
		if (isDisposed()) {
			return;
		}
		dispose();
		onDispose().timeout(timeout,
				Mono.error(new TimeoutException("server couldn't be stopped within " + timeout.toMillis() + "ms")))
		           .block();
	}

	/**
	 * Return a {@link CoreSubscriber} that will dispose on complete or error
	 */
	default CoreSubscriber<Void> disposeSubscriber() {
		return new ReactorNetty.ChannelDisposer(this);
	}

	@Override
	default boolean isDisposed() {
		return !channel().isActive();
	}

	/**
	 * Return an observing {@link Mono} terminating with success when shutdown
	 * successfully or error.
	 *
	 * @return a {@link Mono} terminating with success if shutdown successfully or error
	 */
	default Mono<Void> onDispose() {
		return FutureMono.from(channel().closeFuture());
	}

	/**
	 * Assign a {@link Disposable} to be invoked when the channel is closed.
	 *
	 * @param onDispose the close event handler
	 *
	 * @return {@literal this}
	 */
	default DisposableChannel onDispose(Disposable onDispose) {
		onDispose().subscribe(null, e -> onDispose.dispose(), onDispose::dispose);
		return this;
	}

}