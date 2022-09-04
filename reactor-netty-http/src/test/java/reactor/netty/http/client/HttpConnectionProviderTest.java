/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
import static reactor.netty.http.client.HttpConnectionProvider.HTTP2_CONNECTION_PROVIDER_FACTORY;

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
					.isEqualTo(configuredConnectionProvider.getOrCreate().maxConnections());
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
				.isEqualTo(HttpResources.get().getOrCreateHttp2ConnectionProvider(HTTP2_CONNECTION_PROVIDER_FACTORY)
						.maxConnections());
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
					.isEqualTo(configuredConnectionProvider.getOrCreate().maxConnectionsPerHost());
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
				.isEqualTo(HttpResources.get().getOrCreateHttp2ConnectionProvider(HTTP2_CONNECTION_PROVIDER_FACTORY)
						.maxConnectionsPerHost());
	}

	@Test
	void configuredConnectionProviderShouldReturnTheOriginalConnectionProvider() {
		ConnectionProvider provider = ConnectionProvider.create("provider");
		try {
			HttpClient client = HttpClient.create(provider);
			HttpConnectionProvider configuredConnectionProvider =
					(HttpConnectionProvider) client.configuration().connectionProvider();
			assertThat(provider.getClass().getName())
					.isEqualTo(configuredConnectionProvider.http1ConnectionProvider.getClass().getName());
		}
		finally {
			provider.disposeLater()
					.block(Duration.ofSeconds(5));
		}
	}

	@Test
	void mergeConnectionProviderUsingMutate() {
		ConnectionProvider beforeProvider = ConnectionProvider
				.builder("beforeProvider")
				.maxConnections(1)
				.disposeTimeout(Duration.ofSeconds(1L))
				.pendingAcquireTimeout(Duration.ofSeconds(1L))
				.maxIdleTime(Duration.ofSeconds(1L))
				.maxLifeTime(Duration.ofSeconds(10L))
				.lifo()
				.build();
		HttpClient client1 = HttpClient.create(beforeProvider);

		ConnectionProvider mergedProvider = client1.configuration().connectionProvider()
				.mutate()
				.pendingAcquireTimeout(Duration.ofSeconds(2L))
				.maxIdleTime(Duration.ofSeconds(2L))
				.maxLifeTime(Duration.ofSeconds(20L))
				.build();

		assertThat(beforeProvider.maxConnections()).isEqualTo(mergedProvider.maxConnections());
		assertThat(beforeProvider.name()).isEqualTo(mergedProvider.name());

		beforeProvider.disposeLater().block(Duration.ofSeconds(5));
		mergedProvider.disposeLater().block(Duration.ofSeconds(5));
	}

	@Test
	void mergeDefaultConnectionProviderUsingMutate() {
		HttpClient client = HttpClient.create();

		ConnectionProvider mergedProvider = client.configuration().connectionProvider()
				.mutate()
				.name("mergedProvider")
				.pendingAcquireTimeout(Duration.ofSeconds(2L))
				.maxIdleTime(Duration.ofSeconds(2L))
				.maxLifeTime(Duration.ofSeconds(20L))
				.build();

		assertThat(mergedProvider.name()).isEqualTo("mergedProvider");

		mergedProvider.disposeLater().block(Duration.ofSeconds(5));
	}
}
