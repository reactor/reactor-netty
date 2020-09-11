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
package reactor.netty.http.server;

import brave.Span;
import brave.http.HttpServerHandler;
import brave.http.HttpServerRequest;
import brave.http.HttpServerResponse;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import io.netty.channel.EventLoop;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.util.annotation.Nullable;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;
import static reactor.netty.ConnectionObserver.State.CONFIGURED;
import static reactor.netty.http.server.HttpServerState.REQUEST_DECODING_FAILED;

/**
 * Brave instrumentation for {@link HttpServer}.
 *
 * {@code
 *     ConnectionObserver observer = BraveHttpServerTracing.create(httpTracing);
 *
 *     HttpServer.create()
 *               .port(0)
 *               .childObserve(observer)
 *               .handle((req, res) -> ...)
 *               .bindNow();
 * }
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class BraveHttpServerTracing implements ConnectionObserver {

	/**
	 * Create a new {@link ConnectionObserver} using a preconfigured {@link HttpTracing} instance.
	 *
	 * @param httpTracing a preconfigured {@link HttpTracing} instance
	 * @return a new {@link ConnectionObserver}
	 */
	public static ConnectionObserver create(HttpTracing httpTracing) {
		return create(httpTracing, Function.identity());
	}

	/**
	 * Create a new {@link ConnectionObserver} using a preconfigured {@link HttpTracing} instance.
	 * <p>{@code uriMapping} function receives the actual uri and returns the target uri value
	 * that will be used for the tracing.
	 * For example instead of using the actual uri {@code "/users/1"} as uri value, templated uri
	 * {@code "/users/{id}"} can be used.
	 *
	 * @param httpTracing a preconfigured {@link HttpTracing} instance
	 * @param uriMapping a function that receives the actual uri and returns the target uri value
	 * that will be used for the tracing
	 * @return a new {@link ConnectionObserver}
	 */
	public static ConnectionObserver create(HttpTracing httpTracing, Function<String, String> uriMapping) {
		return new BraveHttpServerTracing(httpTracing, uriMapping);
	}

	@Override
	public void onStateChange(Connection connection, State state) {
		if (state == CONFIGURED && connection instanceof HttpServerOperations) {
			HttpServerOperations ops = (HttpServerOperations) connection;
			HttpServerRequest request = new DelegatingHttpRequest(ops, uriMapping);
			Span span = handler.handleReceive(request);
			BraveHttpServerMapHandle mapHandle =
					new BraveHttpServerMapHandle(ops, currentTraceContext, handler, request, span);
			Function<? super Mono<Void>, ? extends Mono<Void>> current = ops.mapHandle;
			ops.mapHandle = current == null ? mapHandle : current.andThen(mapHandle);
			return;
		}
		if (state == REQUEST_DECODING_FAILED && connection instanceof HttpServerOperations) {
			HttpServerOperations ops = (HttpServerOperations) connection;
			HttpServerRequest request = new DelegatingHttpRequest(ops, uriMapping);
			Optional<TracingContext> tracingContext =
					TracingContext.of(((HttpServerOperations) connection).currentContext());
			if (tracingContext.isPresent()) {
				tracingContext.get()
				              .span(span -> handler.handleSend(new DelegatingHttpResponse(ops, request), span));
			}
			else {
				Span span = handler.handleReceive(request);
				handler.handleSend(new DelegatingHttpResponse(ops, request), span);
			}
		}
	}

	@Override
	public void onUncaughtException(Connection connection, Throwable error) {
		if (connection instanceof HttpServerOperations) {
			TracingContext.of(((HttpServerOperations) connection).currentContext())
			              .ifPresent(tracingContext -> tracingContext.span(span -> span.error(error).finish()));
		}
	}

	final CurrentTraceContext currentTraceContext;
	final HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;
	final Function<String, String> uriMapping;

	BraveHttpServerTracing(HttpTracing httpTracing, Function<String, String> uriMapping) {
		requireNonNull(httpTracing, "httpTracing");
		this.currentTraceContext = httpTracing.tracing().currentTraceContext();
		this.handler = HttpServerHandler.create(httpTracing);
		this.uriMapping = requireNonNull(uriMapping, "uriMapping");
	}

	static final class BraveHttpServerMapHandle implements TracingContext, Function<Mono<Void>, Mono<Void>> {

		final HttpServerOperations ops;
		final CurrentTraceContext currentTraceContext;
		final HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;
		final HttpServerRequest request;
		final Span span;

		volatile Throwable throwable;

		BraveHttpServerMapHandle(
				HttpServerOperations ops,
				CurrentTraceContext currentTraceContext,
				HttpServerHandler<HttpServerRequest, HttpServerResponse> handler,
				HttpServerRequest request,
				Span span) {
			this.ops = ops;
			this.currentTraceContext = currentTraceContext;
			this.handler = handler;
			this.request = request;
			this.span = span;
		}

		@Override
		public Mono<Void> apply(Mono<Void> voidMono) {
			return voidMono.doFinally(sig -> {
			                   EventLoop eventLoop = ops.channel().eventLoop();
			                   if (eventLoop.inEventLoop()) {
			                       handleSend(throwable, sig == SignalType.CANCEL);
			                   }
			                   else {
			                       eventLoop.execute(() -> handleSend(throwable, sig == SignalType.CANCEL));
			                   }
			               })
			               .doOnError(this::throwable)
			               .contextWrite(ctx -> ctx.put(KEY_TRACING_CONTEXT, this));
		}

		@Override
		public TracingContext span(Consumer<Span> consumer) {
			requireNonNull(consumer, "consumer");
			consumer.accept(span);
			return this;
		}

		void handleSend(@Nullable Throwable throwable, boolean isCancel) {
			handler.handleSend(new DelegatingHttpResponse(ops, request, throwable),
			                   isCancel ? span.annotate("cancel") : span);
		}

		void throwable(Throwable t) {
			this.throwable = t;
		}
	}

	static final String KEY_TRACING_CONTEXT = "reactor.netty.http.server.TracingContext";

	static final class DelegatingHttpRequest extends brave.http.HttpServerRequest {

		final HttpServerOperations delegate;
		final Function<String, String> uriMapping;

		DelegatingHttpRequest(HttpServerOperations delegate, Function<String, String> uriMapping) {
			this.delegate = delegate;
			this.uriMapping = uriMapping;
		}

		@Override
		@Nullable
		public String header(String name) {
			requireNonNull(name, "name");
			return delegate.requestHeaders().get(name);
		}

		@Override
		public String method() {
			return delegate.method().name();
		}

		@Override
		public boolean parseClientIpAndPort(Span span) {
			requireNonNull(span, "span");
			//HttpServerOperations handles Forwarded/X-Forwarded and proxy protocol (HAProxy)
			InetSocketAddress remoteAddress = delegate.remoteAddress();
			if (remoteAddress == null) {
				// This can happen only in case of UDS or bad request
				return false;
			}
			return span.remoteIpAndPort(remoteAddress.getHostString(), remoteAddress.getPort());
		}

		@Override
		@Nullable
		public String path() {
			return delegate.path;
		}

		@Override
		@Nullable
		public String route() {
			return path() == null ? null : uriMapping.apply(path());
		}

		@Override
		public Object unwrap() {
			return delegate.nettyRequest;
		}

		@Override
		@Nullable
		public String url() {
			InetSocketAddress hostAddress = delegate.hostAddress();
			if (hostAddress == null) {
				// This can happen only in case of UDS
				return null;
			}

			String tempUri = delegate.uri();
			if (tempUri.isEmpty() || tempUri.charAt(0) == '/') {
				tempUri = delegate.scheme() + "://" + hostAddress.getHostString() + ":" + hostAddress.getPort() + tempUri;
			}
			else if (!SCHEME_PATTERN.matcher(tempUri).matches()) {
				tempUri = delegate.scheme() + "://" + tempUri;
			}
			return tempUri;
		}

		static final Pattern SCHEME_PATTERN = Pattern.compile("^(https?|wss?)://.*$");
	}

	static final class DelegatingHttpResponse extends HttpServerResponse {

		final HttpServerOperations delegate;
		final HttpServerRequest request;
		final Throwable error;

		DelegatingHttpResponse(HttpServerOperations delegate, HttpServerRequest request) {
			this(delegate, request, null);
		}

		DelegatingHttpResponse(HttpServerOperations delegate, HttpServerRequest request, @Nullable Throwable error) {
			this.delegate = delegate;
			this.request = request;
			this.error = error;
		}

		@Override
		@Nullable
		public HttpServerRequest request() {
			return request;
		}

		@Override
		@Nullable
		public Throwable error() {
			return error;
		}

		@Override
		public int statusCode() {
			return delegate.status().code();
		}

		@Override
		public Object unwrap() {
			return delegate.outboundHttpMessage();
		}
	}
}
