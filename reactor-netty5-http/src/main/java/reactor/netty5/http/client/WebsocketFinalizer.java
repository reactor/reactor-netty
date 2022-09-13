/*
 * Copyright (c) 2018-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.util.function.BiFunction;
import java.util.function.Function;

import io.netty5.buffer.BufferAllocator;
import io.netty5.channel.ChannelOption;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty5.BufferFlux;
import reactor.netty5.Connection;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.http.websocket.WebsocketInbound;
import reactor.netty5.http.websocket.WebsocketOutbound;

import static io.netty5.buffer.DefaultBufferAllocators.preferredAllocator;
import static reactor.netty5.http.client.HttpClientFinalizer.contentReceiver;

/**
 * Configures the Websocket request before calling one of the terminal,
 * {@link Publisher} based, {@link WebsocketReceiver} API.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class WebsocketFinalizer extends HttpClientConnect implements HttpClient.WebsocketSender {

	WebsocketFinalizer(HttpClientConfig config) {
		super(config);
	}

	// UriConfiguration methods

	@Override
	public HttpClient.WebsocketSender uri(Mono<String> uri) {
		Objects.requireNonNull(uri, "uri");
		HttpClient dup = duplicate();
		dup.configuration().deferredConf(config -> uri.map(s -> {
			config.uriStr = s;
			config.uri = null;
			return config;
		}));
		return (WebsocketFinalizer) dup;
	}

	@Override
	public HttpClient.WebsocketSender uri(String uri) {
		Objects.requireNonNull(uri, "uri");
		HttpClient dup = duplicate();
		dup.configuration().uriStr = uri;
		dup.configuration().uri = null;
		return (WebsocketFinalizer) dup;
	}

	@Override
	public WebsocketSender uri(URI uri) {
		Objects.requireNonNull(uri, "uri");
		if (!uri.isAbsolute()) {
			throw new IllegalArgumentException("URI is not absolute: " + uri);
		}
		HttpClient dup = duplicate();
		dup.configuration().uriStr = null;
		dup.configuration().uri = uri;
		return (WebsocketFinalizer) dup;
	}

	// WebsocketSender methods

	@Override
	public WebsocketFinalizer send(Function<? super HttpClientRequest, ? extends Publisher<Void>> sender) {
		Objects.requireNonNull(sender, "requestBody");
		HttpClient dup = duplicate();
		dup.configuration().body = (req, out) -> sender.apply(req);
		return (WebsocketFinalizer) dup;
	}

	// WebsocketReceiver methods

	@Override
	public Mono<? extends Connection> connect() {
		return super.connect();
	}

	@Override
	public <V> Flux<V> handle(BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<V>> receiver) {
		@SuppressWarnings("unchecked")
		Mono<WebsocketClientOperations> connector = (Mono<WebsocketClientOperations>) connect();
		return connector.flatMapMany(c -> Flux.from(receiver.apply(c, c))
		                                      .doFinally(s -> HttpClientFinalizer.discard(c)));
	}

	@Override
	public BufferFlux receive() {
		BufferAllocator alloc = (BufferAllocator) configuration().options()
		                                                         .get(ChannelOption.BUFFER_ALLOCATOR);
		if (alloc == null) {
			alloc = preferredAllocator();
		}

		@SuppressWarnings("unchecked")
		Mono<ChannelOperations<?, ?>> connector = (Mono<ChannelOperations<?, ?>>) connect();
		return BufferFlux.fromInbound(connector.flatMapMany(contentReceiver), alloc);
	}

	@Override
	protected HttpClient duplicate() {
		return new WebsocketFinalizer(new HttpClientConfig(config));
	}
}

