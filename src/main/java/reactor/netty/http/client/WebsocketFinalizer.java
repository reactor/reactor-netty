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

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class WebsocketFinalizer extends HttpClient implements HttpClient.WebsocketSender {

	final TcpClient cachedConfiguration;

	WebsocketFinalizer(TcpClient parent) {
		this.cachedConfiguration = parent;
	}

	// UriConfiguration methods

	@Override
	public WebsocketSender uri(String uri) {
		return new WebsocketFinalizer(cachedConfiguration.bootstrap(b -> HttpClientConfiguration.uri(b, uri)));
	}

	@Override
	public WebsocketSender uri(Mono<String> uri) {
		return new WebsocketFinalizer(cachedConfiguration.bootstrap(b -> HttpClientConfiguration.deferredConf(b, conf -> uri.map(conf::uri))));
	}

	// WebsocketSender methods
	@Override
	public WebsocketFinalizer send(Function<? super HttpClientRequest, ? extends Publisher<Void>> sender) {
		Objects.requireNonNull(sender, "requestBody");
		return new WebsocketFinalizer(cachedConfiguration.bootstrap(b -> HttpClientConfiguration.body(b, (req, out) -> sender.apply(req))));
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<WebsocketClientOperations> connect() {
		return (Mono<WebsocketClientOperations>)cachedConfiguration.connect();
	}

	@Override
	public ByteBufFlux receive() {
		return HttpClientFinalizer.content(cachedConfiguration, HttpClientFinalizer.contentReceiver);
	}

	@Override
	public <V> Flux<V> handle(BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<V>> receiver) {
		return connect().flatMapMany(c -> Flux.from(receiver.apply(c, c))
		                                      .doFinally(s -> HttpClientFinalizer.discard(c)));
	}
}

