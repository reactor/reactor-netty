/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Objects;
import java.util.function.Function;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.NetUtil;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.options.ClientProxyOptions;
import reactor.ipc.netty.options.ClientProxyOptions.Proxy;
import reactor.util.function.Tuple2;

/**
 * An http client connector builder with low-level connection options including
 * connection pooling and proxy.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public final class HttpClientOptions extends ClientOptions {


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
	}

	@Override
	public HttpClientOptions duplicate() {
		return builder().from(this).build();
	}

	/**
	 * Return a new {@link InetSocketAddress} from the URI.
	 * <p>
	 * If the port is undefined (-1), a default port is used (80 or 443 depending on
	 * whether the URI is secure or not). If {@link #useProxy(String) a proxy} is used, the
	 * returned address is provided unresolved.
	 *
	 * @param uri {@link URI} to extract host and port information from
	 * @return a new eventual {@link InetSocketAddress}
	 */
	public final InetSocketAddress getRemoteAddress(URI uri) {
		Objects.requireNonNull(uri, "uri");
		boolean secure = isSecure(uri);
		int port = uri.getPort() != -1 ? uri.getPort() : (secure ? 443 : 80);
		// TODO: find out whether the remote address should be resolved using blocking operation at this point
		boolean shouldResolveAddress = !useProxy(uri.getHost());
		return createInetSocketAddress(uri.getHost(), port, shouldResolveAddress);
	}

	@Override
	public SslHandler getSslHandler(ByteBufAllocator allocator, Tuple2<String, Integer> sniInfo) {
		SslHandler handler =  super.getSslHandler(allocator, sniInfo);
		SSLEngine sslEngine = handler.engine();
		SSLParameters sslParameters = sslEngine.getSSLParameters();
		sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
		sslEngine.setSSLParameters(sslParameters);
		return handler;
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

	final String formatSchemeAndHost(String url, boolean ws) {
		if (!url.startsWith(HttpClient.HTTP_SCHEME) && !url.startsWith(HttpClient.WS_SCHEME)) {
			StringBuilder schemeBuilder = new StringBuilder();
			if (ws) {
				schemeBuilder.append(isSecure() ? HttpClient.WSS_SCHEME : HttpClient.WS_SCHEME);
			}
			else {
				schemeBuilder.append(isSecure() ? HttpClient.HTTPS_SCHEME : HttpClient.HTTP_SCHEME);
			}

			final String scheme = schemeBuilder.append("://").toString();
			if (url.startsWith("/")) {
				//consider relative URL, use the base hostname/port or fallback to localhost
				SocketAddress remote = getAddress();

				if (remote instanceof InetSocketAddress) {
					// NetUtil.toSocketAddressString properly handles IPv6 addresses
					return scheme + NetUtil.toSocketAddressString((InetSocketAddress) remote) + url;
				}
				else {
					return scheme + "localhost" + url;
				}
			}
			else if (url.equals("")) {
				return scheme + "localhost";
			}
			else {
				//consider absolute URL
				return scheme + url;
			}
		}
		else {
			return url;
		}
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

	static boolean isSecure(URI uri) {
		return uri.getScheme() != null && (uri.getScheme()
		                                      .toLowerCase()
		                                      .equals(HttpClient.HTTPS_SCHEME) || uri.getScheme()
		                                                                             .toLowerCase()
		                                                                             .equals(HttpClient.WSS_SCHEME));
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
