/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.server;

import io.netty.bootstrap.ServerBootstrap;
import reactor.ipc.netty.options.ServerOptions;

/**
 * Encapsulates configuration options for the http server.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public final class HttpServerOptions extends ServerOptions {

	/**
	 * Create a new HttpServerOptions.Builder
	 *
	 * @return a new HttpServerOptions.Builder
	 */
	@SuppressWarnings("unchecked")
	public static HttpServerOptions.Builder builder() {
		return new HttpServerOptions.Builder();
	}

	private final int minCompressionResponseSize;
	private final int maxInitialLineLength;
	private final int maxHeaderSize;
	private final int maxChunkSize;

	private HttpServerOptions(HttpServerOptions.Builder builder) {
		super(builder);
		this.minCompressionResponseSize = builder.minCompressionResponseSize;
		this.maxInitialLineLength = builder.maxInitialLineLength;
		this.maxHeaderSize = builder.maxHeaderSize;
		this.maxChunkSize = builder.maxChunkSize;
	}

	/**
	 * Returns the minimum response size before the output is compressed.
	 * By default the compression is disabled.
	 *
	 * @return Returns the minimum response size before the output is compressed.
	 */
	public int minCompressionResponseSize() {
		return minCompressionResponseSize;
	}

	/**
	 * Returns the maximum length configured for the initial HTTP line.
	 *
	 * @return the initial HTTP line maximum length
	 * @see io.netty.handler.codec.http.HttpServerCodec
	 */
	public int httpCodecMaxInitialLineLength() {
		return maxInitialLineLength;
	}

	/**
	 * Returns the configured HTTP header maximum size.
	 *
	 * @return the configured HTTP header maximum size
	 * @see io.netty.handler.codec.http.HttpServerCodec
	 */
	public int httpCodecMaxHeaderSize() {
		return maxHeaderSize;
	}

	/**
	 * Returns the configured HTTP chunk maximum size.
	 *
	 * @return the configured HTTP chunk maximum size
	 * @see io.netty.handler.codec.http.HttpServerCodec
	 */
	public int httpCodecMaxChunkSize() {
		return maxChunkSize;
	}

	@Override
	public HttpServerOptions duplicate() {
		return builder().from(this).build();
	}

	@Override
	public String asSimpleString() {
		StringBuilder s = new StringBuilder(super.asSimpleString());

		if (minCompressionResponseSize >= 0) {
			s.append(", gzip");
			if (minCompressionResponseSize > 0) {
				s.append( " over ").append(minCompressionResponseSize).append(" bytes");
			}
		}

		return s.toString();
	}

	@Override
	public String asDetailedString() {
		return super.asDetailedString() +
				", minCompressionResponseSize=" + minCompressionResponseSize +
				", httpCodecSizes={initialLine=" + this.maxInitialLineLength +
				",header=" + this.maxHeaderSize + ",chunk="+ this.maxChunkSize + "}";
	}

	@Override
	public String toString() {
		return "HttpServerOptions{" + asDetailedString() + "}";
	}

	public static final class Builder extends ServerOptions.Builder<Builder> {
		private int minCompressionResponseSize = -1;
		private int maxInitialLineLength = 4096;
		private int maxHeaderSize = 8192;
		private int maxChunkSize = 8192;

		private Builder(){
			super(new ServerBootstrap());
		}

		/**
		 * Enable GZip response compression if the client request presents accept encoding
		 * headers
		 *
		 * @param enabled true whether compression is enabled
		 * @return {@code this}
		 */
		public final Builder compression(boolean enabled) {
			this.minCompressionResponseSize = enabled ? 0 : -1;
			return get();
		}

		/**
		 * Enable GZip response compression if the client request presents accept encoding
		 * headers
		 * AND the response reaches a minimum threshold
		 *
		 * @param minResponseSize compression is performed once response size exceeds given
		 * value in byte
		 * @return {@code this}
		 */
		public final Builder compression(int minResponseSize) {
			if (minResponseSize < 0) {
				throw new IllegalArgumentException("minResponseSize must be positive");
			}
			this.minCompressionResponseSize = minResponseSize;
			return get();
		}

		/**
		 * Configure the {@link io.netty.handler.codec.http.HttpServerCodec HTTP codec}
		 * maximum initial HTTP line length, header size and chunk size.
		 * <p>
		 * Negative or zero values are not valid, but will be interpreted as "don't change
		 * the current configuration for that field": on first call the Netty defaults of
		 * {@code (4096,8192,8192)} will be used.
		 *
		 * @param maxInitialLineLength the HTTP initial line maximum length. Use 0 to ignore/keep default.
		 * @param maxHeaderSize the HTTP header maximum size. Use 0 to ignore/keep default.
		 * @param maxChunkSize the HTTP chunk maximum size. Use 0 to ignore/keep default.
		 * @return {@code this}
		 */
		public final Builder httpCodecOptions(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
			if (maxInitialLineLength > 0) {
				this.maxInitialLineLength = maxInitialLineLength;
			}
			if (maxHeaderSize > 0) {
				this.maxHeaderSize = maxHeaderSize;
			}
			if (maxChunkSize > 0) {
				this.maxChunkSize = maxChunkSize;
			}
			return get();
		}

		/**
		 * Fill the builder with attribute values from the provided options.
		 *
		 * @param options The instance from which to copy values
		 * @return {@code this}
		 */
		public final Builder from(HttpServerOptions options) {
			super.from(options);
			this.minCompressionResponseSize = options.minCompressionResponseSize;
			this.maxInitialLineLength = options.maxInitialLineLength;
			this.maxHeaderSize = options.maxHeaderSize;
			this.maxChunkSize = options.maxChunkSize;
			return get();
		}

		@Override
		public HttpServerOptions build() {
			super.build();
			return new HttpServerOptions(this);
		}
	}
}
