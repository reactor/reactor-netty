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
package reactor.netty.http.server.logging.error;

import io.netty.channel.Channel;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.server.HttpServerInfos;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.ZonedDateTime;

/**
 * Provides default information for logging errors that occur on the HTTP Server.
 *
 * @author raccoonback
 */
final class DefaultErrorLogArgProvider extends AbstractErrorLogArgProvider<DefaultErrorLogArgProvider> {

	private Throwable cause;

	DefaultErrorLogArgProvider(@Nullable SocketAddress remoteAddress) {
		super(remoteAddress);
	}

	@Override
	public DefaultErrorLogArgProvider get() {
		return this;
	}

	@Override
	public Throwable cause() {
		return cause;
	}

	public void clear() {
		cause = null;
	}

	void applyThrowable(Throwable cause) {
		this.errorDateTime = ZonedDateTime.now(ReactorNetty.ZONE_ID_SYSTEM);
		this.cause = cause;
	}

	void applyConnectionInfo(Channel channel) {
		ChannelOperations<?, ?> ops = ChannelOperations.get(channel);
		if (ops instanceof HttpServerInfos) {
			this.httpServerInfos = (HttpServerInfos) ops;
		}
	}
}
