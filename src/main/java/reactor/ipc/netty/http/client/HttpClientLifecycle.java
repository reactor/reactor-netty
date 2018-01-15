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

import java.util.function.Consumer;
import javax.annotation.Nullable;

import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class HttpClientLifecycle extends HttpClientOperator
		implements Consumer<Connection> {

	final Consumer<? super HttpClientRequest>  onRequest;
	final Consumer<? super HttpClientRequest>  afterRequest;
	final Consumer<? super HttpClientResponse> onResponse;
	final Consumer<? super HttpClientResponse> afterResponse;

	HttpClientLifecycle(HttpClient client,
			@Nullable Consumer<? super HttpClientRequest> onRequest,
			@Nullable Consumer<? super HttpClientRequest> afterRequest,
			@Nullable Consumer<? super HttpClientResponse> onResponse,
			@Nullable Consumer<? super HttpClientResponse> afterResponse) {
		super(client);
		this.onRequest = onRequest;
		this.afterRequest = afterRequest;
		this.onResponse = onResponse;
		this.afterResponse = afterResponse;
	}

	static final Consumer<? super Throwable> EMPTY_ERROR = e -> {};

	@Override
	@SuppressWarnings("unchecked")
	public void accept(Connection o) {
		if (onRequest != null) {
			onRequest.accept((HttpClientRequest) o);
		}
		HttpClientOperations ops = (HttpClientOperations)o;
		onHttpEvent(afterRequest, HttpClientOperations.HttpClientEvent.afterRequest, ops);
		onHttpEvent(onResponse, HttpClientOperations.HttpClientEvent.onResponse, ops);

		if (afterResponse != null) {
			ops.httpClientEvents.doOnComplete(() -> afterResponse.accept(ops));
		}
	}

	void onHttpEvent(@Nullable Consumer<? super HttpClientOperations> consumer,
			HttpClientOperations.HttpClientEvent event,
			HttpClientOperations ops) {
		if (consumer != null) {
			ops.httpClientEvents.filter(event::equals)
			                    .next()
			                    .subscribe(evt -> consumer.accept(ops), EMPTY_ERROR);
		}
	}

	@Override
	protected Mono<? extends Connection> connect(TcpClient delegate) {
		return super.connect(delegate.doOnConnected(this));
	}
}
