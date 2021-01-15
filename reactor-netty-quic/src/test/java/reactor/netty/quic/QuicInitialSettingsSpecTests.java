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
package reactor.netty.quic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static reactor.netty.quic.QuicInitialSettingsSpec.Build.DEFAULT_MAX_DATA;
import static reactor.netty.quic.QuicInitialSettingsSpec.Build.DEFAULT_MAX_STREAM_BIDIRECTIONAL;
import static reactor.netty.quic.QuicInitialSettingsSpec.Build.DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL;
import static reactor.netty.quic.QuicInitialSettingsSpec.Build.DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE;
import static reactor.netty.quic.QuicInitialSettingsSpec.Build.DEFAULT_MAX_STREAM_DATA_UNIDIRECTIONAL;
import static reactor.netty.quic.QuicInitialSettingsSpec.Build.DEFAULT_MAX_STREAM_UNIDIRECTIONAL;

/**
 * @author Violeta Georgieva
 */
class QuicInitialSettingsSpecTests {

	private QuicInitialSettingsSpec.Build builder;

	@BeforeEach
	void setUp() {
		builder = new QuicInitialSettingsSpec.Build();
	}

	@Test
	void maxData() {
		checkDefaultMaxData(builder);

		builder.maxData(2);

		assertThat(builder.maxData).isEqualTo(2);

		checkDefaultMaxStreamDataBidirectionalLocal(builder);
		checkDefaultMaxStreamDataBidirectionalRemote(builder);
		checkDefaultMaxStreamDataUnidirectional(builder);
		checkDefaultMaxStreamsBidirectional(builder);
		checkDefaultMaxStreamsUnidirectional(builder);
	}

	@Test
	void maxDataBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxData(-1))
				.withMessage("maxData must be positive or zero");
	}

	@Test
	void maxStreamDataBidirectionalLocal() {
		checkDefaultMaxStreamDataBidirectionalLocal(builder);

		builder.maxStreamDataBidirectionalLocal(10);

		assertThat(builder.maxStreamDataBidirectionalLocal).isEqualTo(10);

		checkDefaultMaxData(builder);
		checkDefaultMaxStreamDataBidirectionalRemote(builder);
		checkDefaultMaxStreamDataUnidirectional(builder);
		checkDefaultMaxStreamsBidirectional(builder);
		checkDefaultMaxStreamsUnidirectional(builder);
	}

	@Test
	void maxStreamDataBidirectionalLocalBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxStreamDataBidirectionalLocal(-1))
				.withMessage("maxStreamDataBidirectionalLocal must be positive or zero");
	}

	@Test
	void maxStreamDataBidirectionalRemote() {
		checkDefaultMaxStreamDataBidirectionalRemote(builder);

		builder.maxStreamDataBidirectionalRemote(10);

		assertThat(builder.maxStreamDataBidirectionalRemote).isEqualTo(10);

		checkDefaultMaxData(builder);
		checkDefaultMaxStreamDataBidirectionalLocal(builder);
		checkDefaultMaxStreamDataUnidirectional(builder);
		checkDefaultMaxStreamsBidirectional(builder);
		checkDefaultMaxStreamsUnidirectional(builder);
	}

	@Test
	void maxStreamDataBidirectionalRemoteBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxStreamDataBidirectionalRemote(-1))
				.withMessage("maxStreamDataBidirectionalRemote must be positive or zero");
	}

	@Test
	void maxStreamDataUnidirectional() {
		checkDefaultMaxStreamDataUnidirectional(builder);

		builder.maxStreamDataUnidirectional(10);

		assertThat(builder.maxStreamDataUnidirectional).isEqualTo(10);

		checkDefaultMaxData(builder);
		checkDefaultMaxStreamDataBidirectionalLocal(builder);
		checkDefaultMaxStreamDataBidirectionalRemote(builder);
		checkDefaultMaxStreamsBidirectional(builder);
		checkDefaultMaxStreamsUnidirectional(builder);
	}

	@Test
	void maxStreamDataUnidirectionalBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxStreamDataUnidirectional(-1))
				.withMessage("maxStreamDataUnidirectional must be positive or zero");
	}

	@Test
	void maxStreamsBidirectional() {
		checkDefaultMaxStreamsBidirectional(builder);

		builder.maxStreamsBidirectional(10);

		assertThat(builder.maxStreamsBidirectional).isEqualTo(10);

		checkDefaultMaxData(builder);
		checkDefaultMaxStreamDataBidirectionalLocal(builder);
		checkDefaultMaxStreamDataBidirectionalRemote(builder);
		checkDefaultMaxStreamDataUnidirectional(builder);
		checkDefaultMaxStreamsUnidirectional(builder);
	}

	@Test
	void maxStreamsBidirectionalBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxStreamsBidirectional(-1))
				.withMessage("maxStreamsBidirectional must be positive or zero");
	}

	@Test
	void maxStreamsUnidirectional() {
		checkDefaultMaxStreamsUnidirectional(builder);

		builder.maxStreamsUnidirectional(10);

		assertThat(builder.maxStreamsUnidirectional).isEqualTo(10);

		checkDefaultMaxData(builder);
		checkDefaultMaxStreamDataBidirectionalLocal(builder);
		checkDefaultMaxStreamDataBidirectionalRemote(builder);
		checkDefaultMaxStreamDataUnidirectional(builder);
		checkDefaultMaxStreamsBidirectional(builder);
	}

	@Test
	void maxStreamsUnidirectionalBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxStreamsUnidirectional(-1))
				.withMessage("maxStreamsUnidirectional must be positive or zero");
	}

	private static void checkDefaultMaxData(QuicInitialSettingsSpec.Build builder) {
		assertThat(builder.maxData).as("default maxData")
				.isEqualTo(DEFAULT_MAX_DATA)
				.isEqualTo(0);
	}

	private static void checkDefaultMaxStreamDataBidirectionalLocal(QuicInitialSettingsSpec.Build builder) {
		assertThat(builder.maxStreamDataBidirectionalLocal).as("default maxStreamDataBidirectionalLocal")
				.isEqualTo(DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL)
				.isEqualTo(0);
	}

	private static void checkDefaultMaxStreamDataBidirectionalRemote(QuicInitialSettingsSpec.Build builder) {
		assertThat(builder.maxStreamDataBidirectionalRemote).as("default maxStreamDataBidirectionalRemote")
				.isEqualTo(DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE)
				.isEqualTo(0);
	}

	private static void checkDefaultMaxStreamDataUnidirectional(QuicInitialSettingsSpec.Build builder) {
		assertThat(builder.maxStreamDataUnidirectional).as("default maxStreamDataUnidirectional")
				.isEqualTo(DEFAULT_MAX_STREAM_DATA_UNIDIRECTIONAL)
				.isEqualTo(0);
	}

	private static void checkDefaultMaxStreamsBidirectional(QuicInitialSettingsSpec.Build builder) {
		assertThat(builder.maxStreamsBidirectional).as("default maxStreamsBidirectional")
				.isEqualTo(DEFAULT_MAX_STREAM_BIDIRECTIONAL)
				.isEqualTo(0);
	}

	private static void checkDefaultMaxStreamsUnidirectional(QuicInitialSettingsSpec.Build builder) {
		assertThat(builder.maxStreamsUnidirectional).as("default maxStreamsUnidirectional")
				.isEqualTo(DEFAULT_MAX_STREAM_UNIDIRECTIONAL)
				.isEqualTo(0);
	}
}
