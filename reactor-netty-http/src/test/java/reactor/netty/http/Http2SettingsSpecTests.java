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
package reactor.netty.http;

import io.netty.handler.codec.http2.Http2CodecUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class Http2SettingsSpecTests {

	private Http2SettingsSpec.Builder builder;

	@BeforeEach
	void setUp() {
		builder = Http2SettingsSpec.builder();
	}

	@Test
	void headerTableSize() {
		builder.headerTableSize(123);
		Http2SettingsSpec spec = builder.build();
		assertThat(spec.headerTableSize()).isEqualTo(123);
		assertThat(spec.initialWindowSize()).isNull();
		assertThat(spec.maxConcurrentStreams()).isNull();
		assertThat(spec.maxFrameSize()).isNull();
		assertThat(spec.maxHeaderListSize()).isEqualTo(Http2CodecUtil.DEFAULT_HEADER_LIST_SIZE);
		assertThat(spec.pushEnabled()).isNull();
	}

	@Test
	void headerTableSizeBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.headerTableSize(-1))
				.withMessage("Setting HEADER_TABLE_SIZE is invalid: -1");
	}

	@Test
	void initialWindowSize() {
		builder.initialWindowSize(123);
		Http2SettingsSpec spec = builder.build();
		assertThat(spec.headerTableSize()).isNull();
		assertThat(spec.initialWindowSize()).isEqualTo(123);
		assertThat(spec.maxConcurrentStreams()).isNull();
		assertThat(spec.maxFrameSize()).isNull();
		assertThat(spec.maxHeaderListSize()).isEqualTo(Http2CodecUtil.DEFAULT_HEADER_LIST_SIZE);
		assertThat(spec.pushEnabled()).isNull();
	}

	@Test
	void initialWindowSizeBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.initialWindowSize(-1))
				.withMessage("Setting INITIAL_WINDOW_SIZE is invalid: -1");
	}

	@Test
	void maxConcurrentStreams() {
		builder.maxConcurrentStreams(123);
		Http2SettingsSpec spec = builder.build();
		assertThat(spec.headerTableSize()).isNull();
		assertThat(spec.initialWindowSize()).isNull();
		assertThat(spec.maxConcurrentStreams()).isEqualTo(123);
		assertThat(spec.maxFrameSize()).isNull();
		assertThat(spec.maxHeaderListSize()).isEqualTo(Http2CodecUtil.DEFAULT_HEADER_LIST_SIZE);
		assertThat(spec.pushEnabled()).isNull();
	}

	@Test
	void maxConcurrentStreamsBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxConcurrentStreams(-1))
				.withMessage("Setting MAX_CONCURRENT_STREAMS is invalid: -1");
	}

	@Test
	void maxFrameSize() {
		builder.maxFrameSize(16384);
		Http2SettingsSpec spec = builder.build();
		assertThat(spec.headerTableSize()).isNull();
		assertThat(spec.initialWindowSize()).isNull();
		assertThat(spec.maxConcurrentStreams()).isNull();
		assertThat(spec.maxFrameSize()).isEqualTo(16384);
		assertThat(spec.maxHeaderListSize()).isEqualTo(Http2CodecUtil.DEFAULT_HEADER_LIST_SIZE);
		assertThat(spec.pushEnabled()).isNull();
	}

	@Test
	void maxFrameSizeBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxFrameSize(-1))
				.withMessage("Setting MAX_FRAME_SIZE is invalid: -1");
	}

	@Test
	void maxHeaderListSize() {
		builder.maxHeaderListSize(123);
		Http2SettingsSpec spec = builder.build();
		assertThat(spec.headerTableSize()).isNull();
		assertThat(spec.initialWindowSize()).isNull();
		assertThat(spec.maxConcurrentStreams()).isNull();
		assertThat(spec.maxFrameSize()).isNull();
		assertThat(spec.maxHeaderListSize()).isEqualTo(123);
		assertThat(spec.pushEnabled()).isNull();
	}

	@Test
	void maxHeaderListSizeBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxHeaderListSize(-1))
				.withMessage("Setting MAX_HEADER_LIST_SIZE is invalid: -1");
	}

	/*
	@Test
	public void pushEnabled() {
		builder.pushEnabled(true);
		Http2SettingsSpec spec = builder.build();
		assertThat(spec.headerTableSize()).isNull();
		assertThat(spec.initialWindowSize()).isNull();
		assertThat(spec.maxConcurrentStreams()).isNull();
		assertThat(spec.maxFrameSize()).isNull();
		assertThat(spec.maxHeaderListSize()).isEqualTo(Http2CodecUtil.DEFAULT_HEADER_LIST_SIZE);
		assertThat(spec.pushEnabled()).isTrue();
	}
	*/
}
