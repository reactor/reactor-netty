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
package reactor.netty5.http.server.logging.error;

import io.netty5.channel.Channel;
import org.jspecify.annotations.Nullable;
import reactor.netty5.ReactorNetty;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.http.server.HttpServerInfos;

import java.net.SocketAddress;
import java.time.ZonedDateTime;

/**
 * A default implementation of the args required for error log.
 *
 * @author raccoonback
 */
final class DefaultErrorLogArgProvider extends AbstractErrorLogArgProvider<DefaultErrorLogArgProvider> {

	@SuppressWarnings("NullAway")
	// Deliberately suppress "NullAway"
	// This is a lazy initialization
	private Throwable cause;
	@SuppressWarnings("NullAway")
	// Deliberately suppress "NullAway"
	// This is a lazy initialization
	private ZonedDateTime errorDateTime;
	private @Nullable HttpServerInfos httpServerInfos;

	DefaultErrorLogArgProvider(@Nullable SocketAddress remoteAddress) {
		super(remoteAddress);
	}

	@Override
	public Throwable cause() {
		return cause;
	}

	@Override
	public ZonedDateTime errorDateTime() {
		return errorDateTime;
	}

	@Override
	public DefaultErrorLogArgProvider get() {
		return this;
	}

	@Override
	public @Nullable HttpServerInfos httpServerInfos() {
		return httpServerInfos;
	}

	@SuppressWarnings("NullAway")
	// Deliberately suppress "NullAway"
	// This is a lazy initialization
	void clear() {
		cause = null;
		errorDateTime = null;
		httpServerInfos = null;
	}

	void applyConnectionInfo(Channel channel) {
		ChannelOperations<?, ?> ops = ChannelOperations.get(channel);
		if (ops instanceof HttpServerInfos) {
			this.httpServerInfos = (HttpServerInfos) ops;
		}
	}

	void applyThrowable(Throwable cause) {
		this.cause = cause;
		this.errorDateTime = ZonedDateTime.now(ReactorNetty.ZONE_ID_SYSTEM);
	}
}
