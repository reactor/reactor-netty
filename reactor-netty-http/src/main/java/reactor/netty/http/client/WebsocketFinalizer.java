/*
 * Copyright (c) 2018-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import java.net.URI;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.Connection;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;

import static reactor.netty.http.client.HttpClientFinalizer.contentReceiver;

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
	public ByteBufFlux receive() {
		ByteBufAllocator alloc = (ByteBufAllocator) configuration().options()
		                                                           .get(ChannelOption.ALLOCATOR);
		if (alloc == null) {
			alloc = ByteBufAllocator.DEFAULT;
		}

		@SuppressWarnings("unchecked")
		Mono<ChannelOperations<?, ?>> connector = (Mono<ChannelOperations<?, ?>>) connect();
		return ByteBufFlux.fromInbound(connector.flatMapMany(contentReceiver), alloc);
	}

	@Override
	protected HttpClient duplicate() {
		return new WebsocketFinalizer(new HttpClientConfig(config));
	}
}

