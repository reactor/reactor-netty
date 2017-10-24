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

package reactor.ipc.netty.http.client;

import java.util.function.Function;

import io.netty.bootstrap.Bootstrap;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.options.ClientProxyOptions;
import reactor.ipc.netty.options.ClientProxyOptions.Proxy;

/**
 * An http client connector builder with low-level connection options including
 * connection pooling and proxy.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public final class HttpClientOptions extends ClientOptions {
	private final UriEndpointFactory uriEndpointFactory;

	/**
	 * Create a new HttpClientOptions.Builder
	 *
	 * @return a new HttpClientOptions.Builder
	 */
	@SuppressWarnings("unchecked")
	public static HttpClientOptions.Builder builder() {
		return new HttpClientOptions.Builder();
	}

	private final boolean acceptGzip;

	private HttpClientOptions(HttpClientOptions.Builder builder) {
		super(builder);
		this.acceptGzip = builder.acceptGzip;
		this.uriEndpointFactory = new UriEndpointFactory(() -> getAddress(), isSecure(),
				(hostname, port) -> createInetSocketAddress(hostname, port, false));
	}

	@Override
	public HttpClientOptions duplicate() {
		return builder().from(this).build();
	}

	/**
	 * Returns true when gzip support is enabled otherwise - false
	 *
	 * @return returns true when gzip support is enabled otherwise - false
	 */
	public boolean acceptGzip() {
		return this.acceptGzip;
	}

	@Override
	protected SslContext defaultSslContext() {
		return DEFAULT_SSL_CONTEXT;
	}


	@Override
	public String asSimpleString() {
		return super.asSimpleString() + (acceptGzip ? " with gzip" : "");
	}

	@Override
	public String asDetailedString() {
		return super.asDetailedString() + ", acceptGzip=" + acceptGzip;
	}

	@Override
	public String toString() {
		return "HttpClientOptions{" + asDetailedString() + "}";
	}

	static final SslContext DEFAULT_SSL_CONTEXT;

	static {
		SslContext sslContext;
		try {
			sslContext = SslContextBuilder.forClient()
			                              .build();
		}
		catch (Exception e) {
			sslContext = null;
		}
		DEFAULT_SSL_CONTEXT = sslContext;
	}

	UriEndpoint createUriEndpoint(String url, boolean ws) {
		return uriEndpointFactory.createUriEndpoint(url, ws);
	}

    public static final class Builder extends ClientOptions.Builder<Builder> {
		private boolean acceptGzip;

		private Builder() {
			super(new Bootstrap());
		}

		/**
		 * Enable GZip accept-encoding header and support for compressed response
		 *
		 * @param enabled true whether gzip support is enabled
		 * @return {@code this}
		 */
		public final Builder compression(boolean enabled) {
			this.acceptGzip = enabled;
			return get();
		}

		/**
		 * The HTTP proxy configuration
		 *
		 * @param proxyOptions the HTTP proxy configuration
		 * @return {@code this}
		 */
		public final Builder httpProxy(Function<ClientProxyOptions.AddressSpec, ClientProxyOptions.Builder> proxyOptions) {
			super.proxy(t -> proxyOptions.apply(t.type(Proxy.HTTP)));
			return get();
		}

		/**
		 * Fill the builder with attribute values from the provided options.
		 *
		 * @param options The instance from which to copy values
		 * @return {@code this}
		 */
		public final Builder from(HttpClientOptions options) {
			super.from(options);
			this.acceptGzip = options.acceptGzip;
			return get();
		}

		@Override
		public HttpClientOptions build() {
			super.build();
			return new HttpClientOptions(this);
		}
	}
}
