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
package reactor.netty.http.server.logging;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import org.jspecify.annotations.Nullable;
import reactor.netty.http.server.HttpServerInfos;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Function;

import static reactor.netty.http.server.logging.AbstractAccessLogArgProvider.MISSING;

/**
 * {@link ChannelHandler} for access log.
 *
 * @author limaoning
 */
class BaseAccessLogHandler extends ChannelDuplexHandler {

	static final String DEFAULT_LOG_FORMAT =
			"{} - {} [{}] \"{} {} {}\" {} {} {}";

	@SuppressWarnings("deprecation")
	static final Function<AccessLogArgProvider, @Nullable AccessLog> DEFAULT_ACCESS_LOG =
			args -> AccessLog.create(DEFAULT_LOG_FORMAT, applyAddress(args.remoteAddress()), args.user(),
					args.zonedDateTime(), args.method(), args.uri(), args.protocol(), args.status(),
					args.contentLength() > -1 ? args.contentLength() : MISSING, args.duration());

	final Function<AccessLogArgProvider, @Nullable AccessLog> accessLog;

	BaseAccessLogHandler(@Nullable Function<AccessLogArgProvider, @Nullable AccessLog> accessLog) {
		this.accessLog = accessLog == null ? DEFAULT_ACCESS_LOG : accessLog;
	}

	static String applyAddress(@Nullable SocketAddress socketAddress) {
		return socketAddress instanceof InetSocketAddress ? ((InetSocketAddress) socketAddress).getHostString() : MISSING;
	}

	static <T extends AbstractAccessLogArgProvider<T>> void applyServerInfos(AbstractAccessLogArgProvider<T> accessLogArgs, HttpServerInfos serverInfos) {
		accessLogArgs.cookies(serverInfos.cookies());
		accessLogArgs.connectionInformation(serverInfos);
	}

}
