/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.netty.bootstrap.Bootstrap;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;
import reactor.ipc.netty.tcp.TcpClient;

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
		return new WebsocketFinalizer(cachedConfiguration.bootstrap(b -> HttpClientConfiguration.deferredUri(b, uri)));
	}

	// WebsocketSender methods
	@Override
	public WebsocketFinalizer send(Function<? super HttpClientRequest, ? extends Publisher<Void>> sender) {
		Objects.requireNonNull(sender, "requestBody");
		return new WebsocketFinalizer(cachedConfiguration.bootstrap(b -> HttpClientConfiguration.body(b, (req, out) -> sender.apply(req))));
	}

	@Override
	public <V> Flux<V> handle(BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<V>> receiver) {
		Bootstrap b;
		try {
			b = cachedConfiguration.configure();
		}
		catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			return Flux.error(t);
		}
		return cachedConfiguration.connect(b)
		                          .flatMapMany(c -> {
			                          WebsocketClientOperations wsOps =
					                          c.as(WebsocketClientOperations.class);
			                          return Flux.from(receiver.apply(wsOps, wsOps));
		                          });
	}
}

