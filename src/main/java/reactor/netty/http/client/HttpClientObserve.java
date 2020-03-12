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
import java.util.function.Function;

import io.netty.bootstrap.Bootstrap;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class HttpClientObserve extends HttpClientOperator
		implements Function<Bootstrap, Bootstrap> {

	final ConnectionObserver observer;

	HttpClientObserve(HttpClient client, ConnectionObserver observer) {
		super(client);
		this.observer = Objects.requireNonNull(observer, "observer");
	}

	@Override
	protected TcpClient tcpConfiguration() {
		return source.tcpConfiguration().bootstrap(this);
	}

	@Override
	public Bootstrap apply(Bootstrap b) {
		ConnectionObserver observer = BootstrapHandlers.connectionObserver(b);
		BootstrapHandlers.connectionObserver(b, observer.then(this.observer));
		return b;
	}
}
