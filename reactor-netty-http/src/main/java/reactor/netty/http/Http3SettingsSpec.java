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

import io.netty.incubator.codec.quic.QuicTokenHandler;
import reactor.util.annotation.Incubating;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * A configuration builder to fine tune the HTTP/3 settings.
 *
 * @author Violeta Georgieva
 * @since 1.2.0
 */
@Incubating
public final class Http3SettingsSpec {

	public interface Builder {

		/**
		 * Build a new {@link Http3SettingsSpec}.
		 *
		 * @return a new {@link Http3SettingsSpec}
		 */
		Http3SettingsSpec build();

		/**
		 * Set the maximum idle timeout (resolution: ms)
		 * See <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_max_idle_timeout">
		 *     set_max_idle_timeout</a>.
		 * <p>By default {@code idleTimeout} is not specified.
		 *
		 * @param idleTimeout the maximum idle timeout (resolution: ms)
		 * @return {@code this}
		 */
		Builder idleTimeout(Duration idleTimeout);

		/**
		 * Set the initial maximum data limit.
		 * See <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_initial_max_data">
		 * set_initial_max_data</a>.
		 * Default to {@link Build#DEFAULT_MAX_DATA}
		 *
		 * @param maxData the initial maximum data limit
		 * @return {@code this}
		 */
		Builder maxData(long maxData);

		/**
		 * Set the initial maximum data limit for local bidirectional streams.
		 * See
		 * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_initial_max_stream_data_bidi_local">
		 * set_initial_max_stream_data_bidi_local</a>.
		 * Default to {@link Build#DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL}
		 *
		 * @param maxStreamDataBidirectionalLocal the initial maximum data limit for local bidirectional streams
		 * @return {@code this}
		 */
		Builder maxStreamDataBidirectionalLocal(long maxStreamDataBidirectionalLocal);

		/**
		 * Set the initial maximum data limit for remote bidirectional streams.
		 * See
		 * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_initial_max_stream_data_bidi_remote">
		 * set_initial_max_stream_data_bidi_remote</a>.
		 * Default to {@link Build#DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE}
		 *
		 * @param maxStreamDataBidirectionalRemote the initial maximum data limit for remote bidirectional streams
		 * @return {@code this}
		 */
		Builder maxStreamDataBidirectionalRemote(long maxStreamDataBidirectionalRemote);

		/**
		 * Set the initial maximum stream limit for bidirectional streams.
		 * See
		 * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_initial_max_streams_bidi">
		 * set_initial_max_streams_bidi</a>.
		 * Default to {@link Build#DEFAULT_MAX_STREAMS_BIDIRECTIONAL}
		 *
		 * @param maxStreamsBidirectional the initial maximum stream limit for bidirectional streams
		 * @return {@code this}
		 */
		Builder maxStreamsBidirectional(long maxStreamsBidirectional);

		/**
		 * Set the {@link QuicTokenHandler} that is used to generate and validate tokens or
		 * {@code null} if no tokens should be used at all.
		 * Default to {@code null}.
		 *
		 * @param tokenHandler  the {@link QuicTokenHandler} to use.
		 * @return {@code this}
		 */
		Builder tokenHandler(QuicTokenHandler tokenHandler);
	}

	/**
	 * Creates a builder for {@link Http3SettingsSpec}.
	 *
	 * @return a new {@link Http3SettingsSpec.Builder}
	 */
	public static Http3SettingsSpec.Builder builder() {
		return new Http3SettingsSpec.Build();
	}

	/**
	 * Return the configured maximum idle timeout or null.
	 *
	 * @return the configured maximum idle timeout or null
	 */
	@Nullable
	public Duration idleTimeout() {
		return idleTimeout;
	}

	/**
	 * Return the configured initial maximum data limit.
	 *
	 * @return the configured initial maximum data limit
	 */
	public long maxData() {
		return maxData;
	}

	/**
	 * Return the configured initial maximum data limit for local bidirectional streams.
	 *
	 * @return the configured initial maximum data limit for local bidirectional streams
	 */
	public long maxStreamDataBidirectionalLocal() {
		return maxStreamDataBidirectionalLocal;
	}

	/**
	 * Return the configured initial maximum data limit for remote bidirectional streams.
	 *
	 * @return the configured initial maximum data limit for remote bidirectional streams
	 */
	public long maxStreamDataBidirectionalRemote() {
		return maxStreamDataBidirectionalRemote;
	}

