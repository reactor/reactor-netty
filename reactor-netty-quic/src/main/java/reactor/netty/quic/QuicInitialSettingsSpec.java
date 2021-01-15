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

import java.util.Objects;

/**
 * A configuration builder to fine tune the QUIC initial settings.
 *
 * @author Violeta Georgieva
 */
public final class QuicInitialSettingsSpec {

	public interface Builder {

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
		 * Set the initial maximum data limit for unidirectional streams.
		 * See
		 * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_initial_max_stream_data_uni">
		 * set_initial_max_stream_data_uni</a>.
		 * Default to {@link Build#DEFAULT_MAX_STREAM_DATA_UNIDIRECTIONAL}
		 *
		 * @param maxStreamDataUnidirectional the initial maximum data limit for unidirectional streams.
		 * @return {@code this}
		 */
		Builder maxStreamDataUnidirectional(long maxStreamDataUnidirectional);

		/**
		 * Set the initial maximum stream limit for bidirectional streams.
		 * See
		 * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_initial_max_streams_bidi">
		 * set_initial_max_streams_bidi</a>.
		 * Default to {@link Build#DEFAULT_MAX_STREAM_BIDIRECTIONAL}
		 *
		 * @param maxStreamsBidirectional the initial maximum stream limit for bidirectional streams
		 * @return {@code this}
		 */
		Builder maxStreamsBidirectional(long maxStreamsBidirectional);

		/**
		 * Set the initial maximum stream limit for unidirectional streams.
		 * See
		 * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_initial_max_streams_uni">
		 * set_initial_max_streams_uni</a>.
		 * Default to {@link Build#DEFAULT_MAX_STREAM_UNIDIRECTIONAL}
		 *
		 * @param maxStreamsUnidirectional the initial maximum stream limit for unidirectional streams
		 * @return {@code this}
		 */
		Builder maxStreamsUnidirectional(long maxStreamsUnidirectional);
	}

	final long maxData;
	final long maxStreamDataBidirectionalLocal;
	final long maxStreamDataBidirectionalRemote;
	final long maxStreamDataUnidirectional;
	final long maxStreamsBidirectional;
	final long maxStreamsUnidirectional;

	QuicInitialSettingsSpec(Build build) {
		this.maxData = build.maxData;
		this.maxStreamDataBidirectionalLocal = build.maxStreamDataBidirectionalLocal;
		this.maxStreamDataBidirectionalRemote = build.maxStreamDataBidirectionalRemote;
		this.maxStreamDataUnidirectional = build.maxStreamDataUnidirectional;
		this.maxStreamsBidirectional = build.maxStreamsBidirectional;
		this.maxStreamsUnidirectional = build.maxStreamsUnidirectional;
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
	 * Return the configured initial maximum data limit for unidirectional streams.
	 *
	 * @return the configured initial maximum data limit for unidirectional streams
	 */
	public long maxStreamDataUnidirectional() {
		return maxStreamDataUnidirectional;
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
	 * Return the configured initial maximum stream limit for unidirectional streams.
	 *
	 * @return the configured initial maximum stream limit for unidirectional streams
	 */
	public long maxStreamsUnidirectional() {
		return maxStreamsUnidirectional;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof QuicInitialSettingsSpec)) {
			return false;
		}
		QuicInitialSettingsSpec that = (QuicInitialSettingsSpec) o;
		return maxData == that.maxData &&
				maxStreamDataBidirectionalLocal == that.maxStreamDataBidirectionalLocal &&
				maxStreamDataBidirectionalRemote == that.maxStreamDataBidirectionalRemote &&
				maxStreamDataUnidirectional == that.maxStreamDataUnidirectional &&
				maxStreamsBidirectional == that.maxStreamsBidirectional &&
				maxStreamsUnidirectional == that.maxStreamsUnidirectional;
	}

	@Override
	public int hashCode() {
		return Objects.hash(maxData, maxStreamDataBidirectionalLocal, maxStreamDataBidirectionalRemote,
				maxStreamDataUnidirectional, maxStreamsBidirectional, maxStreamsUnidirectional);
	}

	static final class Build implements Builder {
		static final long DEFAULT_MAX_DATA = 0L;
		static final long DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL = 0L;
		static final long DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE = 0L;
		static final long DEFAULT_MAX_STREAM_DATA_UNIDIRECTIONAL = 0L;
		static final long DEFAULT_MAX_STREAM_BIDIRECTIONAL = 0L;
		static final long DEFAULT_MAX_STREAM_UNIDIRECTIONAL = 0L;

		long maxData = DEFAULT_MAX_DATA;
		long maxStreamDataBidirectionalLocal = DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL;
		long maxStreamDataBidirectionalRemote = DEFAULT_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE;
		long maxStreamDataUnidirectional = DEFAULT_MAX_STREAM_DATA_UNIDIRECTIONAL;
		long maxStreamsBidirectional = DEFAULT_MAX_STREAM_BIDIRECTIONAL;
		long maxStreamsUnidirectional = DEFAULT_MAX_STREAM_UNIDIRECTIONAL;

		QuicInitialSettingsSpec build() {
			return new QuicInitialSettingsSpec(this);
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
		public Builder maxStreamDataUnidirectional(long maxStreamDataUnidirectional) {
			if (maxStreamDataUnidirectional < 0) {
				throw new IllegalArgumentException("maxStreamDataUnidirectional must be positive or zero");
			}
			this.maxStreamDataUnidirectional = maxStreamDataUnidirectional;
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
		public Builder maxStreamsUnidirectional(long maxStreamsUnidirectional) {
			if (maxStreamsUnidirectional < 0) {
				throw new IllegalArgumentException("maxStreamsUnidirectional must be positive or zero");
			}
			this.maxStreamsUnidirectional = maxStreamsUnidirectional;
			return this;
		}
	}
}
