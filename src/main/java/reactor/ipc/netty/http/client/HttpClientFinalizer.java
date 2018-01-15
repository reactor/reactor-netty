/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;

import io.netty.buffer.ByteBuf;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.ByteBufMono;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class HttpClientFinalizer extends HttpClientOperator
		implements HttpClient.RequestSender {

	final TcpClient cachedConfiguration;
	final String    baseUri;

	HttpClientFinalizer(HttpClient parent) {
		super(parent);
		this.cachedConfiguration = parent.tcpConfiguration();
		this.baseUri = baseUri();
	}

	// UriConfiguration methods

	@Override
	public HttpClient.RequestSender uri(String uri) {
		return new HttpClientFinalizer(tcpConfiguration(tcp -> tcp.attr(HttpClientConnect.URI, uri)));
	}

	@Override
	public HttpClient.RequestSender uri(Mono<String> uri) {
		return new HttpClientFinalizer(tcpConfiguration(tcp ->
				tcp.bootstrap(b -> b.attr(HttpClientConnect.URI, HttpClientConnect.MONO_URI_MARKER)
				                    .attr(HttpClientConnect.MONO_URI, uri))));
	}

	// ResponseReceiver methods

	@Override
	@SuppressWarnings("unchecked")
	public Mono<HttpClientResponse> response() {
		return this.connect(cachedConfiguration)
		           .cast(HttpClientResponse.class);
	}

	@Override
	public <V> Flux<V> response(BiFunction<? super HttpClientResponse, ? super ByteBufFlux, ? extends Publisher<V>> receiver) {
		return response().flatMapMany(resp -> Flux.from(receiver.apply(resp,
				resp.receive()))
		                                          .doFinally(s -> resp.dispose()));
	}

	@Override
	public ByteBufFlux responseContent() {
		// TODO assign allocator
		return ByteBufFlux.fromInbound(response().flatMapMany((HttpClientResponse::receive)));
	}

	@Override
	public <V> Mono<V> responseSingle(BiFunction<? super HttpClientResponse, ? super ByteBufMono, ? extends Mono<V>> receiver) {
		return response().flatMap(resp -> receiver.apply(resp,
				resp.receive()
				    .aggregate()).doFinally(s -> resp.dispose()));
	}

	// RequestSender methods

	@Override
	public HttpClient.ResponseReceiver<?> send(Publisher<? extends ByteBuf> requestBody) {
		Objects.requireNonNull(requestBody, "requestBody");
		return send((req, out) -> out.sendObject(requestBody));

	}

	@Override
	public HttpClient.ResponseReceiver<?> send(BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends NettyOutbound> sender) {
		Objects.requireNonNull(sender, "requestBody");
		return new HttpClientFinalizer(tcpConfiguration(tcp -> tcp.attr(HttpClientConnect.BODY, sender)));
	}


}

