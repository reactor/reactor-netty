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
