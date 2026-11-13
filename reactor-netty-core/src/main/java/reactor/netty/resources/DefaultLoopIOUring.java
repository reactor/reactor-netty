/*
 * Copyright (c) 2020-2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.concurrent.ThreadFactory;

/**
 * {@link DefaultLoop} that uses {@code io_uring} transport.
 * <p>The {@code io_uring} transport requires Java 11 or later, the actual implementation
 * is provided in {@code META-INF/versions/11}. On Java 8, {@code io_uring} is never available.
 *
 * @author Violeta Georgieva
 */
final class DefaultLoopIOUring implements DefaultLoop {

	@Override
	public <CHANNEL extends Channel> CHANNEL getChannel(Class<CHANNEL> channelClass) {
		throw new UnsupportedOperationException("io_uring transport requires Java 11 or later");
	}

	@Override
	public <CHANNEL extends Channel> Class<? extends CHANNEL> getChannelClass(Class<CHANNEL> channelClass) {
		throw new UnsupportedOperationException("io_uring transport requires Java 11 or later");
	}

	@Override
	public String getName() {
		return "io_uring";
	}

	@Override
	public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		throw new UnsupportedOperationException("io_uring transport requires Java 11 or later");
	}

	@Override
	public boolean supportGroup(EventLoopGroup group) {
		return false;
	}

	static final Logger log = Loggers.getLogger(DefaultLoopIOUring.class);

	static final boolean isIoUringAvailable;

	static {
		// Must not be a compile-time constant, otherwise it is inlined in the callers
		// and the Java 11+ implementation will never be used
		isIoUringAvailable = false;
		if (log.isDebugEnabled()) {
			log.debug("Default io_uring support : " + isIoUringAvailable + " (requires Java 11 or later)");
		}
	}
}
