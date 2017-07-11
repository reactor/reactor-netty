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
package reactor.ipc.netty.http.server;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.CompositeNettyContext;
import reactor.ipc.netty.NettyContext;

/**
 * @author Simon Baslé
 */
public final class HttpMultiServer implements HttpBinding {

	List<HttpServer.Builder> multiOptions = new ArrayList<>();

	public HttpMultiServer(Consumer<? super HttpServerOptions.Builder> firstOptions) {
		multiOptions.add(HttpServer.builder().options(firstOptions));
	}

	public final HttpMultiServer and(
			Consumer<? super HttpServerOptions.Builder> otherOptions) {
		multiOptions.add(HttpServer.builder()
		                           .options(otherOptions));
		return this;
	}

	@Override
	public Mono<? extends NettyContext> newHandler(
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		if (multiOptions.size() == 1) {
			return multiOptions.get(0)
			                   .build()
			                   .newHandler(handler);
		}
		else {
			return Flux.fromIterable(multiOptions)
			           .map(HttpServer.Builder::build)
			           .flatMap(binding -> binding.newHandler(handler))
			           .collectList()
			           .map(CompositeNettyContext::new);
		}
	}
}
