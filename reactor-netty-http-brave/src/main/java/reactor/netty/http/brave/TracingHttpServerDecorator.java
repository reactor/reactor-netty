/*
 * Copyright (c) 2020-2021 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.brave;

import brave.Span;
import brave.SpanCustomizer;
import brave.http.HttpServerHandler;
import brave.http.HttpServerRequest;
import brave.http.HttpServerResponse;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoop;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.http.server.HttpServer;
import reactor.util.annotation.Nullable;

import java.net.InetSocketAddress;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;
import static reactor.netty.ConnectionObserver.State.CONFIGURED;
import static reactor.netty.http.brave.ReactorNettyHttpTracing.REQUEST_ATTR_KEY;
import static reactor.netty.http.brave.ReactorNettyHttpTracing.SPAN_ATTR_KEY;
import static reactor.netty.http.server.HttpServerState.REQUEST_DECODING_FAILED;

final class TracingHttpServerDecorator {

	final CurrentTraceContext currentTraceContext;
	final HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;
	final Function<String, String> uriMapping;

	TracingHttpServerDecorator(HttpTracing httpTracing, Function<String, String> uriMapping) {
		requireNonNull(httpTracing, "httpTracing");
		this.currentTraceContext = httpTracing.tracing().currentTraceContext();
		this.handler = HttpServerHandler.create(httpTracing);
		this.uriMapping = requireNonNull(uriMapping, "uriMapping");
	}

	HttpServer decorate(HttpServer server) {
		return server.childObserve(new TracingConnectionObserver(currentTraceContext, handler, uriMapping))
		             .doOnChannelInit(new TracingChannelPipelineConfigurer(currentTraceContext))
		             .mapHandle(new TracingMapHandle(currentTraceContext, handler));
	}

	static final class DelegatingHttpRequest extends HttpServerRequest {

		final reactor.netty.http.server.HttpServerRequest delegate;
		final Function<String, String> uriMapping;
		final String path;

		DelegatingHttpRequest(reactor.netty.http.server.HttpServerRequest delegate, Function<String, String> uriMapping) {
			this.delegate = delegate;
			this.uriMapping = uriMapping;
			this.path = initPath();
		}

		@Nullable
		String initPath() {
			try {
				return delegate.fullPath();
			}
			catch (IllegalStateException e) {
				return null;
			}
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
			return path;
		}

		@Override
		@Nullable
		public String route() {
			return path == null ? null : uriMapping.apply(path);
		}

		@Override
		public Object unwrap() {
			return delegate;
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

		final reactor.netty.http.server.HttpServerResponse delegate;
		final HttpServerRequest request;
		final Throwable error;

		DelegatingHttpResponse(reactor.netty.http.server.HttpServerResponse delegate, @Nullable HttpServerRequest request) {
			this(delegate, request, null);
		}

		DelegatingHttpResponse(
				reactor.netty.http.server.HttpServerResponse delegate,
				@Nullable HttpServerRequest request,
				@Nullable Throwable error) {
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
			return delegate;
		}
	}

	static final class TracingConnectionObserver implements ConnectionObserver {

		final CurrentTraceContext currentTraceContext;
		final HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;
		final Function<String, String> uriMapping;
		final ChannelHandler inboundHandler;
		final ChannelHandler outboundHandler;

		TracingConnectionObserver(
				CurrentTraceContext currentTraceContext,
				HttpServerHandler<HttpServerRequest, HttpServerResponse> handler,
				Function<String, String> uriMapping) {
			this.currentTraceContext = currentTraceContext;
			this.inboundHandler = new TracingChannelInboundHandler(currentTraceContext);
			this.outboundHandler = new TracingChannelOutboundHandler(currentTraceContext);
			this.handler = handler;
			this.uriMapping = uriMapping;
		}

		@Override
		public void onStateChange(Connection connection, State state) {
			if (state == CONFIGURED && connection instanceof reactor.netty.http.server.HttpServerRequest) {
				reactor.netty.http.server.HttpServerRequest request = (reactor.netty.http.server.HttpServerRequest) connection;

				HttpServerRequest braveRequest = new DelegatingHttpRequest(request, uriMapping);
				Span span = handler.handleReceive(braveRequest);

				connection.channel().attr(REQUEST_ATTR_KEY).set(braveRequest);
				connection.channel().attr(SPAN_ATTR_KEY).set(span);

				return;
			}
			if (state == REQUEST_DECODING_FAILED && connection instanceof reactor.netty.http.server.HttpServerResponse) {
				reactor.netty.http.server.HttpServerResponse response = (reactor.netty.http.server.HttpServerResponse) connection;


				HttpServerRequest braveRequest = connection.channel().attr(REQUEST_ATTR_KEY).getAndSet(null);
				if (braveRequest == null && connection instanceof reactor.netty.http.server.HttpServerRequest) {
					braveRequest = new DelegatingHttpRequest((reactor.netty.http.server.HttpServerRequest) connection, uriMapping);
				}

				Span span = connection.channel().attr(SPAN_ATTR_KEY).getAndSet(null);
				if (span == null) {
					span = handler.handleReceive(braveRequest);
				}
				handler.handleSend(new DelegatingHttpResponse(response, braveRequest), span);
			}
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			Span span = connection.channel().attr(SPAN_ATTR_KEY).getAndSet(null);
			if (span != null) {
				span.error(error).finish();
			}
		}
	}

	static final class TracingMapHandle implements BiFunction<Mono<Void>, Connection, Mono<Void>> {

		final CurrentTraceContext currentTraceContext;
		final HttpServerHandler<HttpServerRequest, HttpServerResponse> handler;

		volatile Throwable throwable;

		TracingMapHandle(
				CurrentTraceContext currentTraceContext,
				HttpServerHandler<HttpServerRequest, HttpServerResponse> handler) {
			this.currentTraceContext = currentTraceContext;
			this.handler = handler;
		}

		@Override
		public Mono<Void> apply(Mono<Void> voidMono, Connection connection) {
			HttpServerRequest braveRequest = connection.channel().attr(REQUEST_ATTR_KEY).get();
			Span span = connection.channel().attr(SPAN_ATTR_KEY).get();
			return voidMono.doFinally(sig -> {
			                   if (braveRequest.unwrap() instanceof reactor.netty.http.server.HttpServerResponse) {
			                       reactor.netty.http.server.HttpServerResponse response =
			                               (reactor.netty.http.server.HttpServerResponse) braveRequest.unwrap();
			                       Span localSpan = sig == SignalType.CANCEL ? span.annotate("cancel") : span;
			                       HttpServerResponse braveResponse =
			                               new DelegatingHttpResponse(response, braveRequest, throwable);
			                       response.withConnection(conn -> {
			                               conn.onTerminate()
			                                   .subscribe(
			                                           null,
			                                           t -> cleanup(connection.channel()),
			                                           () -> cleanup(connection.channel()));
			                               EventLoop eventLoop = conn.channel().eventLoop();
			                               if (eventLoop.inEventLoop()) {
			                                   handler.handleSend(braveResponse, localSpan);
			                               }
			                               else {
			                                   eventLoop.execute(() -> handler.handleSend(braveResponse, localSpan));
			                               }
			                       });
			                   }
			               })
			               .doOnError(this::throwable)
			               .contextWrite(ctx -> ctx.put(TraceContext.class, span.context())
			                                       .put(SpanCustomizer.class, span.customizer()));
		}

		void throwable(Throwable t) {
			this.throwable = t;
		}

		void cleanup(Channel channel) {
			EventLoop eventLoop = channel.eventLoop();
			if (eventLoop.inEventLoop()) {
				channel.attr(REQUEST_ATTR_KEY).set(null);
				channel.attr(SPAN_ATTR_KEY).set(null);
			}
			else {
				eventLoop.execute(() -> {
					channel.attr(REQUEST_ATTR_KEY).set(null);
					channel.attr(SPAN_ATTR_KEY).set(null);
				});
			}
		}
	}
}
