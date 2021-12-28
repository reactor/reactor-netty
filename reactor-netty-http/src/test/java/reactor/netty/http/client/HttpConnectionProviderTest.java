/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import org.junit.jupiter.api.Test;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Violeta Georgieva
 */
class HttpConnectionProviderTest {

	@Test
	void maxConnectionsCustomConnectionProvider() {
		ConnectionProvider provider = ConnectionProvider.create("maxConnectionsCustomConnectionProvider", 1);
		try {
			HttpClient client = HttpClient.create(provider);
			HttpConnectionProvider configuredConnectionProvider =
					(HttpConnectionProvider) client.configuration().connectionProvider();
			assertThat(configuredConnectionProvider.maxConnections())
					.isEqualTo(provider.maxConnections());
			// Http2ConnectionProvider inherits the configuration from the configured provider
			assertThat(configuredConnectionProvider.maxConnections())
					.isEqualTo(configuredConnectionProvider.getOrCreate(provider).maxConnections());
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void maxConnectionsDefaultConnectionProvider() {
		HttpClient client = HttpClient.create();
		HttpConnectionProvider configuredConnectionProvider =
				(HttpConnectionProvider) client.configuration().connectionProvider();
		assertThat(configuredConnectionProvider.maxConnections())
				.isEqualTo(((ConnectionProvider) HttpResources.get()).maxConnections());
		// Http2ConnectionProvider inherits the configuration from HttpResources
		assertThat(configuredConnectionProvider.maxConnections())
				.isEqualTo(configuredConnectionProvider.getOrCreate(HttpResources.get()).maxConnections());
	}

	@Test
	void maxConnectionsPerHostCustomConnectionProvider() {
		ConnectionProvider provider = ConnectionProvider.create("maxConnectionsPerHostCustomConnectionProvider", 1);
		try {
			HttpClient client = HttpClient.create(provider);
			HttpConnectionProvider configuredConnectionProvider =
					(HttpConnectionProvider) client.configuration().connectionProvider();
			assertThat(configuredConnectionProvider.maxConnectionsPerHost())
					.isEqualTo(provider.maxConnectionsPerHost());
			// Http2ConnectionProvider inherits the configuration from the configured provider
			assertThat(configuredConnectionProvider.maxConnectionsPerHost())
					.isEqualTo(configuredConnectionProvider.getOrCreate(provider).maxConnectionsPerHost());
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void maxConnectionsPerHostDefaultConnectionProvider() {
		HttpClient client = HttpClient.create();
		HttpConnectionProvider configuredConnectionProvider =
				(HttpConnectionProvider) client.configuration().connectionProvider();
		assertThat(configuredConnectionProvider.maxConnectionsPerHost())
				.isEqualTo(((ConnectionProvider) HttpResources.get()).maxConnectionsPerHost());
		// Http2ConnectionProvider inherits the configuration from HttpResources
		assertThat(configuredConnectionProvider.maxConnectionsPerHost())
				.isEqualTo(configuredConnectionProvider.getOrCreate(HttpResources.get()).maxConnectionsPerHost());
	}
}
