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
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.jspecify.annotations.Nullable;
import reactor.netty.NettyPipeline;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * A handler that manages idle timeout for HTTP connections.
 * This handler will close the connection if it remains idle for the specified duration.
 * It may also check the liveness of the HTTP connection with sending series of ping messages.
 *
 * @author raccoonback
 * @author Violeta Georgieva
 * @since 1.2.12
 */
public final class IdleTimeoutHandler extends IdleStateHandler {

	private final HttpConnectionLiveness httpConnectionLiveness;

	private IdleTimeoutHandler(long idleTimeout, HttpConnectionLiveness httpConnectionLiveness) {
		super(0, 0, idleTimeout, TimeUnit.MILLISECONDS);
		this.httpConnectionLiveness = httpConnectionLiveness;
	}

	@Override
	protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) {
		if (evt.state() == IdleState.ALL_IDLE) {
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
	 * Adds an idle timeout handler to the pipeline.
	 *
	 * @param pipeline               the channel pipeline
	 * @param idleTimeout            the idle timeout duration
	 * @param httpConnectionLiveness the HTTP connection liveness checker
	 * @since 1.2.12
	 */
	public static void addIdleTimeoutHandler(ChannelPipeline pipeline, @Nullable Duration idleTimeout,
			HttpConnectionLiveness httpConnectionLiveness) {
		if (idleTimeout != null && pipeline.get(NettyPipeline.IdleTimeoutHandler) == null) {
			String baseName = null;
			if (pipeline.get(NettyPipeline.HttpCodec) != null) {
				baseName = NettyPipeline.HttpCodec;
			}
			else {
				ChannelHandler http2FrameCodec = pipeline.get(Http2FrameCodec.class);
				if (http2FrameCodec != null) {
					baseName = pipeline.context(http2FrameCodec).name();
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
			}

			pipeline.addAfter(baseName,
					NettyPipeline.IdleTimeoutHandler,
					new IdleTimeoutHandler(idleTimeout.toMillis(), httpConnectionLiveness));
		}
	}

	/**
	 * Removes the idle timeout handler from the pipeline if it exists.
	 *
	 * @param pipeline the channel pipeline from which the handler will be removed
	 * @since 1.2.12
	 */
	public static void removeIdleTimeoutHandler(ChannelPipeline pipeline) {
		if (pipeline.get(NettyPipeline.IdleTimeoutHandler) != null) {
			pipeline.remove(NettyPipeline.IdleTimeoutHandler);
		}
	}
}
