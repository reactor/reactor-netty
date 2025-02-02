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

import java.time.Duration;
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

		/**
		 * Sets the interval for checking ping frames.
		 * If a ping ACK frame is not received within the configured interval, the connection will be closed.
		 *
		 * <p>Be cautious when setting a very short interval, as it may cause the connection to be closed,
		 * even if the keep-alive setting is enabled.</p>
		 *
		 * <p>If no interval is specified, no ping frame checking will be performed by default.</p>
		 *
		 * @param pingInterval the duration between sending ping frames. If not specified, ping frame checking is disabled.
		 * @return {@code this}
		 * @since 1.2.3
		 */
		Builder pingInterval(Duration pingInterval);
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

	/**
	 * Returns the configured {@code pingInterval} value or null.
	 *
	 * @return the configured {@code pingInterval} value or null
	 */
	@Nullable
	public Duration pingInterval() {
		return pingInterval;
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
				Objects.equals(maxFrameSize, that.maxFrameSize) &&
				maxHeaderListSize.equals(that.maxHeaderListSize) &&
				Objects.equals(maxStreams, that.maxStreams) &&
				Objects.equals(pushEnabled, that.pushEnabled) &&
				Objects.equals(pingInterval, that.pingInterval);
	}

	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + (connectProtocolEnabled == null ? 0 : Boolean.hashCode(connectProtocolEnabled));
		result = 31 * result + (headerTableSize == null ? 0 : Long.hashCode(headerTableSize));
		result = 31 * result + (initialWindowSize == null ? 0 : initialWindowSize);
		result = 31 * result + (maxConcurrentStreams == null ? 0 : Long.hashCode(maxConcurrentStreams));
		result = 31 * result + (maxFrameSize == null ? 0 : maxFrameSize);
		result = 31 * result + (maxHeaderListSize == null ? 0 : Long.hashCode(maxHeaderListSize));
		result = 31 * result + (maxStreams == null ? 0 : Long.hashCode(maxStreams));
		result = 31 * result + (pushEnabled == null ? 0 : Boolean.hashCode(pushEnabled));
		result = 31 * result + (pingInterval == null ? 0 : Objects.hashCode(pingInterval));
		return result;
	}

	final Boolean connectProtocolEnabled;
	final Long headerTableSize;
	final Integer initialWindowSize;
	final Long maxConcurrentStreams;
	final Integer maxFrameSize;
	final Long maxHeaderListSize;
	final Long maxStreams;
	final Boolean pushEnabled;
	final Duration pingInterval;

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
		maxFrameSize = settings.maxFrameSize();
		maxHeaderListSize = settings.maxHeaderListSize();
		maxStreams = build.maxStreams;
		pushEnabled = settings.pushEnabled();
		pingInterval = build.pingInterval;
	}

	static final class Build implements Builder {
		Boolean connectProtocolEnabled;
		Long maxStreams;
		Duration pingInterval;
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

		@Override
		public Builder pingInterval(Duration pingInterval) {
			this.pingInterval = pingInterval;
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
