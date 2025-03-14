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
		 * Sets the interval for sending HTTP/2 PING frames and receiving ACK responses.
		 *
		 * <p>
		 * This method configures the time interval at which PING frames are sent to the peer.
		 * The interval should be chosen carefully to balance between detecting connection issues
		 * and minimizing unnecessary network traffic.
		 * </p>
		 *
		 * <p>
		 * If the interval is set too short, it may cause excessive network overhead.
		 * If set too long, connection failures may not be detected in a timely manner.
		 * </p>
		 *
		 * @param pingAckTimeout the interval in between consecutive PING frames
		 *                       and ACK responses. Must be a positive value.
		 */
		default Builder pingAckTimeout(Duration pingAckTimeout) {
			return this;
		}

		/**
		 * Sets the execution interval for the scheduler that sends HTTP/2 PING frames
		 * and periodically checks for ACK responses.
		 *
		 * <p>
		 * This method configures the time interval at which the scheduler runs
		 * to send PING frames and verify if ACK responses are received within
		 * the expected timeframe.
		 * Proper tuning of this interval helps in detecting connection issues
		 * while avoiding unnecessary network overhead.
		 * </p>
		 *
		 * <p>
		 * If the interval is too short, it may increase network and CPU usage.
		 * Conversely, setting it too long may delay the detection of connection failures.
		 * </p>
		 *
		 * @param pingScheduleInterval the interval in at which the scheduler executes.
		 *                       Must be a positive value.
		 */
		default Builder pingScheduleInterval(Duration pingScheduleInterval) {
			return this;
		}

		/**
		 * Sets the threshold for retrying HTTP/2 PING frame transmissions.
		 *
		 * <p>
		 * This method defines the maximum number of attempts to send a PING frame
		 * before considering the connection as unresponsive.
		 * If the threshold is exceeded without receiving an ACK response,
		 * the connection may be closed or marked as unhealthy.
		 * </p>
		 *
		 * <p>
		 * A lower threshold can detect connection failures more quickly but may lead
		 * to premature disconnections. Conversely, a higher threshold allows more retries
		 * but may delay failure detection.
		 * </p>
		 *
		 * <p>
		 * If this value is not specified, it defaults to 0, meaning only one attempt to send a PING frame is made without retries.
		 * </p>
		 *
		 * @param pingAckDropThreshold the maximum number of PING transmission attempts.
		 *                 Must be a positive integer.
		 *                 The default value is 0, meaning no retries will occur and only one PING frame will be sent.
		 */
		default Builder pingAckDropThreshold(Integer pingAckDropThreshold) {
			return this;
		}
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
	 * Returns the configured {@code pingAckTimeout} value or null.
	 *
	 * @return the configured {@code pingAckTimeout} value or null
	 */
	@Nullable
	public Duration pingAckTimeout() {
		return pingAckTimeout;
	}

	/**
	 * Returns the configured {@code pingScheduleInterval} value or null.
	 *
	 * @return the configured {@code pingScheduleInterval} value or null
	 */
	@Nullable
	public Duration pingScheduleInterval() {
		return pingScheduleInterval;
	}

	/**
	 * Returns the configured {@code pingAckDropThreshold} value or null.
	 *
	 * @return the configured {@code pingAckDropThreshold} value or null
	 */
	@Nullable
	public Integer pingAckDropThreshold() {
		return pingAckDropThreshold;
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
				Objects.equals(pingAckTimeout, that.pingAckTimeout) &&
				Objects.equals(pingScheduleInterval, that.pingScheduleInterval) &&
				Objects.equals(pingAckDropThreshold, that.pingAckDropThreshold);
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
		result = 31 * result + (pingAckTimeout == null ? 0 : Objects.hashCode(pingAckTimeout));
		result = 31 * result + (pingScheduleInterval == null ? 0 : Objects.hashCode(pingScheduleInterval));
		result = 31 * result + (pingAckDropThreshold == null ? 0 : Integer.hashCode(pingAckDropThreshold));
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
	final Duration pingAckTimeout;
	final Duration pingScheduleInterval;
	final Integer pingAckDropThreshold;

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
		pingAckTimeout = build.pingAckTimeout;
		pingScheduleInterval = build.pingScheduleInterval;
		pingAckDropThreshold = build.pingAckDropThreshold;
	}

	static final class Build implements Builder {
		Boolean connectProtocolEnabled;
		Long maxStreams;
		Duration pingAckTimeout;
		Duration pingScheduleInterval;
		Integer pingAckDropThreshold;
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
		public Builder pingAckTimeout(Duration pingAckTimeout) {
			this.pingAckTimeout = pingAckTimeout;
			return this;
		}

		@Override
		public Builder pingScheduleInterval(Duration pingScheduleInterval) {
			this.pingScheduleInterval = pingScheduleInterval;
			return this;
		}

		@Override
		public Builder pingAckDropThreshold(Integer pingAckDropThreshold) {
			this.pingAckDropThreshold = pingAckDropThreshold;
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

	public Http2Settings http2Settings() {
		Http2Settings settings = Http2Settings.defaultSettings();

		if (headerTableSize != null) {
			settings.headerTableSize(headerTableSize);
		}

		if (initialWindowSize != null) {
			settings.initialWindowSize(initialWindowSize);
		}

		if (maxConcurrentStreams != null) {
			settings.maxConcurrentStreams(maxConcurrentStreams);
		}

		if (maxFrameSize != null) {
			settings.maxFrameSize(maxFrameSize);
		}

		settings.maxHeaderListSize(maxHeaderListSize);

		if (pushEnabled != null) {
			settings.pushEnabled(pushEnabled);
		}

		return settings;
	}
}
