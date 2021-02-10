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

import java.net.SocketAddress;
import java.time.Duration;

import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static java.util.Objects.requireNonNull;

/**
 * Holds contextual information for the underlying channel and provides
 * non-blocking resource disposing API
 *
 * @author Stephane Maldini
 * @since 0.7
 */
@FunctionalInterface
public interface DisposableChannel extends Disposable {

	/**
	 * When on the server, returns the bind address,
	 * when on the client, returns the remote address.
	 *
	 * @return {@link SocketAddress}
	 */
	default SocketAddress address() {
		Channel c = channel();
		if (c instanceof DatagramChannel) {
			SocketAddress a = c.remoteAddress();
			return a != null ? a : c.localAddress();
		}

		return c.remoteAddress();
	}

	/**
	 * Returns the underlying {@link Channel}. Direct interaction might be considered
	 * insecure if that affects the underlying I/O processing such as read, write or close
	 * or state such as pipeline handler addition/removal.
	 *
	 * @return the underlying {@link Channel}
	 */
	Channel channel();

	/**
	 * Releases or closes the underlying {@link Channel}
	 */
	@Override
	@SuppressWarnings({"FutureReturnValueIgnored", "FunctionalInterfaceMethodChanged"})
	default void dispose() {
		//"FutureReturnValueIgnored" this is deliberate
		channel().close();
	}

	/**
	 * Releases or closes the underlying {@link Channel} in a blocking fashion with
	 * {@code 3} seconds default timeout.
	 */
	default void disposeNow() {
		disposeNow(Duration.ofSeconds(3));
	}

	/**
	 * Releases or closes the underlying {@link Channel} in a blocking fashion with
	 * the provided timeout.
	 *
	 * @param timeout max dispose timeout (resolution: ns)
	 */
	default void disposeNow(Duration timeout) {
		if (isDisposed()) {
			return;
		}

		requireNonNull(timeout, "timeout");

		dispose();
		try {
			onDispose().block(timeout);
		}
		catch (IllegalStateException e) {
			if (e.getMessage()
			     .contains("blocking read")) {
				throw new IllegalStateException("Socket couldn't be stopped within " + timeout.toMillis() + "ms");
			}
			throw e;
		}
	}

	/**
	 * Returns a {@link CoreSubscriber} that will dispose on complete or error
	 */
	default CoreSubscriber<Void> disposeSubscriber() {
		return new ReactorNetty.ChannelDisposer(this);
	}

	@Override
	default boolean isDisposed() {
		return !channel().isActive();
	}

	/**
	 * Returns an observing {@link Mono} terminating with success when shutdown
	 * successfully or error.
	 *
	 * @return a {@link Mono} terminating with success if shutdown successfully or error
	 */
	default Mono<Void> onDispose() {
		return FutureMono.from(channel().closeFuture());
	}

	/**
	 * Assigns a {@link Disposable} to be invoked when the channel is closed.
	 *
	 * @param onDispose the close event handler
	 *
	 * @return {@literal this}
	 */
	default DisposableChannel onDispose(Disposable onDispose) {
		requireNonNull(onDispose, "onDispose");
		onDispose().subscribe(null, e -> onDispose.dispose(), onDispose::dispose);
		return this;
	}

}