/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
import brave.http.HttpClientHandler;
import brave.http.HttpClientRequest;
import brave.http.HttpClientResponse;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.client.HttpClient;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;
import static reactor.netty.http.brave.ReactorNettyHttpTracing.SPAN_ATTR_KEY;

/**
 * This class is based on
 * https://github.com/spring-cloud/spring-cloud-sleuth/blob/d969aa7b549e9c82729e16dc7d846654e32ddef9/spring-cloud-sleuth-core/src/main/java/org/springframework/cloud/sleuth/instrument/web/client/HttpClientBeanPostProcessor.java.
 */
final class TracingHttpClientDecorator {

	final CurrentTraceContext currentTraceContext;
	final HttpClientHandler<HttpClientRequest, HttpClientResponse> handler;
	final Function<String, String> uriMapping;

	TracingHttpClientDecorator(HttpTracing httpTracing, Function<String, String> uriMapping) {
		requireNonNull(httpTracing, "httpTracing");
		this.currentTraceContext = httpTracing.tracing().currentTraceContext();
		this.handler = HttpClientHandler.create(httpTracing);
		this.uriMapping = requireNonNull(uriMapping, "uriMapping");
	}

	HttpClient decorate(HttpClient client) {
		TracingDoOnResponse onResponse = new TracingDoOnResponse(handler, uriMapping);
		return client.doOnRequest(new TracingDoOnRequest(handler, uriMapping))
		             .doOnResponse(onResponse)
		             .doOnRedirect(onResponse)
		             .doOnError(new TracingDoOnRequestError(handler, uriMapping),
		                        new TracingDoOnResponseError(handler, uriMapping))
		             .doOnChannelInit(new TracingChannelPipelineConfigurer(currentTraceContext))
		             .mapConnect(new TracingMapConnect(currentTraceContext));
	}

	static void cleanup(Channel channel) {
		EventLoop eventLoop = channel.eventLoop();
		if (eventLoop.inEventLoop()) {
			channel.attr(SPAN_ATTR_KEY).set(null);
		}
		else {
			eventLoop.execute(() -> channel.attr(SPAN_ATTR_KEY).set(null));
		}
	}

	abstract static class AbstractTracingDoOnHandler {

		final HttpClientHandler<HttpClientRequest, HttpClientResponse> handler;

		AbstractTracingDoOnHandler(HttpClientHandler<HttpClientRequest, HttpClientResponse> handler) {
			this.handler = handler;
		}

		void handleReceive(HttpClientResponse response, ContextView contextView) {
			PendingSpan pendingSpan = contextView.get(PendingSpan.class);
			Span span = pendingSpan.getAndSet(null);
			if (span != null) {
				handler.handleReceive(response, span);
			}
		}
	}

	static final class DelegatingHttpRequest extends HttpClientRequest {

		final reactor.netty.http.client.HttpClientRequest delegate;
		final Function<String, String> uriMapping;

		DelegatingHttpRequest(reactor.netty.http.client.HttpClientRequest delegate, Function<String, String> uriMapping) {
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
		public void header(String name, String value) {
			requireNonNull(name, "name");
			requireNonNull(value, "value");
			delegate.header(name, value);
		}

		@Override
		public String method() {
			return delegate.method().name();
		}

		@Override
		public String path() {
			return delegate.fullPath();
		}

		@Override
		public String route() {
			return uriMapping.apply(path());
		}

		@Override
		public Object unwrap() {
			return delegate;
		}

		@Override
		@Nullable
		public String url() {
			return delegate.resourceUrl();
		}
	}

	static final class DelegatingHttpResponse extends HttpClientResponse {

		final reactor.netty.http.client.HttpClientResponse delegate;
		final Throwable error;
		final HttpClientRequest request;

		DelegatingHttpResponse(reactor.netty.http.client.HttpClientResponse delegate, HttpClientRequest request, @Nullable Throwable error) {
			this.delegate = delegate;
			this.request = request;
			this.error = error;
		}

		@Override
		public int statusCode() {
			try {
				return delegate.status().code();
			}
			catch (IllegalStateException e) {
				return 0;
			}
		}

		@Override
		public Object unwrap() {
			return delegate;
		}

		@Override
		public HttpClientRequest request() {
			return request;
		}

		@Override
		@Nullable
		public Throwable error() {
			return error;
		}
	}

	static final class PendingSpan extends AtomicReference<Span> {
	}

