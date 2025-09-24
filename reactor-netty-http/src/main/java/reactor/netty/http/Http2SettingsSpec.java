/*
 * Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Settings;
import reactor.util.annotation.Nullable;

import java.util.Objects;

/**
 * A configuration builder to fine tune the {@link Http2Settings}.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class Http2SettingsSpec {

	public interface Builder {

		/**
		 * Build a new {@link Http2SettingsSpec}.
		 *
		 * @return a new {@link Http2SettingsSpec}
		 */
		Http2SettingsSpec build();

		/**
		 * Sets the {@code SETTINGS_ENABLE_CONNECT_PROTOCOL} value.
		 *
		 * @param connectProtocolEnabled the {@code SETTINGS_ENABLE_CONNECT_PROTOCOL} value
		 * @return {@code this}
		 * @since 1.2.5
		 */
		Builder connectProtocolEnabled(boolean connectProtocolEnabled);

		/**
		 * Sets the {@code SETTINGS_HEADER_TABLE_SIZE} value.
		 *
		 * @param headerTableSize the {@code SETTINGS_HEADER_TABLE_SIZE} value
		 * @return {@code this}
		 */
		Builder headerTableSize(long headerTableSize);

		/**
		 * Sets the {@code SETTINGS_INITIAL_WINDOW_SIZE} value.
		 *
		 * @param initialWindowSize the {@code SETTINGS_INITIAL_WINDOW_SIZE} value
		 * @return {@code this}
		 */
		Builder initialWindowSize(int initialWindowSize);

		/**
		 * Sets the {@code SETTINGS_MAX_CONCURRENT_STREAMS} value.
		 *
		 * @param maxConcurrentStreams the {@code SETTINGS_MAX_CONCURRENT_STREAMS} value
		 * @return {@code this}
		 */
		Builder maxConcurrentStreams(long maxConcurrentStreams);

		/**
		 * Sets the maximum number RST frames that are allowed per window before the connection is closed.
		 * This allows to protect against the remote peer flooding us with such frames and so use up a lot of CPU.
		 * {@code 0} for any of the parameters means no protection should be applied.
		 * For server, the default {code maxDecodedRstFramesPerWindow} is {@code 200} and for the client it is {@code 0}.
		 * The default {code maxDecodedRstFramesSecondsPerWindow} is {@code 30}.
		 *
		 * @param maxDecodedRstFramesPerWindow the maximum number RST frames that are allowed per window
		 * @param maxDecodedRstFramesSecondsPerWindow the maximum seconds per window
		 * @return {@code this}
		 * @since 1.2.11
		 */
		Builder maxDecodedRstFramesPerWindow(int maxDecodedRstFramesPerWindow, int maxDecodedRstFramesSecondsPerWindow);

		/**
		 * Sets the maximum number RST frames that are allowed per window before the connection is closed.
		 * This allows to protect against the remote peer that will trigger us to generate a flood
		 * of RST frames and so use up a lot of CPU.
		 * {@code 0} for any of the parameters means no protection should be applied.
		 * For server, the default {code maxEncodedRstFramesPerWindow} is {@code 200} and for the client - is {@code 0}.
		 * The default {code maxEncodedRstFramesSecondsPerWindow} is {@code 30}.
		 *
		 * @param maxEncodedRstFramesPerWindow the maximum number RST frames that are allowed per window
		 * @param maxEncodedRstFramesSecondsPerWindow the maximum seconds per window
		 * @return {@code this}
		 * @since 1.2.11
		 */
		Builder maxEncodedRstFramesPerWindow(int maxEncodedRstFramesPerWindow, int maxEncodedRstFramesSecondsPerWindow);

		/**
		 * Sets the {@code SETTINGS_MAX_FRAME_SIZE} value.
		 *
		 * @param maxFrameSize the {@code SETTINGS_MAX_FRAME_SIZE} value
		 * @return {@code this}
		 */
		Builder maxFrameSize(int maxFrameSize);

		/**
		 * Sets the {@code SETTINGS_MAX_HEADER_LIST_SIZE} value.
		 *
		 * @param maxHeaderListSize the {@code SETTINGS_MAX_HEADER_LIST_SIZE} value
		 * @return {@code this}
		 */
		Builder maxHeaderListSize(long maxHeaderListSize);

		/**
		 * The connection is marked for closing once the number of all-time streams reaches {@code maxStreams}.
		 *
		 * @return {@code this}
		 * @since 1.0.33
		 */
		Builder maxStreams(long maxStreams);

		/**
		 * Sets the {@code SETTINGS_ENABLE_PUSH} value.
		 *
		 * @param pushEnabled the {@code SETTINGS_ENABLE_PUSH} value
		 * @return {@code this}
		 */
		//Builder pushEnabled(boolean pushEnabled);
	}

	/**
	 * Creates a builder for {@link Http2SettingsSpec}.
	 *
	 * @return a new {@link Http2SettingsSpec.Builder}
	 */
	public static Builder builder() {
		return new Build();
	}

	/**
	 * Returns the configured {@code SETTINGS_ENABLE_CONNECT_PROTOCOL} value or null.
	 *
	 * @return the configured {@code SETTINGS_ENABLE_CONNECT_PROTOCOL} value or null
	 * @since 1.2.5
	 */
	@Nullable
	public Boolean connectProtocolEnabled() {
		return connectProtocolEnabled;
	}

	/**
	 * Returns the configured {@code SETTINGS_HEADER_TABLE_SIZE} value or null.
	 *
	 * @return the configured {@code SETTINGS_HEADER_TABLE_SIZE} value or null
	 */
	@Nullable
	public Long headerTableSize() {
		return headerTableSize;
	}

	/**
	 * Returns the configured {@code SETTINGS_INITIAL_WINDOW_SIZE} value or null.
	 *
	 * @return the configured {@code SETTINGS_INITIAL_WINDOW_SIZE} value or null
	 */
	@Nullable
	public Integer initialWindowSize() {
		return initialWindowSize;
	}

	/**
	 * Returns the configured {@code SETTINGS_MAX_CONCURRENT_STREAMS} value or null.
	 *
	 * @return the configured {@code SETTINGS_MAX_CONCURRENT_STREAMS} value or null
	 */
	@Nullable
	public Long maxConcurrentStreams() {
		return maxConcurrentStreams;
	}

	/**
	 * Returns the configured maximum number RST frames that are allowed per window or null.
	 *
	 * @return the configured maximum number RST frames that are allowed per window or null
	 * @since 1.2.11
	 */
	@Nullable
	public Integer maxDecodedRstFramesPerWindow() {
		return maxDecodedRstFramesPerWindow;
	}

	/**
	 * Returns the configured maximum seconds per window or null.
	 *
	 * @return the configured maximum seconds per window or null
	 * @since 1.2.11
	 */
	@Nullable
	public Integer maxDecodedRstFramesSecondsPerWindow() {
		return maxDecodedRstFramesSecondsPerWindow;
	}

	/**
	 * Returns the configured maximum number RST frames that are allowed per window or null.
	 *
	 * @return the configured maximum number RST frames that are allowed per window or null
	 * @since 1.2.11
	 */
	@Nullable
	public Integer maxEncodedRstFramesPerWindow() {
		return maxEncodedRstFramesPerWindow;
	}

	/**
	 * Returns the configured maximum seconds per window or null.
	 *
	 * @return the configured maximum seconds per window or null
	 * @since 1.2.11
	 */
	@Nullable
	public Integer maxEncodedRstFramesSecondsPerWindow() {
		return maxEncodedRstFramesSecondsPerWindow;
	}

	/**
	 * Returns the configured {@code SETTINGS_MAX_FRAME_SIZE} value or null.
	 *
	 * @return the configured {@code SETTINGS_MAX_FRAME_SIZE} value or null
	 */
	@Nullable
	public Integer maxFrameSize() {
		return maxFrameSize;
	}

	/**
	 * Returns the configured {@code SETTINGS_MAX_HEADER_LIST_SIZE} value or
	 * the default {@link Http2CodecUtil#DEFAULT_HEADER_LIST_SIZE}.
	 *
	 * @return the configured {@code SETTINGS_MAX_HEADER_LIST_SIZE} value or
	 * the default {@link Http2CodecUtil#DEFAULT_HEADER_LIST_SIZE}.
	 */
	public Long maxHeaderListSize() {
		return maxHeaderListSize;
	}

	/**
	 * Returns the configured {@code maxStreams} value or null.
	 *
	 * @return the configured {@code maxStreams} value or null
	 * @since 1.0.33
	 */
	@Nullable
	public Long maxStreams() {
		return maxStreams;
	}

	/**
	 * Returns the configured {@code SETTINGS_ENABLE_PUSH} value or null.
	 *
	 * @return the configured {@code SETTINGS_ENABLE_PUSH} value or null
	 */
	@Nullable
	public Boolean pushEnabled() {
		return pushEnabled;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Http2SettingsSpec)) {
			return false;
		}
		Http2SettingsSpec that = (Http2SettingsSpec) o;
		return Objects.equals(connectProtocolEnabled, that.connectProtocolEnabled) &&
				Objects.equals(headerTableSize, that.headerTableSize) &&
				Objects.equals(initialWindowSize, that.initialWindowSize) &&
				Objects.equals(maxConcurrentStreams, that.maxConcurrentStreams) &&
				Objects.equals(maxDecodedRstFramesPerWindow, that.maxDecodedRstFramesPerWindow) &&
				Objects.equals(maxDecodedRstFramesSecondsPerWindow, that.maxDecodedRstFramesSecondsPerWindow) &&
				Objects.equals(maxEncodedRstFramesPerWindow, that.maxEncodedRstFramesPerWindow) &&
				Objects.equals(maxEncodedRstFramesSecondsPerWindow, that.maxEncodedRstFramesSecondsPerWindow) &&
				Objects.equals(maxFrameSize, that.maxFrameSize) &&
				maxHeaderListSize.equals(that.maxHeaderListSize) &&
				Objects.equals(maxStreams, that.maxStreams) &&
				Objects.equals(pushEnabled, that.pushEnabled);
	}

	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + (connectProtocolEnabled == null ? 0 : Boolean.hashCode(connectProtocolEnabled));
		result = 31 * result + (headerTableSize == null ? 0 : Long.hashCode(headerTableSize));
		result = 31 * result + (initialWindowSize == null ? 0 : initialWindowSize);
		result = 31 * result + (maxConcurrentStreams == null ? 0 : Long.hashCode(maxConcurrentStreams));
		result = 31 * result + (maxDecodedRstFramesPerWindow == null ? 0 : maxDecodedRstFramesPerWindow);
		result = 31 * result + (maxDecodedRstFramesSecondsPerWindow == null ? 0 : maxDecodedRstFramesSecondsPerWindow);
		result = 31 * result + (maxEncodedRstFramesPerWindow == null ? 0 : maxEncodedRstFramesPerWindow);
		result = 31 * result + (maxEncodedRstFramesSecondsPerWindow == null ? 0 : maxEncodedRstFramesSecondsPerWindow);
		result = 31 * result + (maxFrameSize == null ? 0 : maxFrameSize);
		result = 31 * result + (maxHeaderListSize == null ? 0 : Long.hashCode(maxHeaderListSize));
		result = 31 * result + (maxStreams == null ? 0 : Long.hashCode(maxStreams));
		result = 31 * result + (pushEnabled == null ? 0 : Boolean.hashCode(pushEnabled));
		return result;
	}

	final Boolean connectProtocolEnabled;
	final Long headerTableSize;
	final Integer initialWindowSize;
	final Long maxConcurrentStreams;
	final Integer maxDecodedRstFramesPerWindow;
	final Integer maxDecodedRstFramesSecondsPerWindow;
	final Integer maxEncodedRstFramesPerWindow;
	final Integer maxEncodedRstFramesSecondsPerWindow;
	final Integer maxFrameSize;
	final Long maxHeaderListSize;
	final Long maxStreams;
	final Boolean pushEnabled;

	Http2SettingsSpec(Build build) {
		Http2Settings settings = build.http2Settings;
		connectProtocolEnabled = build.connectProtocolEnabled;
		headerTableSize = settings.headerTableSize();
		initialWindowSize = settings.initialWindowSize();
		if (settings.maxConcurrentStreams() != null) {
			maxConcurrentStreams = build.maxStreams != null ?
					Math.min(settings.maxConcurrentStreams(), build.maxStreams) : settings.maxConcurrentStreams();
		}
		else {
			maxConcurrentStreams = build.maxStreams;
		}
		maxDecodedRstFramesPerWindow = build.maxDecodedRstFramesPerWindow;
		maxDecodedRstFramesSecondsPerWindow = build.maxDecodedRstFramesSecondsPerWindow;
		maxEncodedRstFramesPerWindow = build.maxEncodedRstFramesPerWindow;
		maxEncodedRstFramesSecondsPerWindow = build.maxEncodedRstFramesSecondsPerWindow;
		maxFrameSize = settings.maxFrameSize();
		maxHeaderListSize = settings.maxHeaderListSize();
		maxStreams = build.maxStreams;
		pushEnabled = settings.pushEnabled();
	}

	static final class Build implements Builder {
		Boolean connectProtocolEnabled;
		Integer maxDecodedRstFramesPerWindow;
		Integer maxDecodedRstFramesSecondsPerWindow;
		Integer maxEncodedRstFramesPerWindow;
		Integer maxEncodedRstFramesSecondsPerWindow;
		Long maxStreams;
		final Http2Settings http2Settings = Http2Settings.defaultSettings();

		@Override
		public Http2SettingsSpec build() {
			return new Http2SettingsSpec(this);
		}

		@Override
		public Builder connectProtocolEnabled(boolean connectProtocolEnabled) {
			this.connectProtocolEnabled = Boolean.valueOf(connectProtocolEnabled);
			return this;
		}

		@Override
		public Builder headerTableSize(long headerTableSize) {
			http2Settings.headerTableSize(headerTableSize);
			return this;
		}

		@Override
		public Builder initialWindowSize(int initialWindowSize) {
			http2Settings.initialWindowSize(initialWindowSize);
			return this;
		}

		@Override
		public Builder maxConcurrentStreams(long maxConcurrentStreams) {
			http2Settings.maxConcurrentStreams(maxConcurrentStreams);
			return this;
		}

		@Override
		public Builder maxDecodedRstFramesPerWindow(int maxDecodedRstFramesPerWindow, int maxDecodedRstFramesSecondsPerWindow) {
			if (maxDecodedRstFramesPerWindow < 0) {
				throw new IllegalArgumentException("maxDecodedRstFramesPerWindow must be positive or zero");
			}
			if (maxDecodedRstFramesSecondsPerWindow < 0) {
				throw new IllegalArgumentException("maxDecodedRstFramesSecondsPerWindow must be positive or zero");
			}
			this.maxDecodedRstFramesPerWindow = Integer.valueOf(maxDecodedRstFramesPerWindow);
			this.maxDecodedRstFramesSecondsPerWindow = Integer.valueOf(maxDecodedRstFramesSecondsPerWindow);
			return this;
		}

		@Override
		public Builder maxEncodedRstFramesPerWindow(int maxEncodedRstFramesPerWindow, int maxEncodedRstFramesSecondsPerWindow) {
			if (maxEncodedRstFramesPerWindow < 0) {
				throw new IllegalArgumentException("maxEncodedRstFramesPerWindow must be positive or zero");
			}
			if (maxEncodedRstFramesSecondsPerWindow < 0) {
				throw new IllegalArgumentException("maxEncodedRstFramesSecondsPerWindow must be positive or zero");
			}
			this.maxEncodedRstFramesPerWindow = Integer.valueOf(maxEncodedRstFramesPerWindow);
			this.maxEncodedRstFramesSecondsPerWindow = Integer.valueOf(maxEncodedRstFramesSecondsPerWindow);
			return this;
		}

		@Override
		public Builder maxFrameSize(int maxFrameSize) {
			http2Settings.maxFrameSize(maxFrameSize);
			return this;
		}

		@Override
		public Builder maxHeaderListSize(long maxHeaderListSize) {
			http2Settings.maxHeaderListSize(maxHeaderListSize);
			return this;
		}

		@Override
		public Builder maxStreams(long maxStreams) {
			if (maxStreams < 1) {
				throw new IllegalArgumentException("maxStreams must be positive");
			}
			this.maxStreams = Long.valueOf(maxStreams);
			return this;
		}

		/*
		@Override
		public Builder pushEnabled(boolean pushEnabled) {
			http2Settings.pushEnabled(pushEnabled);
			return this;
		}
		*/
	}
}
