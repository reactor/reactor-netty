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

import io.netty.channel.ChannelHandlerContext;
import reactor.util.annotation.Nullable;

import java.util.function.Function;

/**
 * Handler for logging errors that occur in the HTTP Server.
 *
 * @author raccoonback
 * @since 1.2.5
 */
public final class DefaultErrorLogHandler extends BaseErrorLogHandler {

	private DefaultErrorLogArgProvider errorLogArgProvider;

	public DefaultErrorLogHandler(@Nullable Function<ErrorLogArgProvider, ErrorLog> errorLog) {
		super(errorLog);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		ErrorLog log;

		if (errorLogArgProvider == null) {
			errorLogArgProvider = new DefaultErrorLogArgProvider(ctx.channel().remoteAddress());
		}
		else {
			errorLogArgProvider.clear();
		}

		errorLogArgProvider.applyThrowable(cause);
		errorLogArgProvider.applyConnectionInfo(ctx.channel());
		log = errorLog.apply(errorLogArgProvider);

		if (log != null) {
			log.log();
		}

		super.exceptionCaught(ctx, cause);
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof DefaultErrorLoggingEvent) {
			exceptionCaught(ctx, ((DefaultErrorLoggingEvent) evt).getThrowable());
		}

		super.userEventTriggered(ctx, evt);
	}
}