	static final class TracingDoOnRequest
			implements BiConsumer<reactor.netty.http.client.HttpClientRequest, Connection> {

		final HttpClientHandler<HttpClientRequest, HttpClientResponse> handler;
		final Function<String, String> uriMapping;

		TracingDoOnRequest(
				HttpClientHandler<HttpClientRequest, HttpClientResponse> handler,
				Function<String, String> uriMapping) {
			this.handler = handler;
			this.uriMapping = uriMapping;
		}

		@Override
		public void accept(reactor.netty.http.client.HttpClientRequest request, Connection connection) {
			ContextView contextView = request.currentContextView();
			TraceContext parent = contextView.getOrDefault(TraceContext.class, null);

			HttpClientRequest braveRequest = new DelegatingHttpRequest(request, uriMapping);
			Span span = handler.handleSendWithParent(braveRequest, parent);

			SocketAddress remoteAddress = connection.channel().remoteAddress();
			if (remoteAddress instanceof InetSocketAddress) {
				InetSocketAddress address = (InetSocketAddress) remoteAddress;
				span.remoteIpAndPort(address.getHostString(), address.getPort());
			}

			PendingSpan pendingSpan = contextView.get(PendingSpan.class);
			Span oldSpan = pendingSpan.getAndSet(span);
			if (oldSpan != null) {
				oldSpan.abandon();
			}

			AtomicReference<SpanCustomizer> ref = contextView.get(SpanCustomizer.class.getName());
			ref.set(span.customizer());

			connection.channel().attr(SPAN_ATTR_KEY).set(span);
		}
	}

	static final class TracingDoOnRequestError extends AbstractTracingDoOnHandler
			implements BiConsumer<reactor.netty.http.client.HttpClientRequest, Throwable> {

		final Function<String, String> uriMapping;

		TracingDoOnRequestError(
				HttpClientHandler<HttpClientRequest, HttpClientResponse> handler,
				Function<String, String> uriMapping) {
			super(handler);
			this.uriMapping = uriMapping;
		}

		@Override
		public void accept(reactor.netty.http.client.HttpClientRequest request, Throwable throwable) {
			if (request instanceof reactor.netty.http.client.HttpClientResponse) {
				DelegatingHttpResponse delegate = new DelegatingHttpResponse((reactor.netty.http.client.HttpClientResponse) request,
						new DelegatingHttpRequest(request, uriMapping), throwable);
				handleReceive(delegate, request.currentContextView());
				cleanup(((ChannelOperations<?, ?>) request).channel());
			}
			else {
				PendingSpan pendingSpan = request.currentContextView().get(PendingSpan.class);
				Span span = pendingSpan.getAndSet(null);
				if (span != null) {
					span.error(throwable).finish();
				}
			}
		}
	}

	static final class TracingDoOnResponseError extends AbstractTracingDoOnHandler
			implements BiConsumer<reactor.netty.http.client.HttpClientResponse, Throwable> {

		final Function<String, String> uriMapping;

		TracingDoOnResponseError(
				HttpClientHandler<HttpClientRequest, HttpClientResponse> handler,
				Function<String, String> uriMapping) {
			super(handler);
			this.uriMapping = uriMapping;
		}

		@Override
		public void accept(reactor.netty.http.client.HttpClientResponse response, Throwable throwable) {
			DelegatingHttpResponse delegate = new DelegatingHttpResponse(response,
					new DelegatingHttpRequest((reactor.netty.http.client.HttpClientRequest) response, uriMapping), throwable);
			handleReceive(delegate, response.currentContextView());
			cleanup(((ChannelOperations<?, ?>) response).channel());
		}
	}

	static final class TracingDoOnResponse extends AbstractTracingDoOnHandler
			implements BiConsumer<reactor.netty.http.client.HttpClientResponse, Connection> {

		final Function<String, String> uriMapping;

		TracingDoOnResponse(
				HttpClientHandler<HttpClientRequest, HttpClientResponse> handler,
				Function<String, String> uriMapping) {
			super(handler);
			this.uriMapping = uriMapping;
		}

		@Override
		public void accept(reactor.netty.http.client.HttpClientResponse response, Connection connection) {
			HttpClientResponse delegate = new DelegatingHttpResponse(response,
					new DelegatingHttpRequest((reactor.netty.http.client.HttpClientRequest) response, uriMapping), null);
			handleReceive(delegate, response.currentContextView());
			cleanup(((ChannelOperations<?, ?>) response).channel());
		}
	}

	static final class TracingMapConnect implements Function<Mono<? extends Connection>, Mono<? extends Connection>> {

		final CurrentTraceContext currentTraceContext;

		TracingMapConnect(CurrentTraceContext currentTraceContext) {
			this.currentTraceContext = currentTraceContext;
		}

		@Override
		public Mono<? extends Connection> apply(Mono<? extends Connection> mono) {
			PendingSpan pendingSpan = new PendingSpan();
			return mono.doOnCancel(() -> {
			               Span span = pendingSpan.getAndSet(null);
			               if (span != null) {
			                   span.annotate("cancel").finish();
			               }
			           })
			           .contextWrite(ctx -> {
			               TraceContext invocationContext = currentTraceContext.get();
			               if (invocationContext != null) {
			                   ctx = ctx.put(TraceContext.class, invocationContext);
			               }
			               return ctx.put(PendingSpan.class, pendingSpan)
			                         .put(SpanCustomizer.class.getName(), new AtomicReference<SpanCustomizer>());
			           });
		}
	}
}