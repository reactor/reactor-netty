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

import java.util.Objects;
import java.util.function.BiPredicate;

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

	private final BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate;

	private final int minCompressionResponseSize;
	private final int maxInitialLineLength;
	private final int maxHeaderSize;
	private final int maxChunkSize;
	private final int initialBufferSize;
	private final boolean validateHeaders;

	private HttpServerOptions(HttpServerOptions.Builder builder) {
		super(builder);
		this.minCompressionResponseSize = builder.minCompressionResponseSize;
		this.maxInitialLineLength = builder.maxInitialLineLength;
		this.maxHeaderSize = builder.maxHeaderSize;
		this.maxChunkSize = builder.maxChunkSize;
		this.validateHeaders = builder.validateHeaders;
		this.initialBufferSize = builder.initialBufferSize;
		this.compressionPredicate = builder.compressionPredicate;
	}

	/**
	 * Returns the compression predicate returning true to compress the response.
	 * By default the compression is disabled.
	 *
	 * @return Returns the compression predicate returning true to compress the response.
	 */
	public BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate() {
		return compressionPredicate;
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

	/**
	 * Returns the HTTP validate headers flag.
	 *
	 * @return true if the HTTP codec validates headers, false otherwise
	 * @see io.netty.handler.codec.http.HttpServerCodec
	 */
	public boolean httpCodecValidateHeaders() {
		return validateHeaders;
	}
	/**
	 * Returns the configured HTTP codec initial buffer size.
	 *
	 * @return the configured HTTP codec initial buffer size
	 * @see io.netty.handler.codec.http.HttpServerCodec
	 */
	public int httpCodecInitialBufferSize() {
		return initialBufferSize;
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

		public static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
		public static final int DEFAULT_MAX_HEADER_SIZE         = 8192;
		public static final int DEFAULT_MAX_CHUNK_SIZE          = 8192;
		public static final boolean DEFAULT_VALIDATE_HEADERS    = true;
		public static final int DEFAULT_INITIAL_BUFFER_SIZE     = 128;

		private BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate;

		private int minCompressionResponseSize = -1;
		private int maxInitialLineLength = DEFAULT_MAX_INITIAL_LINE_LENGTH;
		private int maxHeaderSize = DEFAULT_MAX_HEADER_SIZE;
		private int maxChunkSize = DEFAULT_MAX_CHUNK_SIZE;
		private boolean validateHeaders = DEFAULT_VALIDATE_HEADERS;
		private int initialBufferSize = DEFAULT_INITIAL_BUFFER_SIZE;

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
		 * headers and the passed {@link java.util.function.Predicate} matches.
		 * <p>
		 *     note the passed {@link HttpServerRequest} and {@link HttpServerResponse}
		 *     should be considered read-only and the implement SHOULD NOT consume or
		 *     write the request/response in this predicate.
		 * @param predicate that returns true to compress the response.
		 *
		 *
		 * @return {@code this}
		 */
		public final Builder compression(BiPredicate<HttpServerRequest, HttpServerResponse> predicate) {
			Objects.requireNonNull(predicate, "compressionPredicate");
			if (this.minCompressionResponseSize < 0) {
				this.minCompressionResponseSize = 0;
			}
			this.compressionPredicate = predicate;
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
		 * Configure the maximum length that can be decoded for the HTTP request's initial
		 * line. Defaults to {@code #DEFAULT_MAX_INITIAL_LINE_LENGTH}.
		 *
		 * @param value the value for the maximum initial line length (strictly positive)
		 * @return this option builder for further configuration
		 */
		public final Builder maxInitialLineLength(int value) {
			if (value <= 0) {
				throw new IllegalArgumentException(
						"maxInitialLineLength must be strictly positive");
			}
			this.maxInitialLineLength = value;
			return get();
		}

		/**
		 * Configure the maximum header size that can be decoded for the HTTP request.
		 * Defaults to {@link #DEFAULT_MAX_HEADER_SIZE}.
		 *
		 * @param value the value for the maximum header size (strictly positive)
		 * @return this option builder for further configuration
		 */
		public final Builder maxHeaderSize(int value) {
			if (value <= 0) {
				throw new IllegalArgumentException("maxHeaderSize must be strictly positive");
			}
			this.maxHeaderSize = value;
			return get();
		}

		/**
		 * Configure the maximum chunk size that can be decoded for the HTTP request.
		 * Defaults to {@link #DEFAULT_MAX_CHUNK_SIZE}.
		 *
		 * @param value the value for the maximum chunk size (strictly positive)
		 * @return this option builder for further configuration
		 */
		public final Builder maxChunkSize(int value) {
			if (value <= 0) {
				throw new IllegalArgumentException("maxChunkSize must be strictly positive");
			}
			this.maxChunkSize = value;
			return get();
		}

		/**
		 * Configure whether or not to validate headers when decoding requests. Defaults to
		 * #DEFAULT_VALIDATE_HEADERS.
		 *
		 * @param validate true to validate headers, false otherwise
		 * @return this option builder for further configuration
		 */
		public final Builder validateHeaders(boolean validate) {
			this.validateHeaders = validate;
			return get();
		}

		/**
		 * Configure the initial buffer size for HTTP request decoding. Defaults to
		 * {@link #DEFAULT_INITIAL_BUFFER_SIZE}.
		 *
		 * @param value the initial buffer size to use (strictly positive)
		 * @return {@code this}
		 */
		public final Builder initialBufferSize(int value) {
			if (value <= 0) {
				throw new IllegalArgumentException("initialBufferSize must be strictly positive");
			}
			this.initialBufferSize = value;
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
			this.validateHeaders = options.validateHeaders;
			this.initialBufferSize = options.initialBufferSize;
			return get();
		}

		@Override
		public HttpServerOptions build() {
			super.build();
			return new HttpServerOptions(this);
		}
	}
}
