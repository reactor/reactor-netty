/*
 * Copyright (c) 2022-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import reactor.netty.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static reactor.netty.ReactorNetty.format;

/**
 * A handler that manages idle timeout for HTTP connections.
 * This handler will close the connection if it remains idle for the specified duration.
 * It also checks the liveness of the HTTP connection.
 *
 * @author raccoonback
 * @since 1.2.5
 */
public final class IdleTimeoutHandler extends IdleStateHandler {

	private static final Logger log = Loggers.getLogger(IdleTimeoutHandler.class);

	private final HttpConnectionLiveness httpConnectionLiveness;

	private IdleTimeoutHandler(long idleTimeout, HttpConnectionLiveness httpConnectionLiveness) {
		super(0, 0, idleTimeout, TimeUnit.MILLISECONDS);
		this.httpConnectionLiveness = httpConnectionLiveness;
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) {
		if (evt.state() == IdleState.ALL_IDLE) {
			if (log.isDebugEnabled()) {
				log.debug(format(ctx.channel(),
								"Connection was idle for [{}ms], as per configuration the connection will be closed."),
						getAllIdleTimeInMillis());
			}
			// FutureReturnValueIgnored is deliberate

			httpConnectionLiveness.check(ctx);
		}

		ctx.fireUserEventTriggered(evt);
	}


	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		httpConnectionLiveness.receive(msg);

		super.channelRead(ctx, msg);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		httpConnectionLiveness.cancel();

		super.channelInactive(ctx);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		httpConnectionLiveness.cancel();

		super.handlerRemoved(ctx);
	}

	/**
	 * Adds an idle timeout handler to the server pipeline.
	 *
	 * @param pipeline               the channel pipeline
	 * @param idleTimeout            the idle timeout duration
	 * @param httpConnectionLiveness the HTTP connection liveness checker
	 */
	public static void addIdleTimeoutHandler(ChannelPipeline pipeline, @Nullable Duration idleTimeout,
	                                               HttpConnectionLiveness httpConnectionLiveness) {
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
					new IdleTimeoutHandler(
							idleTimeout.toMillis(),
							httpConnectionLiveness
					)
			);
		}
	}

	/**
	 * Removes the idle timeout handler from the pipeline if it exists.
	 *
	 * @param pipeline the channel pipeline from which the handler will be removed
	 */
	public static void removeIdleTimeoutHandler(ChannelPipeline pipeline) {
		if (pipeline.get(NettyPipeline.IdleTimeoutHandler) != null) {
			pipeline.remove(NettyPipeline.IdleTimeoutHandler);
		}
	}
}
