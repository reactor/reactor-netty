/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
import reactor.util.annotation.Nullable;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.client.HttpConnectionProvider.HTTP2_CONNECTION_PROVIDER_FACTORY;

/**
 * This test class verifies {@link HttpConnectionProvider}.
 *
 * @author Violeta Georgieva
 */
class HttpConnectionProviderTest {

	@Test
	void maxConnectionsCustomConnectionProvider() {
		ConnectionProvider provider = ConnectionProvider.create("maxConnectionsCustomConnectionProvider", 1);
		try {
			HttpClient client = HttpClient.create(provider);
			HttpConnectionProvider configuredConnectionProvider = client.configuration().httpConnectionProvider();
			assertThat(configuredConnectionProvider.maxConnections())
					.isEqualTo(provider.maxConnections());
			// Http2ConnectionProvider inherits the configuration from the configured provider
			assertThat(configuredConnectionProvider.maxConnections())
					.isEqualTo(configuredConnectionProvider.getOrCreateHttp2().maxConnections());
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void maxConnectionsDefaultConnectionProvider() {
		HttpClient client = HttpClient.create();
		HttpConnectionProvider configuredConnectionProvider = client.configuration().httpConnectionProvider();
		assertThat(configuredConnectionProvider.maxConnections())
				.isEqualTo(((ConnectionProvider) HttpResources.get()).maxConnections());
		// Http2ConnectionProvider inherits the configuration from HttpResources
		assertThat(configuredConnectionProvider.maxConnections())
				.isEqualTo(HttpResources.get().getOrCreateHttp2ConnectionProvider(HTTP2_CONNECTION_PROVIDER_FACTORY)
						.maxConnections());
	}

	@Test
	void maxConnectionsPerHostCustomConnectionProvider() {
		ConnectionProvider provider = ConnectionProvider.create("maxConnectionsPerHostCustomConnectionProvider", 1);
		try {
			HttpClient client = HttpClient.create(provider);
			HttpConnectionProvider configuredConnectionProvider = client.configuration().httpConnectionProvider();
			assertThat(configuredConnectionProvider.maxConnectionsPerHost())
					.isEqualTo(provider.maxConnectionsPerHost());
			// Http2ConnectionProvider inherits the configuration from the configured provider
			assertThat(configuredConnectionProvider.maxConnectionsPerHost())
					.isEqualTo(configuredConnectionProvider.getOrCreateHttp2().maxConnectionsPerHost());
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void maxConnectionsPerHostDefaultConnectionProvider() {
		HttpClient client = HttpClient.create();
		HttpConnectionProvider configuredConnectionProvider = client.configuration().httpConnectionProvider();
		assertThat(configuredConnectionProvider.maxConnectionsPerHost())
				.isEqualTo(((ConnectionProvider) HttpResources.get()).maxConnectionsPerHost());
		// Http2ConnectionProvider inherits the configuration from HttpResources
		assertThat(configuredConnectionProvider.maxConnectionsPerHost())
				.isEqualTo(HttpResources.get().getOrCreateHttp2ConnectionProvider(HTTP2_CONNECTION_PROVIDER_FACTORY)
						.maxConnectionsPerHost());
	}

	@Test
	void returnOriginalConnectionProvider() {
		testReturnOriginalConnectionProvider(HttpClient.create(), null);
	}

	@Test
	void returnOriginalConnectionProviderNewConnection() {
		ConnectionProvider provider = ConnectionProvider.newConnection();
		testReturnOriginalConnectionProvider(HttpClient.create(provider), provider);
	}

	@Test
	void returnOriginalConnectionProviderUsingBuilder() {
		ConnectionProvider provider =
				ConnectionProvider.builder("provider")
				                  .maxConnections(1)
				                  .disposeTimeout(Duration.ofSeconds(1L))
				                  .pendingAcquireTimeout(Duration.ofSeconds(1L))
				                  .maxIdleTime(Duration.ofSeconds(1L))
				                  .maxLifeTime(Duration.ofSeconds(10L))
				                  .lifo()
				                  .build();

		testReturnOriginalConnectionProvider(HttpClient.create(provider), provider);
	}

	private void testReturnOriginalConnectionProvider(HttpClient httpClient, @Nullable ConnectionProvider originalProvider) {
		ConnectionProvider provider = httpClient.configuration().connectionProvider();
		try {
			assertThat(provider).isSameAs(originalProvider != null ? originalProvider : HttpResources.get());
		}
		finally {
			if (originalProvider != null) {
				provider.disposeLater()
				        .block(Duration.ofSeconds(5));
			}
		}
	}
}
