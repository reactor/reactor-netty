/*
 * Copyright (c) 2022-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import io.micrometer.common.docs.KeyName;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.docs.MeterDocumentation;

/**
 * {@link ConnectionProvider} meters.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum ConnectionProviderMeters implements MeterDocumentation {

	/**
	 * The number of the connections in the connection pool that have been successfully acquired and are in active use.
	 */
	ACTIVE_CONNECTIONS {
		@Override
		public String getName() {
			return "reactor.netty.connection.provider.active.connections";
		}

		@Override
		public KeyName[] getKeyNames() {
			return ConnectionProviderMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The number of the idle connections in the connection pool.
	 */
	IDLE_CONNECTIONS {
		@Override
		public String getName() {
			return "reactor.netty.connection.provider.idle.connections";
		}

		@Override
		public KeyName[] getKeyNames() {
			return ConnectionProviderMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The maximum number of active connections that are allowed in the connection pool.
	 */
	MAX_CONNECTIONS {
		@Override
		public String getName() {
			return "reactor.netty.connection.provider.max.connections";
		}

		@Override
		public KeyName[] getKeyNames() {
			return ConnectionProviderMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The maximum number of requests that will be queued while waiting for a ready connection from the connection pool.
	 */
	MAX_PENDING_CONNECTIONS {
		@Override
		public String getName() {
			return "reactor.netty.connection.provider.max.pending.connections";
		}

		@Override
		public KeyName[] getKeyNames() {
			return ConnectionProviderMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The number of the request, that are pending acquire a connection from the connection pool.
	 */
	PENDING_CONNECTIONS {
		@Override
		public String getName() {
			return "reactor.netty.connection.provider.pending.connections";
		}

		@Override
		public KeyName[] getKeyNames() {
			return ConnectionProviderMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * Time spent in pending acquire a connection from the connection pool.
	 */
	PENDING_CONNECTIONS_TIME {
		@Override
		public String getName() {
			return "reactor.netty.connection.provider.pending.connections.time";
		}

		@Override
		public KeyName[] getKeyNames() {
			return PendingConnectionsTimeTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.TIMER;
		}
	},

	/**
	 * The number of all connections in the connection pool, active or idle.
	 */
	TOTAL_CONNECTIONS {
		@Override
		public String getName() {
			return "reactor.netty.connection.provider.total.connections";
		}

		@Override
		public KeyName[] getKeyNames() {
			return ConnectionProviderMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	};

	enum ConnectionProviderMetersTags implements KeyName {

		/**
		 * ID.
		 */
		ID {
			@Override
			public String asString() {
				return "id";
			}
		},

		/**
		 * NAME.
		 */
		NAME {
			@Override
			public String asString() {
				return "name";
			}
		},

		/**
		 * Remote address.
		 */
		REMOTE_ADDRESS {
			@Override
			public String asString() {
				return "remote.address";
			}
		}
	}

	enum PendingConnectionsTimeTags implements KeyName {

		/**
		 * ID.
		 */
		ID {
			@Override
			public String asString() {
				return "id";
			}
		},

		/**
		 * NAME.
		 */
		NAME {
			@Override
			public String asString() {
				return "name";
			}
		},

		/**
		 * Remote address.
		 */
		REMOTE_ADDRESS {
			@Override
			public String asString() {
				return "remote.address";
			}
		},

		/**
		 * STATUS.
		 */
		STATUS {
			@Override
			public String asString() {
				return "status";
			}
		}
	}
}
