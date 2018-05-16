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
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.ByteBufMono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class HttpClientFinalizer extends HttpClient implements HttpClient.RequestSender {

	final TcpClient cachedConfiguration;

	HttpClientFinalizer(TcpClient parent) {
		this.cachedConfiguration = parent;
	}

	// UriConfiguration methods

	@Override
	public HttpClient.RequestSender uri(String uri) {
		return new HttpClientFinalizer(cachedConfiguration.bootstrap(b -> HttpClientConfiguration.uri(b, uri)));
	}

	@Override
	public HttpClient.RequestSender uri(Mono<String> uri) {
		return new HttpClientFinalizer(cachedConfiguration.bootstrap(b -> HttpClientConfiguration.deferredUri(b, uri)));
	}

	// ResponseReceiver methods

	@SuppressWarnings("unchecked")
	Mono<HttpClientOperations> connect() {
		return (Mono<HttpClientOperations>)cachedConfiguration.connect();
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<HttpClientResponse> response() {
		return connect().map(RESPONSE_ONLY);
	}

	@Override
	public <V> Flux<V> response(BiFunction<? super HttpClientResponse, ? super ByteBufFlux, ? extends Publisher<V>> receiver) {
		return connect().flatMapMany(resp -> Flux.from(receiver.apply(resp, resp.receive()))
		                                          .doFinally(s -> dispose(resp)));
	}

	@Override
	public <V> Flux<V> responseConnection(BiFunction<? super HttpClientResponse, ? super Connection, ? extends Publisher<V>> receiver) {
		return connect().flatMapMany(resp -> Flux.from(receiver.apply(resp, resp)));
	}

	@Override
	public ByteBufFlux responseContent() {
		Bootstrap b;
		try {
			b = cachedConfiguration.configure();
		}
		catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			return ByteBufFlux.fromInbound(Mono.error(t));
		}
		@SuppressWarnings("unchecked")
		ByteBufAllocator alloc = (ByteBufAllocator) b.config()
		                          .options()
		                          .getOrDefault(ChannelOption.ALLOCATOR, ByteBufAllocator.DEFAULT);

		return ByteBufFlux.fromInbound(connect().flatMapMany((HttpClientOperations::receive)), alloc);
	}

	@Override
	public <V> Mono<V> responseSingle(BiFunction<? super HttpClientResponse, ? super ByteBufMono, ? extends Mono<V>> receiver) {
		return connect().flatMap(resp -> receiver.apply(resp,
				resp.receive()
				    .aggregate()).doFinally(s -> dispose(resp)));
	}

	static void dispose(Connection c) {
		if (!c.isDisposed()) {
			c.channel().eventLoop().execute(c::dispose);
		}
	}


	// RequestSender methods
	@Override
	public HttpClientFinalizer send(Publisher<? extends ByteBuf> requestBody) {
		Objects.requireNonNull(requestBody, "requestBody");
		return send((req, out) -> out.sendObject(requestBody));
	}

	@Override
	public HttpClientFinalizer send(BiFunction<? super HttpClientRequest, ?
			super NettyOutbound, ? extends Publisher<Void>> sender) {
		Objects.requireNonNull(sender, "requestBody");
		return new HttpClientFinalizer(cachedConfiguration.bootstrap(b -> HttpClientConfiguration.body(b, sender)));
	}

	@Override
	public HttpClientFinalizer sendForm(BiConsumer<? super HttpClientRequest, HttpClientForm> formCallback, Consumer<Flux<Long>> progress) {
		Objects.requireNonNull(formCallback, "formCallback");
		return send((req, out) -> {
			@SuppressWarnings("unchecked")
			HttpClientOperations ops = (HttpClientOperations) out;
			return new HttpClientOperations.SendForm(ops, formCallback, progress);
		});
	}

	static final Function<HttpClientOperations, HttpClientResponse> RESPONSE_ONLY = ops -> {
		//defer the dispose to avoid over disposing on receive
		dispose(ops);
		return ops;
	};
}

