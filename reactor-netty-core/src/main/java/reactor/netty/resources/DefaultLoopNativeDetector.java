/*
 * Copyright (c) 2018-2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.EventLoopGroup;
import org.jspecify.annotations.Nullable;

/**
 * Provides an {@link DefaultLoop} instance based on the available transport.
 *
 * @author Violeta Georgieva
 */
final class DefaultLoopNativeDetector {

	static final DefaultLoop INSTANCE;

	static final DefaultLoop NIO;

	@Nullable
	static final DefaultLoop IO_URING;

	@Nullable
	static final DefaultLoop EPOLL;

	@Nullable
	static final DefaultLoop KQUEUE;

	static {
		NIO = new DefaultLoopNIO();
		IO_URING = DefaultLoopIOUring.isIoUringAvailable ? new DefaultLoopIOUring() : null;
		EPOLL = DefaultLoopEpoll.isEpollAvailable ? new DefaultLoopEpoll() : null;
		KQUEUE = DefaultLoopKQueue.isKqueueAvailable ? new DefaultLoopKQueue() : null;

		if (IO_URING != null) {
			INSTANCE = IO_URING;
		}
		else if (EPOLL != null) {
			INSTANCE = EPOLL;
		}
		else if (KQUEUE != null) {
			INSTANCE = KQUEUE;
		}
		else {
			INSTANCE = NIO;
		}
	}

	/**
	 * Finds the correct {@link DefaultLoop} for the given {@link EventLoopGroup}.
	 * This method checks all available native transports to find one that supports
	 * the provided group, falling back to NIO if none match.
	 *
	 * @param group the event loop group to find a matching channel factory for
	 * @return the appropriate {@link DefaultLoop} for the group
	 */
	static DefaultLoop forGroup(EventLoopGroup group) {
		// Check each available native transport in priority order
		if (IO_URING != null && IO_URING.supportGroup(group)) {
			return IO_URING;
		}
		if (EPOLL != null && EPOLL.supportGroup(group)) {
			return EPOLL;
		}
		if (KQUEUE != null && KQUEUE.supportGroup(group)) {
			return KQUEUE;
		}
		if (NIO.supportGroup(group)) {
			return NIO;
		}

		throw new IllegalArgumentException("Unsupported event loop group: " + group);
	}
}
