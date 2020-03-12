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

import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class HttpClientDoOn extends HttpClientOperator implements ConnectionObserver, Function<Bootstrap, Bootstrap> {

	final BiConsumer<? super HttpClientRequest, ? super Connection>  onRequest;
	final BiConsumer<? super HttpClientRequest, ? super Connection>  afterRequest;
	final BiConsumer<? super HttpClientResponse, ? super Connection> onResponse;
	final BiConsumer<? super HttpClientResponse, ? super Connection> afterResponseSuccess;
	final BiConsumer<? super HttpClientResponse, ? super Connection> onRedirect;


	HttpClientDoOn(HttpClient client,
			@Nullable BiConsumer<? super HttpClientRequest,  ? super Connection> onRequest,
			@Nullable BiConsumer<? super HttpClientRequest,  ? super Connection> afterRequest,
			@Nullable BiConsumer<? super HttpClientResponse, ? super Connection> onResponse,
			@Nullable BiConsumer<? super HttpClientResponse,  ? super Connection> afterResponseSuccess,
			@Nullable BiConsumer<? super HttpClientResponse, ? super Connection> onRedirect) {
		super(client);
		this.onRequest = onRequest;
		this.afterRequest = afterRequest;
		this.onResponse = onResponse;
		this.afterResponseSuccess = afterResponseSuccess;
		this.onRedirect = onRedirect;
	}

	@Override
	public Bootstrap apply(Bootstrap bootstrap) {
		ConnectionObserver observer = BootstrapHandlers.connectionObserver(bootstrap);
		BootstrapHandlers.connectionObserver(bootstrap, observer.then(this));
		return bootstrap;
	}

	@Override
	public void onStateChange(Connection connection, State newState) {
		if (onRequest != null && newState == HttpClientState.REQUEST_PREPARED) {
			onRequest.accept(connection.as(HttpClientOperations.class), connection);
			return;
		}
		if (afterResponseSuccess != null && newState == HttpClientState.RESPONSE_COMPLETED) {
			afterResponseSuccess.accept(connection.as(HttpClientOperations.class), connection);
			return;
		}
		if (afterRequest != null && newState == HttpClientState.REQUEST_SENT) {
			afterRequest.accept(connection.as(HttpClientOperations.class), connection);
			return;
		}
		if (onResponse != null && newState == HttpClientState.RESPONSE_RECEIVED) {
			onResponse.accept(connection.as(HttpClientOperations.class), connection);
		}
	}

	@Override
	public void onUncaughtException(Connection connection, Throwable error) {
		if (onRedirect != null && error instanceof RedirectClientException) {
			onRedirect.accept(connection.as(HttpClientOperations.class), connection);
		}
	}

	@Override
	protected TcpClient tcpConfiguration() {
		return source.tcpConfiguration().bootstrap(this);
	}

}
