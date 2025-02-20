/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import reactor.netty.resources.ConnectionPoolMetrics;

/**
 * Interface for collecting HTTP/2 or HTTP/3 specific connection pool metrics.
 * Extends the {@link ConnectionPoolMetrics} interface to include metrics
 * related to active and pending HTTP/2 or HTTP/3 streams.
 *
 * @author raccoonback
 * @since 1.2.4
 */
public interface Http2ConnectionPoolMetrics extends ConnectionPoolMetrics {

	/**
	 * Measure the current number of active HTTP/2 or HTTP/3 streams in the connection pool.
	 *
	 * @return the number of active HTTP/2 or HTTP/3 streams
	 */
	int activeStreamSize();

	/**
	 * Measure the current number of pending HTTP/2 streams in the connection pool.
	 *
	 * @return the number of pending HTTP/2 streams
	 */
	int pendingStreamSize();
}
