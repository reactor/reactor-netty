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

	private HttpServerOptions(HttpServerOptions.Builder builder) {
		super(builder);
		this.minCompressionResponseSize = builder.minCompressionResponseSize;
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
				", minCompressionResponseSize=" + minCompressionResponseSize;
	}

	@Override
	public String toString() {
		return "HttpServerOptions{" + asDetailedString() + "}";
	}

	public static final class Builder extends ServerOptions.Builder<Builder> {
		private int minCompressionResponseSize = -1;

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
		 * Fill the builder with attribute values from the provided options.
		 *
		 * @param options The instance from which to copy values
		 * @return {@code this}
		 */
		public final Builder from(HttpServerOptions options) {
			super.from(options);
			this.minCompressionResponseSize = options.minCompressionResponseSize;
			return get();
		}

		@Override
		public HttpServerOptions build() {
			super.build();
			return new HttpServerOptions(this);
		}
	}
}
