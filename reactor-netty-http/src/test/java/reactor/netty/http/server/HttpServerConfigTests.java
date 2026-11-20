/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import org.junit.jupiter.api.Test;
import reactor.netty.http.Http2SettingsSpec;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class HttpServerConfigTests {

	@Test
	void http2SettingsEvaluatesMaxConcurrentStreamsSupplierForEachNewConnection() {
		AtomicLong maxConcurrentStreams = new AtomicLong(10);
		Http2SettingsSpec http2SettingsSpec = Http2SettingsSpec.builder()
				.maxConcurrentStreamsSupplier(maxConcurrentStreams::get)
				.build();

		assertThat(HttpServerConfig.http2Settings(http2SettingsSpec).maxConcurrentStreams()).isEqualTo(10);

		maxConcurrentStreams.set(20);
		assertThat(HttpServerConfig.http2Settings(http2SettingsSpec).maxConcurrentStreams()).isEqualTo(20);
	}

	@Test
	void http2SettingsCapsSuppliedMaxConcurrentStreamsWithMaxStreams() {
		AtomicLong maxConcurrentStreams = new AtomicLong(20);
		Http2SettingsSpec http2SettingsSpec = Http2SettingsSpec.builder()
				.maxConcurrentStreamsSupplier(maxConcurrentStreams::get)
				.maxStreams(10)
				.build();

		assertThat(HttpServerConfig.http2Settings(http2SettingsSpec).maxConcurrentStreams()).isEqualTo(10);

		maxConcurrentStreams.set(5);
		assertThat(HttpServerConfig.http2Settings(http2SettingsSpec).maxConcurrentStreams()).isEqualTo(5);
	}
}
