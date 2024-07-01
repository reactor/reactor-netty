/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class Http3SettingsSpecTest {

	private Http3SettingsSpec.Builder builder;

	@BeforeEach
	void setUp() {
		builder = Http3SettingsSpec.builder();
	}

	@Test
	void idleTimeout() {
		builder.idleTimeout(Duration.ofMillis(50));
		Http3SettingsSpec spec = builder.build();
		assertThat(spec.idleTimeout()).isEqualTo(Duration.ofMillis(50));
		assertThat(spec.maxData()).isEqualTo(0);
		assertThat(spec.maxStreamDataBidirectionalLocal()).isEqualTo(0);
		assertThat(spec.maxStreamDataBidirectionalRemote()).isEqualTo(0);
		assertThat(spec.maxStreamsBidirectional()).isEqualTo(0);
		assertThat(spec.tokenHandler()).isNull();
	}

	@Test
	void maxData() {
		builder.maxData(123);
		Http3SettingsSpec spec = builder.build();
		assertThat(spec.idleTimeout()).isNull();
		assertThat(spec.maxData()).isEqualTo(123);
		assertThat(spec.maxStreamDataBidirectionalLocal()).isEqualTo(0);
		assertThat(spec.maxStreamDataBidirectionalRemote()).isEqualTo(0);
		assertThat(spec.maxStreamsBidirectional()).isEqualTo(0);
		assertThat(spec.tokenHandler()).isNull();
	}

	@Test
	void maxDataBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxData(-1))
				.withMessageContaining("maxData must be positive or zero");
	}

	@Test
	void maxStreamDataBidirectionalLocal() {
		builder.maxStreamDataBidirectionalLocal(123);
		Http3SettingsSpec spec = builder.build();
		assertThat(spec.idleTimeout()).isNull();
		assertThat(spec.maxData()).isEqualTo(0);
		assertThat(spec.maxStreamDataBidirectionalLocal()).isEqualTo(123);
		assertThat(spec.maxStreamDataBidirectionalRemote()).isEqualTo(0);
		assertThat(spec.maxStreamsBidirectional()).isEqualTo(0);
		assertThat(spec.tokenHandler()).isNull();
	}

	@Test
	void maxStreamDataBidirectionalLocalBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxStreamDataBidirectionalLocal(-1))
				.withMessageContaining("maxStreamDataBidirectionalLocal must be positive or zero");
	}

	@Test
	void maxStreamDataBidirectionalRemote() {
		builder.maxStreamDataBidirectionalRemote(123);
		Http3SettingsSpec spec = builder.build();
		assertThat(spec.idleTimeout()).isNull();
		assertThat(spec.maxData()).isEqualTo(0);
		assertThat(spec.maxStreamDataBidirectionalLocal()).isEqualTo(0);
		assertThat(spec.maxStreamDataBidirectionalRemote()).isEqualTo(123);
		assertThat(spec.maxStreamsBidirectional()).isEqualTo(0);
		assertThat(spec.tokenHandler()).isNull();
	}

	@Test
	void maxStreamDataBidirectionalRemoteBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxStreamDataBidirectionalRemote(-1))
				.withMessageContaining("maxStreamDataBidirectionalRemote must be positive or zero");
	}

	@Test
	void maxStreamsBidirectional() {
		builder.maxStreamsBidirectional(123);
		Http3SettingsSpec spec = builder.build();
		assertThat(spec.idleTimeout()).isNull();
		assertThat(spec.maxData()).isEqualTo(0);
		assertThat(spec.maxStreamDataBidirectionalLocal()).isEqualTo(0);
		assertThat(spec.maxStreamDataBidirectionalRemote()).isEqualTo(0);
		assertThat(spec.maxStreamsBidirectional()).isEqualTo(123);
		assertThat(spec.tokenHandler()).isNull();
	}

	@Test
	void maxStreamsBidirectionalBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxStreamsBidirectional(-1))
				.withMessageContaining("maxStreamsBidirectional must be positive or zero");
	}

	@Test
	void tokenHandler() {
		builder.tokenHandler(InsecureQuicTokenHandler.INSTANCE);
		Http3SettingsSpec spec = builder.build();
		assertThat(spec.idleTimeout()).isNull();
		assertThat(spec.maxData()).isEqualTo(0);
		assertThat(spec.maxStreamDataBidirectionalLocal()).isEqualTo(0);
		assertThat(spec.maxStreamDataBidirectionalRemote()).isEqualTo(0);
		assertThat(spec.maxStreamsBidirectional()).isEqualTo(0);
		assertThat(spec.tokenHandler()).isEqualTo(InsecureQuicTokenHandler.INSTANCE);
	}
}
