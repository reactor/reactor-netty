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

package reactor.netty.http.client;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.http.Cookies;
import reactor.netty.http.HttpOperations;
import reactor.netty.tcp.ProxyProvider;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpClient;
import reactor.util.context.Context;

/**
 * @author Stephane Maldini
 */
final class HttpClientDoOnError extends HttpClientOperator {

	final BiConsumer<? super HttpClientRequest, ? super Throwable>  onRequestError;
	final BiConsumer<? super HttpClientResponse, ? super Throwable> onResponseError;

	HttpClientDoOnError(HttpClient client,
			@Nullable BiConsumer<? super HttpClientRequest, ? super Throwable> onRequestError,
			@Nullable BiConsumer<? super HttpClientResponse, ? super Throwable> onResponseError) {
		super(client);
		this.onRequestError = onRequestError;
		this.onResponseError = onResponseError;
	}

	@Override
	protected TcpClient tcpConfiguration() {
		return new OnErrorTcpClient(source.tcpConfiguration(),
				onRequestError,
				onResponseError);
	}

	final static class PreparingHttpClientRequest implements HttpClientRequest {

		final HttpHeaders         headers;
		final HttpMethod          method;
		final String              uri;
		final String              path;
		final Context             context;
		final ClientCookieDecoder cookieDecoder;
		final boolean             isWebsocket;

		PreparingHttpClientRequest(Context context, HttpClientConfiguration c) {
			this.context = context;
			this.headers = c.headers;
			this.cookieDecoder = c.cookieDecoder;
			this.uri = c.uri;
			this.path = HttpOperations.resolvePath(this.uri);
			this.method = c.method;
			this.isWebsocket = c.websocketSubprotocols != null;
		}

		@Override
		public HttpClientRequest addCookie(Cookie cookie) {
			throw new UnsupportedOperationException("Should not add Cookie");
		}

		@Override
		public HttpClientRequest addHeader(CharSequence name, CharSequence value) {
			throw new UnsupportedOperationException("Should not add Header");
		}

		@Override
		public HttpClientRequest header(CharSequence name, CharSequence value) {
			throw new UnsupportedOperationException("Should not add Header");
		}

		@Override
		public HttpClientRequest headers(HttpHeaders headers) {
			throw new UnsupportedOperationException("Should not add Header");
		}

		@Override
		public Context currentContext() {
			return context;
		}

		@Override
		public boolean isFollowRedirect() {
			return true;
		}

		@Override
		public String[] redirectedFrom() {
			return EMPTY;
		}

		@Override
		public String resourceUrl() {
			return null;
		}

		final static String[] EMPTY = new String[0];

		@Override
		public HttpHeaders requestHeaders() {
			return headers != null ? headers : EmptyHttpHeaders.INSTANCE;
		}

		@Override
		public Map<CharSequence, Set<Cookie>> cookies() {
			if (headers == null) {
				return Collections.emptyMap();
			}
			return Cookies.newClientResponseHolder(headers, cookieDecoder)
			              .getCachedCookies();
		}

		@Override
		public boolean isKeepAlive() {
			if (headers == null) {
				return true;
			}

			return HttpHeaderValues.CLOSE.contentEqualsIgnoreCase(headers.get(
					HttpHeaderNames.CONNECTION));
		}

		@Override
		public boolean isWebsocket() {
			return isWebsocket;
		}

		@Override
		public HttpMethod method() {
			return method;
		}

		@Override
		public String uri() {
			return uri;
		}

		@Override
		public String fullPath() {
			return path;
		}

		@Override
		public HttpVersion version() {
			return HttpVersion.HTTP_1_1;
		}
	}

	static final class OnErrorTcpClient extends TcpClient implements ConnectionObserver {

		final TcpClient                                                 sourceTcp;
		final BiConsumer<? super HttpClientRequest, ? super Throwable>  onRequestError;
		final BiConsumer<? super HttpClientResponse, ? super Throwable> onResponseError;

		OnErrorTcpClient(TcpClient sourceTcp,
				@Nullable BiConsumer<? super HttpClientRequest, ? super Throwable> onRequestError,
				@Nullable BiConsumer<? super HttpClientResponse, ? super Throwable> onResponseError) {
			this.onRequestError = onRequestError;
			this.onResponseError = onResponseError;
			this.sourceTcp = sourceTcp;
		}

		@Override
		public void onStateChange(Connection connection,
				ConnectionObserver.State newState) {
			HttpClientOperations ops = connection.as(HttpClientOperations.class);
			if (ops == null) {
				return;
			}
			if (onResponseError != null && newState == HttpClientState.RESPONSE_INCOMPLETE && ops.responseState != null) {
				onResponseError.accept(ops, new PrematureCloseException("Connection prematurely closed DURING response"));
			}
		}

		@Override
		public Mono<? extends Connection> connect(Bootstrap b) {
			if (onRequestError != null) {
				HttpClientConfiguration c = HttpClientConfiguration.get(b);
				return sourceTcp.connect(b)
				                .onErrorResume(error ->
				                	Mono.subscriberContext()
					                    .doOnNext(ctx -> onRequestError.accept(new PreparingHttpClientRequest(ctx, c), error))
					                    .then(Mono.error(error))
				                );
			}
			else {
				return sourceTcp.connect(b);
			}
		}

		@Override
		public Bootstrap configure() {
			Bootstrap bootstrap = sourceTcp.configure();
			ConnectionObserver observer = BootstrapHandlers.connectionObserver(bootstrap);
			BootstrapHandlers.connectionObserver(bootstrap, observer.then(this));
			return bootstrap;
		}

		@Nullable
		@Override
		public ProxyProvider proxyProvider() {
			return sourceTcp.proxyProvider();
		}

		@Nullable
		@Override
		public SslProvider sslProvider() {
			return sourceTcp.sslProvider();
		}
	}
}
