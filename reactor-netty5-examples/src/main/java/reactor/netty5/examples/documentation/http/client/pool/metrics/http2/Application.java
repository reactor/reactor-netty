/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.examples.documentation.http.client.pool.metrics.http2;

import reactor.netty5.http.HttpProtocol;
import reactor.netty5.http.client.HttpClient;
import reactor.netty5.http.client.HttpConnectionPoolMetrics;
import reactor.netty5.http.client.HttpMeterRegistrarAdapter;
import reactor.netty5.resources.ConnectionProvider;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;

public class Application {

	public static void main(String[] args) {
		AtomicReference<HttpConnectionPoolMetrics> metrics = new AtomicReference<>();

		ConnectionProvider provider =
				ConnectionProvider.builder("custom")
				                  .maxConnections(50)
				                  .metrics(true, () -> new CustomHttp2MeterRegistrar(metrics)) //<1>
				                  .build();

		HttpClient client = HttpClient.create(provider).protocol(HttpProtocol.H2);

		String response =
				client.get()
				      .uri("https://example.com/")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block();

		System.out.println("Response " + response);
		System.out.println("Active stream size " + metrics.get().activeStreamSize());
		System.out.println("Pending stream size " + metrics.get().pendingAcquireSize());

		provider.disposeLater()
		        .block();
	}

	static final class CustomHttp2MeterRegistrar extends HttpMeterRegistrarAdapter {
		AtomicReference<HttpConnectionPoolMetrics> metrics;

		CustomHttp2MeterRegistrar(AtomicReference<HttpConnectionPoolMetrics> metrics) {
			this.metrics = metrics;
		}

		@Override
		public void registerMetrics(String poolName, String id, SocketAddress remoteAddress, HttpConnectionPoolMetrics metrics) { //<2>
			this.metrics.set(metrics);
		}

		@Override
		public void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) { //<3>
			// no op
		}
	}
}