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

package reactor.ipc.netty.tcp;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import reactor.core.Exceptions;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * SSL Provider
 *
 * @author Violeta Georgieva
 */
public final class SslProvider {

	/**
	 * Creates a builder for {@link SslProvider SslProvider}
	 *
	 * @return a new SslProvider builder
	 */
	public static SslProvider.SslContextSpec builder() {
		return new SslProvider.Build();
	}


	final SslContext sslContext;
	final long handshakeTimeoutMillis;
	final long closeNotifyFlushTimeoutMillis;
	final long closeNotifyReadTimeoutMillis;

	SslProvider(SslProvider.Build builder) {
		this.sslContext = builder.sslContext;
		this.handshakeTimeoutMillis = builder.handshakeTimeoutMillis;
		this.closeNotifyFlushTimeoutMillis = builder.closeNotifyFlushTimeoutMillis;
		this.closeNotifyReadTimeoutMillis = builder.closeNotifyReadTimeoutMillis;
	}


	/**
	 * Returns {@code SslContext} instance with configured settings.
	 * 
	 * @return {@code SslContext} instance with configured settings.
	 */
	public SslContext getSslContext() {
		return this.sslContext;
	}

	public void configure(SslHandler sslHandler) {
		sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutMillis);
		sslHandler.setCloseNotifyFlushTimeoutMillis(closeNotifyFlushTimeoutMillis);
		sslHandler.setCloseNotifyReadTimeoutMillis(closeNotifyReadTimeoutMillis);
	}


	public String asSimpleString() {
		return toString();
	}

	public String asDetailedString() {
		return "handshakeTimeoutMillis=" + this.handshakeTimeoutMillis +
				", closeNotifyFlushTimeoutMillis=" + this.closeNotifyFlushTimeoutMillis +
				", closeNotifyReadTimeoutMillis=" + this.closeNotifyReadTimeoutMillis;
	}

	@Override
	public String toString() {
		return "SslProvider{" + asDetailedString() + "}";
	}

	
	static final class Build implements SslContextSpec, Builder {

		static final long DEFAULT_SSL_HANDSHAKE_TIMEOUT =
				Long.parseLong(System.getProperty(
						"reactor.ipc.netty.sslHandshakeTimeout",
						"10000"));

		static final SelfSignedCertificate DEFAULT_SSL_CONTEXT_SELF;

		static {
			SelfSignedCertificate cert;
			try {
				cert = new SelfSignedCertificate();
			}
			catch (Exception e) {
				cert = null;
			}
			DEFAULT_SSL_CONTEXT_SELF = cert;
		}

		SslContextBuilder sslCtxBuilder;
		SslContext sslContext;
		long handshakeTimeoutMillis = DEFAULT_SSL_HANDSHAKE_TIMEOUT;
		long closeNotifyFlushTimeoutMillis = 3000L;
		long closeNotifyReadTimeoutMillis;

		Build() {
		}

		@Override
		public final Builder forClient() {
			this.sslCtxBuilder = SslContextBuilder.forClient();
			return this;
		}

		@Override
		public final Builder forServer() {
			this.sslCtxBuilder = SslContextBuilder.forServer(
					DEFAULT_SSL_CONTEXT_SELF.certificate(),
					DEFAULT_SSL_CONTEXT_SELF.privateKey());
			return this;
		}

		@Override
		public final Builder sslContext(Consumer<? super SslContextBuilder> sslContextBuilder) {
			Objects.requireNonNull(sslContextBuilder, "sslContextBuilder");
			SslContext sslContext;
			try {
				sslContextBuilder.accept(this.sslCtxBuilder);
				sslContext = this.sslCtxBuilder.build();
			}
			catch (Exception sslException) {
				throw Exceptions.propagate(sslException);
			}
			return sslContext(sslContext);
		}

		@Override
		public final Builder sslContext(SslContext sslContext){
			this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
			return this;
		}

		@Override
		public final Builder handshakeTimeout(Duration handshakeTimeout) {
			Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
			return handshakeTimeoutMillis(handshakeTimeout.toMillis());
		}

		@Override
		public final Builder handshakeTimeoutMillis(long handshakeTimeoutMillis) {
			if (handshakeTimeoutMillis < 0L) {
				throw new IllegalArgumentException("ssl handshake timeout must be positive"
						+ " was: " + handshakeTimeoutMillis);
			}
			this.handshakeTimeoutMillis = handshakeTimeoutMillis;
			return this;
		}

		@Override
		public final Builder closeNotifyFlushTimeout(Duration closeNotifyFlushTimeout) {
			Objects.requireNonNull(closeNotifyFlushTimeout, "closeNotifyFlushTimeout");
			return closeNotifyFlushTimeoutMillis(closeNotifyFlushTimeout.toMillis());
		}

		@Override
		public final Builder closeNotifyFlushTimeoutMillis(long closeNotifyFlushTimeoutMillis) {
			if (closeNotifyFlushTimeoutMillis < 0L) {
				throw new IllegalArgumentException("ssl close_notify flush timeout must be positive,"
						+ " was: " + closeNotifyFlushTimeoutMillis);
			}
			this.closeNotifyFlushTimeoutMillis = closeNotifyFlushTimeoutMillis;
			return this;
		}

		@Override
		public final Builder closeNotifyReadTimeout(Duration closeNotifyReadTimeout) {
			Objects.requireNonNull(closeNotifyReadTimeout, "closeNotifyReadTimeout");
			return closeNotifyReadTimeoutMillis(closeNotifyReadTimeout.toMillis());
		}

		@Override
		public final Builder closeNotifyReadTimeoutMillis(long closeNotifyReadTimeoutMillis) {
			if (closeNotifyReadTimeoutMillis < 0L) {
				throw new IllegalArgumentException("ssl close_notify read timeout must be positive,"
						+ " was: " + closeNotifyReadTimeoutMillis);
			}
			this.closeNotifyReadTimeoutMillis = closeNotifyReadTimeoutMillis;
			return this;
		}

		@Override
		public SslProvider build() {
			return new SslProvider(this);
		}
	}

	public interface Builder {

		/**
		 * Set a builder callback for further customization of SslContext
		 *
		 * @param sslContextBuilder builder callback for further customization of SslContext
		 *
		 * @return {@literal this}
		 */
		Builder sslContext(Consumer<? super SslContextBuilder> sslContextBuilder);

		/**
		 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
		 *
		 * @param handshakeTimeout The timeout {@link Duration}
		 *
		 * @return {@literal this}
		 */
		Builder handshakeTimeout(Duration handshakeTimeout);

		/**
		 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
		 *
		 * @param handshakeTimeoutMillis The timeout in milliseconds
		 *
		 * @return {@literal this}
		 */
		Builder handshakeTimeoutMillis(long handshakeTimeoutMillis);

		/**
		 * Set the options to use for configuring SSL close_notify flush timeout. Default to 3000 ms.
		 *
		 * @param closeNotifyFlushTimeout The timeout {@link Duration}
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyFlushTimeout(Duration closeNotifyFlushTimeout);

		/**
		 * Set the options to use for configuring SSL close_notify flush timeout. Default to 3000 ms.
		 *
		 * @param closeNotifyFlushTimeoutMillis The timeout in milliseconds
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyFlushTimeoutMillis(long closeNotifyFlushTimeoutMillis);

		/**
		 * Set the options to use for configuring SSL close_notify read timeout. Default to 0 ms.
		 *
		 * @param closeNotifyReadTimeout The timeout {@link Duration}
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyReadTimeout(Duration closeNotifyReadTimeout);

		/**
		 * Set the options to use for configuring SSL close_notify read timeout. Default to 0 ms.
		 *
		 * @param closeNotifyReadTimeoutMillis The timeout in milliseconds
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyReadTimeoutMillis(long closeNotifyReadTimeoutMillis);

		/**
		 * Builds new SslProvider
		 *
		 * @return builds new SslProvider
		 */
		SslProvider build();
	}

	public interface SslContextSpec {

		/**
		 * The context to set when configuring SSL
		 * 
		 * @param sslContext The context to set when configuring SSL
		 * 
		 * @return {@literal this}
		 */
		Builder sslContext(SslContext sslContext);

		/**
		 * Creates SslContextBuilder for new client-side {@link SslContext}.
		 * 
		 * @return {@literal this}
		 */
		Builder forClient();

		/**
		 * Creates SslContextBuilder for new server-side {@link SslContext}.
		 * 
		 * @return {@literal this}
		 */
		Builder forServer();
	}
}
