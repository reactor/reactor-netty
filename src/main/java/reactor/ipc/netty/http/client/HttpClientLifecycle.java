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

import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class HttpClientLifecycle extends HttpClientOperator implements
                                                           ConnectionObserver,
                                                           Function<Bootstrap, Bootstrap> {

	final BiConsumer<? super HttpClientRequest, ? super Connection> onRequest;
	final BiConsumer<? super HttpClientRequest, ? super Connection> afterRequest;
	final BiConsumer<? super HttpClientResponse, ? super Connection> onResponse;
	final BiConsumer<? super HttpClientResponse, ? super Connection> afterResponse;

	HttpClientLifecycle(HttpClient client,
			@Nullable BiConsumer<? super HttpClientRequest,  ? super Connection> onRequest,
			@Nullable BiConsumer<? super HttpClientRequest,  ? super Connection> afterRequest,
			@Nullable BiConsumer<? super HttpClientResponse, ? super Connection> onResponse,
			@Nullable BiConsumer<? super HttpClientResponse,  ? super Connection> afterResponse) {
		super(client);
		this.onRequest = onRequest;
		this.afterRequest = afterRequest;
		this.onResponse = onResponse;
		this.afterResponse = afterResponse;
	}

	@Override
	public Bootstrap apply(Bootstrap bootstrap) {
		BootstrapHandlers.connectionObserver(bootstrap, this);
		return bootstrap;
	}

	@Override
	public void onStateChange(Connection connection, State newState) {
		if (newState == State.CONFIGURED) {
			if (onRequest != null) {
				onRequest.accept(connection.as(HttpClientOperations.class), connection);
			}

			if (afterResponse != null) {
				connection.onDispose(() ->
						afterResponse.accept(connection.as(HttpClientOperations.class), connection));
			}
			return;
		}
		if (afterRequest != null && newState == HttpClientOperations.REQUEST_SENT) {
			afterRequest.accept(connection.as(HttpClientOperations.class), connection);
			return;
		}
		if (onResponse != null && newState == HttpClientOperations.RESPONSE_RECEIVED) {
			onResponse.accept(connection.as(HttpClientOperations.class), connection);
		}
	}

	@Override
	protected TcpClient tcpConfiguration() {
		return super.tcpConfiguration().bootstrap(this);
	}

}
