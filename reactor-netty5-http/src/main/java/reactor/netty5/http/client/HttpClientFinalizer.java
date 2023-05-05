/*
 * Copyright (c) 2017-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.client;

import java.net.URI;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.channel.ChannelOption;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty5.BufferFlux;
import reactor.netty5.BufferMono;
import reactor.netty5.Connection;
import reactor.netty5.NettyOutbound;
import reactor.netty5.channel.ChannelOperations;
import reactor.util.annotation.Nullable;

import static io.netty5.buffer.DefaultBufferAllocators.preferredAllocator;
import static reactor.netty5.http.HttpOperations.bufferExtractorFunction;

/**
 * Configures the HTTP request before calling one of the terminal,
 * {@link Publisher} based, {@link ResponseReceiver} API.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class HttpClientFinalizer extends HttpClientConnect implements HttpClient.RequestSender {

	HttpClientFinalizer(HttpClientConfig config) {
		super(config);
	}

	// UriConfiguration methods

	@Override
	public HttpClient.RequestSender uri(Mono<String> uri) {
		Objects.requireNonNull(uri, "uri");
		HttpClient dup = duplicate();
		dup.configuration().deferredConf(config -> uri.map(s -> {
			config.uriStr = s;
			config.uri = null;
			return config;
		}));
		return (HttpClientFinalizer) dup;
	}

	@Override
	public HttpClient.RequestSender uri(String uri) {
		Objects.requireNonNull(uri, "uri");
		HttpClient dup = duplicate();
		dup.configuration().uriStr = uri;
		dup.configuration().uri = null;
		return (HttpClientFinalizer) dup;
	}

	@Override
	public RequestSender uri(URI uri) {
		Objects.requireNonNull(uri, "uri");
		if (!uri.isAbsolute()) {
			throw new IllegalArgumentException("URI is not absolute: " + uri);
		}
		HttpClient dup = duplicate();
		dup.configuration().uriStr = null;
		dup.configuration().uri = uri;
		return (HttpClientFinalizer) dup;
	}

	// ResponseReceiver methods

	@Override
	public Mono<HttpClientResponse> response() {
		return _connect().map(RESPONSE_ONLY);
	}

	@Override
	public <V> Flux<V> response(BiFunction<? super HttpClientResponse, ? super BufferFlux, ? extends Publisher<V>> receiver) {
		return _connect().flatMapMany(resp -> Flux.from(receiver.apply(resp, resp.receive()))
		                                          .doFinally(s -> discard(resp))
		                                          .contextWrite(resp.currentContextView()));
	}

	@Override
	public <V> Flux<V> responseConnection(BiFunction<? super HttpClientResponse, ? super Connection, ? extends Publisher<V>> receiver) {
		return _connect().flatMapMany(resp -> Flux.from(receiver.apply(resp, resp))
		                                          .contextWrite(resp.currentContextView()));
	}

	@Override
	public BufferFlux responseContent() {
		BufferAllocator alloc = (BufferAllocator) configuration().options()
		                                                         .get(ChannelOption.BUFFER_ALLOCATOR);
		if (alloc == null) {
			alloc = preferredAllocator();
		}

		@SuppressWarnings("unchecked")
		Mono<ChannelOperations<?, ?>> connector = (Mono<ChannelOperations<?, ?>>) connect();
		return BufferFlux.fromInbound(connector.flatMapMany(contentReceiver), alloc, bufferExtractorFunction);
	}

	@Override
	public <V> Mono<V> responseSingle(BiFunction<? super HttpClientResponse, ? super BufferMono, ? extends Mono<V>> receiver) {
		return _connect().flatMap(resp -> receiver.apply(resp, resp.receive().aggregate())
		                                          .doFinally(s -> discard(resp))
		                                          .contextWrite(resp.currentContextView()));
	}

	// RequestSender methods

	@Override
	public HttpClientFinalizer send(
			BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>> sender) {
		Objects.requireNonNull(sender, "requestBody");
		HttpClient dup = duplicate();
		dup.configuration().body = sender;
		return (HttpClientFinalizer) dup;
	}

	@Override
	public HttpClientFinalizer send(Publisher<? extends Buffer> requestBody) {
		Objects.requireNonNull(requestBody, "requestBody");
		return send((req, out) -> out.send(requestBody));
	}

	@Override
	public HttpClientFinalizer sendForm(BiConsumer<? super HttpClientRequest, HttpClientForm> formCallback, @Nullable Consumer<Flux<Long>> progress) {
		Objects.requireNonNull(formCallback, "formCallback");
		return send((req, out) -> {
			HttpClientOperations ops = (HttpClientOperations) out;
			return new HttpClientOperations.SendForm(ops, formCallback, progress);
		});
	}

	@Override
	protected HttpClient duplicate() {
		return new HttpClientFinalizer(new HttpClientConfig(config));
	}

	@SuppressWarnings("unchecked")
	Mono<HttpClientOperations> _connect() {
		return (Mono<HttpClientOperations>) connect();
	}

	static void discard(HttpClientOperations c) {
		if (!c.isInboundDisposed()) {
			c.discard();
		}
	}

	static final Function<ChannelOperations<?, ?>, Flux<?>> contentReceiver = ChannelOperations::receiveObject;

	static final Function<HttpClientOperations, HttpClientResponse> RESPONSE_ONLY = ops -> {
		//defer the dispose to avoid over disposing on receive
		discard(ops);
		return ops;
	};
}

