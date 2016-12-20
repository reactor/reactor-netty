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

import java.util.Objects;
import java.util.function.Consumer;

import org.reactivestreams.Publisher;

/**
 * Constant for names used when adding/removing {@link io.netty.channel.ChannelHandler}.
 *
 * Order of placement :
 * <p>
 * {@code
 * -> proxy ? [ProxyHandler]
 * -> ssl ? [SslHandler]
 * -> ssl & trace log ? [SslLoggingHandler]
 * -> ssl ? [SslReader]
 * -> log ? [LoggingHandler]
 * => [BridgeSetup]
 * -> http ? [HttpCodecHandler]
 * -> http ws ? [HttpAggregator]
 * -> http server  ? [HttpServerHandler]
 * -> onWriteIdle ? [OnChannelWriteIdle]
 * -> onReadIdle ? [OnChannelReadIdle]
 * -> http form/multipart ? [ChunkedWriter]
 * => [ReactiveBridge]
 * }
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public interface NettyPipeline {

	String SslHandler         = "sslHandler";
	String SslReader          = "sslReader";
	String SslLoggingHandler  = "sslLoggingHandler";
	String ProxyHandler       = "proxyHandler";
	String ReactiveBridge     = "reactiveBridge";
	String BridgeSetup        = "bridgeSetup";
	String HttpEncoder        = "httpEncoder";
	String HttpDecoder        = "httpDecoder";
	String HttpAggregator     = "reactorHttpAggregator";
	String HttpServerHandler  = "httpServerHandler";
	String OnChannelWriteIdle = "onChannelWriteIdle";
	String OnChannelReadIdle  = "onChannelReadIdle";
	String ChunkedWriter      = "chunkedWriter";
	String LoggingHandler     = "loggingHandler";

	/**
	 * A builder for sending strategy, similar prefixed methods being mutually exclusive
	 * (flushXxx, prefetchXxx, requestXxx).
	 */
	interface SendOptions {

		/**
		 * Make the underlying channel flush on a terminated {@link Publisher} (default).
		 *
		 * @return this builder
		 */
		SendOptions flushOnBoundary();

		/**
		 * Make the underlying channel flush item by item.
		 *
		 * @return this builder
		 */
		SendOptions flushOnEach();

		/**
		 * Make the underlying channel flush on a memory threshold expressed in bytes.
		 * This should enable {@link #trackPendingBytes(boolean)} to evaluate written
		 * items size.
		 *
		 * @param maxPendingBytes a non strict maximum of bytes to trigger flush.
		 *
		 * @return this builder
		 */
		SendOptions flushOnMemoryUsed(long maxPendingBytes);

		/**
		 * Maximum in-flight number of chunks.
		 *
		 * @param prefetch maximum number of chunks
		 *
		 * @return this builder
		 */
		SendOptions prefetch(int prefetch);

		/**
		 * Non-Strict maximum in-flight sent bytes. The demand score will be evaluated
		 * every item sent by automatically enabling {@link #trackPendingBytes(boolean)}.
		 *
		 * @param prefetchBytes non-strict maximum of bytes to request
		 *
		 * @return this builder
		 */
		SendOptions prefetchMemory(long prefetchBytes);

		/**
		 * Non-Strict maximum in-flight sent bytes. The long demand will evaluated every
		 * {@code samplingPrefetch} items sent by automatically enabling {@link
		 * #trackPendingBytes(boolean)}.
		 *
		 * @param samplingPrefetch number of chunks to evaluate an average size from
		 * @param prefetchBytes non-strict maximum of bytes to request
		 *
		 * @return this builder
		 */
		SendOptions prefetchMemory(int samplingPrefetch, long prefetchBytes);

		/**
		 * Make the underlying channel request more chunks from {@link Publisher} on
		 * write buffer availability (Default). This will be faster demand than
		 * {@link #requestOnWriteConfirm()} but less fair to resources use.
		 * <p>Request sequence: N (prefetch) then 1 x M.
		 *
		 * @return this builder
		 */
		SendOptions requestOnWriteAvailable();

		/**
		 * Make the underlying channel request more chunks from {@link Publisher} on
		 * write confirm. This will be slower demand than
		 * {@link #requestOnWriteAvailable()} but more fair to resource use.
		 * <p>Request sequence: N (prefetch) then N*75% x M.
		 *
		 * @return this builder
		 */
		SendOptions requestOnWriteConfirm();

		/**
		 * Enable or disable written item size tracking.
		 *
		 * @param shouldCount true if should track written items size
		 *
		 * @return this builder
		 */
		SendOptions trackPendingBytes(boolean shouldCount);
	}

	/**
	 * An container transporting a new {@link SendOptions}, eventually bound to a
	 * specific {@link Publisher}
	 */
	final class SendOptionsChangeEvent {

		final Consumer<? super SendOptions> configurator;
		final Publisher<?>                  source;

		SendOptionsChangeEvent(Consumer<? super SendOptions> configurator,
				Publisher<?> source) {
			this.configurator = Objects.requireNonNull(configurator, "configurator");
			this.source = source;
		}

		/**
		 * Return the send configurator
		 *
		 * @return the send configurator
		 */
		public Consumer<? super SendOptions> configurator() {
			return configurator;
		}

		/**
		 * Return the optional source {@link Publisher} or null
		 *
		 * @return the optional source {@link Publisher} or null
		 */
		public Publisher<?> source() {
			return source;
		}
	}

	/**
	 * Return a marking event used when a netty connector handler terminates
	 *
	 * @return a marking event used when a netty connector handler terminates
	 */
	static Object handlerTerminatedEvent() {
		return ReactorNetty.TERMINATED;
	}
}
