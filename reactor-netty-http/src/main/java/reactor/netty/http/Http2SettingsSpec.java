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
import org.jspecify.annotations.Nullable;

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
		 * For server, the default {code maxEncodedRstFramesPerWindow} is {@code 200} and for the client it is {@code 0}.
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
		 * Sets the maximum number of PING frame transmission attempts before closing the connection.
		 *
		 * <p>
		 * This method configures how many PING frames will be sent without receiving an ACK
		 * before considering the connection as unresponsive and closing it.
		 * Each PING waits for {@link #pingAckTimeout(Duration)} before either receiving an ACK
		 * (which resets the health check) or timing out and sending the next PING.
		 * </p>
		 *
		 * <p>
		 * <strong>Example with {@code pingAckDropThreshold=1}:</strong>
		 * <ol>
		 *   <li>Connection becomes idle</li>
		 *   <li>First PING frame is sent</li>
		 *   <li>Wait up to {@code pingAckTimeout} for ACK</li>
		 *   <li>If no ACK received, connection is closed (1 attempt limit reached)</li>
		 * </ol>
		 * </p>
		 *
		 * <p>
		 * <strong>Example with {@code pingAckDropThreshold=2}:</strong>
		 * <ol>
		 *   <li>Connection becomes idle</li>
		 *   <li>First PING frame is sent</li>
		 *   <li>Wait up to {@code pingAckTimeout} for ACK</li>
		 *   <li>If no ACK received, second PING frame is sent</li>
		 *   <li>Wait up to {@code pingAckTimeout} for ACK</li>
		 *   <li>If no ACK received, connection is closed (2 attempt limit reached)</li>
		 * </ol>
		 * </p>
		 *
		 * <p>
		 * A lower threshold detects connection failures more quickly but may lead
		 * to premature disconnections if network latency is high. A higher threshold
		 * tolerates more packet loss or delays but increases the time to detect truly dead connections.
		 * </p>
		 *
		 * <p>
		 * The default {@code pingAckDropThreshold} is {@code 1}, meaning only one PING frame
		 * will be sent. If no ACK is received within {@code pingAckTimeout}, the connection closes immediately.
		 * </p>
		 *
		 * @param pingAckDropThreshold the maximum number of PING transmission attempts without receiving ACK.
		 * Must be a positive integer (minimum 1). Default is 1.
		 * @return {@code this}
		 * @since 1.2.12
		 */
		default Builder pingAckDropThreshold(int pingAckDropThreshold) {
			return this;
		}

		/**
		 * Sets the timeout for receiving an ACK response to HTTP/2 PING frames.
		 *
		 * <p>
		 * This method configures how long to wait for a PING ACK response before
		 * either retrying or closing the connection (based on {@link #pingAckDropThreshold(int)}).
		 * This timeout is used in conjunction with the idle timeout to detect unresponsive connections.
		 * </p>
		 *
		 * <p>
		 * When a connection becomes idle (no reads/writes for the configured idle timeout duration),
		 * a PING frame is sent to check if the peer is still responsive. If no ACK is received
		 * within the {@code pingAckTimeout} duration, another PING attempt may be made
		 * (depending on {@code pingAckDropThreshold}). If all attempts fail, the connection is closed.
		 * </p>
		 *
		 * <p>
		 * <strong>Important:</strong> This setting only takes effect when used together with:
		 * <ul>
		 *   <li>For client: {@code ConnectionProvider} with {@code maxIdleTime} configured</li>
		 *   <li>For server: {@code HttpServer} with {@code idleTimeout} configured</li>
		 * </ul>
		 * Without an idle timeout, PING frames will not be sent.
		 * </p>
		 *
		 * <p>
		 * The timeout should be chosen based on your network conditions and requirements:
		 * <ul>
		 *   <li>Too short: May cause false positives and premature connection closures due to temporary delays</li>
		 *   <li>Too long: Delays detection of truly unresponsive connections</li>
		 * </ul>
		 * Consider your expected network latency, load patterns, and tolerance for stale connections.
		 * </p>
		 *
		 * @param pingAckTimeout the timeout duration to wait for a PING ACK response.
		 * Must be a positive value.
		 * @return {@code this}
		 * @since 1.2.12
		 */
		default Builder pingAckTimeout(Duration pingAckTimeout) {
			return this;
		}

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
	public @Nullable Boolean connectProtocolEnabled() {
		return connectProtocolEnabled;
	}

	/**
	 * Returns the configured {@code SETTINGS_HEADER_TABLE_SIZE} value or null.
	 *
	 * @return the configured {@code SETTINGS_HEADER_TABLE_SIZE} value or null
	 */
	public @Nullable Long headerTableSize() {
		return headerTableSize;
	}

	/**
	 * Returns the configured {@code SETTINGS_INITIAL_WINDOW_SIZE} value or null.
	 *
	 * @return the configured {@code SETTINGS_INITIAL_WINDOW_SIZE} value or null
	 */
	public @Nullable Integer initialWindowSize() {
		return initialWindowSize;
	}

	/**
	 * Returns the configured {@code SETTINGS_MAX_CONCURRENT_STREAMS} value or null.
	 *
	 * @return the configured {@code SETTINGS_MAX_CONCURRENT_STREAMS} value or null
	 */
	public @Nullable Long maxConcurrentStreams() {
		return maxConcurrentStreams;
	}

	/**
	 * Returns the configured maximum number RST frames that are allowed per window or null.
	 *
	 * @return the configured maximum number RST frames that are allowed per window or null
	 * @since 1.2.11
	 */
	public @Nullable Integer maxDecodedRstFramesPerWindow() {
		return maxDecodedRstFramesPerWindow;
	}

	/**
	 * Returns the configured maximum seconds per window or null.
	 *
	 * @return the configured maximum seconds per window or null
	 * @since 1.2.11
	 */
	public @Nullable Integer maxDecodedRstFramesSecondsPerWindow() {
		return maxDecodedRstFramesSecondsPerWindow;
	}

	/**
	 * Returns the configured maximum number RST frames that are allowed per window or null.
	 *
	 * @return the configured maximum number RST frames that are allowed per window or null
	 * @since 1.2.11
	 */
	public @Nullable Integer maxEncodedRstFramesPerWindow() {
		return maxEncodedRstFramesPerWindow;
	}

	/**
	 * Returns the configured maximum seconds per window or null.
	 *
	 * @return the configured maximum seconds per window or null
	 * @since 1.2.11
	 */
	public @Nullable Integer maxEncodedRstFramesSecondsPerWindow() {
		return maxEncodedRstFramesSecondsPerWindow;
	}

	/**
	 * Returns the configured {@code SETTINGS_MAX_FRAME_SIZE} value or null.
	 *
	 * @return the configured {@code SETTINGS_MAX_FRAME_SIZE} value or null
	 */
	public @Nullable Integer maxFrameSize() {
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
	public @Nullable Long maxStreams() {
		return maxStreams;
	}

	/**
	 * Returns the configured {@code pingAckDropThreshold} value.
	 *
	 * @return the configured {@code pingAckDropThreshold} value
	 * @since 1.2.12
	 */
	public Integer pingAckDropThreshold() {
		return pingAckDropThreshold;
	}

	/**
	 * Returns the configured {@code pingAckTimeout} value or null.
	 *
	 * @return the configured {@code pingAckTimeout} value or null
	 * @since 1.2.12
	 */
	public @Nullable Duration pingAckTimeout() {
		return pingAckTimeout;
	}

	/**
	 * Returns the configured {@code SETTINGS_ENABLE_PUSH} value or null.
	 *
	 * @return the configured {@code SETTINGS_ENABLE_PUSH} value or null
	 */
	public @Nullable Boolean pushEnabled() {
		return pushEnabled;
	}

	@Override
	public boolean equals(@Nullable Object o) {
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
				pingAckDropThreshold.equals(that.pingAckDropThreshold) &&
				Objects.equals(pingAckTimeout, that.pingAckTimeout) &&
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
		result = 31 * result + Long.hashCode(maxHeaderListSize);
		result = 31 * result + (maxStreams == null ? 0 : Long.hashCode(maxStreams));
		result = 31 * result + pingAckDropThreshold;
		result = 31 * result + (pingAckTimeout == null ? 0 : Objects.hashCode(pingAckTimeout));
		result = 31 * result + (pushEnabled == null ? 0 : Boolean.hashCode(pushEnabled));
		return result;
	}

	final @Nullable Boolean connectProtocolEnabled;
	final @Nullable Long headerTableSize;
	final @Nullable Integer initialWindowSize;
	final @Nullable Long maxConcurrentStreams;
	final @Nullable Integer maxDecodedRstFramesPerWindow;
	final @Nullable Integer maxDecodedRstFramesSecondsPerWindow;
	final @Nullable Integer maxEncodedRstFramesPerWindow;
	final @Nullable Integer maxEncodedRstFramesSecondsPerWindow;
	final @Nullable Integer maxFrameSize;
	final Long maxHeaderListSize;
	final @Nullable Long maxStreams;
	final Integer pingAckDropThreshold;
	final @Nullable Duration pingAckTimeout;
	final @Nullable Boolean pushEnabled;

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
		pingAckDropThreshold = build.pingAckDropThreshold;
		pingAckTimeout = build.pingAckTimeout;
		pushEnabled = settings.pushEnabled();
	}

	static final class Build implements Builder {
		static final int DEFAULT_PING_ACK_DROP_THRESHOLD = 1;

		@Nullable Boolean connectProtocolEnabled;
		@Nullable Integer maxDecodedRstFramesPerWindow;
		@Nullable Integer maxDecodedRstFramesSecondsPerWindow;
		@Nullable Integer maxEncodedRstFramesPerWindow;
		@Nullable Integer maxEncodedRstFramesSecondsPerWindow;
		@Nullable Long maxStreams;
		Integer pingAckDropThreshold = Integer.valueOf(DEFAULT_PING_ACK_DROP_THRESHOLD);
		@Nullable Duration pingAckTimeout;
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

		@Override
		public Builder pingAckDropThreshold(int pingAckDropThreshold) {
			if (pingAckDropThreshold < 1) {
				throw new IllegalArgumentException("pingAckDropThreshold must be positive");
			}
			this.pingAckDropThreshold = Integer.valueOf(pingAckDropThreshold);
			return this;
		}

		@Override
		public Builder pingAckTimeout(Duration pingAckTimeout) {
			Objects.requireNonNull(pingAckTimeout, "pingAckTimeout");
			if (pingAckTimeout.isNegative() || pingAckTimeout.isZero()) {
				throw new IllegalArgumentException("pingAckTimeout must be positive");
			}
			this.pingAckTimeout = pingAckTimeout;
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