	/**
	 * Return the configured initial maximum stream limit for bidirectional streams.
	 *
	 * @return the configured initial maximum stream limit for bidirectional streams
	 */
	public long maxStreamsBidirectional() {
		return maxStreamsBidirectional;
	}

	/**
	 * Return the configured {@link QuicTokenHandler} or null.
	 *
	 * @return the configured {@link QuicTokenHandler} or null
	 */
	@Nullable
	public QuicTokenHandler tokenHandler() {
		return tokenHandler;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Http3SettingsSpec)) {
			return false;
		}
		Http3SettingsSpec that = (Http3SettingsSpec) o;
		return Objects.equals(idleTimeout, that.idleTimeout) &&
				maxData == that.maxData &&
				maxStreamDataBidirectionalLocal == that.maxStreamDataBidirectionalLocal &&
				maxStreamDataBidirectionalRemote == that.maxStreamDataBidirectionalRemote &&
				maxStreamsBidirectional == that.maxStreamsBidirectional &&
				Objects.equals(tokenHandler, that.tokenHandler);
	}

	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + Objects.hashCode(idleTimeout);
		result = 31 * result + Long.hashCode(maxData);
		result = 31 * result + Long.hashCode(maxStreamDataBidirectionalLocal);
		result = 31 * result + Long.hashCode(maxStreamDataBidirectionalRemote);
		result = 31 * result + Long.hashCode(maxStreamsBidirectional);
		result = 31 * result + Objects.hashCode(tokenHandler);
		return result;
	}

	final Duration idleTimeout;
	final long maxData;
	final long maxStreamDataBidirectionalLocal;
	final long maxStreamDataBidirectionalRemote;
	final long maxStreamsBidirectional;
	final QuicTokenHandler tokenHandler;

	Http3SettingsSpec(Build build) {
		this.idleTimeout = build.idleTimeout;
		this.maxData = build.maxData;
		this.maxStreamDataBidirectionalLocal = build.maxStreamDataBidirectionalLocal;
		this.maxStreamDataBidirectionalRemote = build.maxStreamDataBidirectionalRemote;
		this.maxStreamsBidirectional = build.maxStreamsBidirectional;
		this.tokenHandler = build.tokenHandler;
	}

	static final class Build implements Builder {
		static final long DEFAULT_MAX_DATA = 0L;
		static final long DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL = 0L;
		static final long DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE = 0L;
		static final long DEFAULT_MAX_STREAMS_BIDIRECTIONAL = 0L;

		Duration idleTimeout;
		long maxData = DEFAULT_MAX_DATA;
		long maxStreamDataBidirectionalLocal = DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL;
		long maxStreamDataBidirectionalRemote = DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE;
		long maxStreamsBidirectional = DEFAULT_MAX_STREAMS_BIDIRECTIONAL;
		QuicTokenHandler tokenHandler;

		@Override
		public Http3SettingsSpec build() {
			return new Http3SettingsSpec(this);
		}

		@Override
		public Builder idleTimeout(Duration idleTimeout) {
			this.idleTimeout = idleTimeout;
			return this;
		}

		@Override
		public Builder maxData(long maxData) {
			if (maxData < 0) {
				throw new IllegalArgumentException("maxData must be positive or zero");
			}
			this.maxData = maxData;
			return this;
		}

		@Override
		public Builder maxStreamDataBidirectionalLocal(long maxStreamDataBidirectionalLocal) {
			if (maxStreamDataBidirectionalLocal < 0) {
				throw new IllegalArgumentException("maxStreamDataBidirectionalLocal must be positive or zero");
			}
			this.maxStreamDataBidirectionalLocal = maxStreamDataBidirectionalLocal;
			return this;
		}

		@Override
		public Builder maxStreamDataBidirectionalRemote(long maxStreamDataBidirectionalRemote) {
			if (maxStreamDataBidirectionalRemote < 0) {
				throw new IllegalArgumentException("maxStreamDataBidirectionalRemote must be positive or zero");
			}
			this.maxStreamDataBidirectionalRemote = maxStreamDataBidirectionalRemote;
			return this;
		}

		@Override
		public Builder maxStreamsBidirectional(long maxStreamsBidirectional) {
			if (maxStreamsBidirectional < 0) {
				throw new IllegalArgumentException("maxStreamsBidirectional must be positive or zero");
			}
			this.maxStreamsBidirectional = maxStreamsBidirectional;
			return this;
		}

		@Override
		public Builder tokenHandler(QuicTokenHandler tokenHandler) {
			this.tokenHandler = tokenHandler;
			return this;
		}
	}
}
