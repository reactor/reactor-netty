/*
 * Copyright (c) 2022-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import reactor.netty.NettyPipeline;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static reactor.netty.ReactorNetty.format;

final class IdleTimeoutHandler extends IdleStateHandler {

	IdleTimeoutHandler(long idleTimeout) {
		super(idleTimeout, 0, 0, TimeUnit.MILLISECONDS);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) {
		if (evt.state() == IdleState.READER_IDLE) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(format(ctx.channel(),
								"Connection was idle for [{}ms], as per configuration the connection will be closed."),
						getReaderIdleTimeInMillis());
			}
			// FutureReturnValueIgnored is deliberate
			ctx.close();
		}
		ctx.fireUserEventTriggered(evt);
	}

	static void addIdleTimeoutHandler(ChannelPipeline pipeline, @Nullable Duration idleTimeout) {
		if (idleTimeout != null && pipeline.get(NettyPipeline.IdleTimeoutHandler) == null) {
			String baseName = null;
			if (pipeline.get(NettyPipeline.HttpCodec) != null) {
				baseName = NettyPipeline.HttpCodec;
			}
			else {
				ChannelHandler httpServerUpgradeHandler = pipeline.get(HttpServerUpgradeHandler.class);
				if (httpServerUpgradeHandler != null) {
					baseName = pipeline.context(httpServerUpgradeHandler).name();
				}
				else {
					ChannelHandler httpServerCodec = pipeline.get(HttpServerCodec.class);
					if (httpServerCodec != null) {
						baseName = pipeline.context(httpServerCodec).name();
					}
				}
			}

			pipeline.addAfter(baseName,
					NettyPipeline.IdleTimeoutHandler,
					new IdleTimeoutHandler(idleTimeout.toMillis()));
		}
	}

	static void removeIdleTimeoutHandler(ChannelPipeline pipeline) {
		if (pipeline.get(NettyPipeline.IdleTimeoutHandler) != null) {
			pipeline.remove(NettyPipeline.IdleTimeoutHandler);
		}
	}
}
