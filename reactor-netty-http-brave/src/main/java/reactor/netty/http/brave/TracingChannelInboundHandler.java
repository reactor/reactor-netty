/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.brave;

import brave.Span;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.client.HttpClientResponse;

import static reactor.netty.http.brave.ReactorNettyHttpTracing.SPAN_ATTR_KEY;

/**
 * {@link io.netty.channel.ChannelInboundHandler} to set the {@link Scope}.
 *
 * @author Violeta Georgieva
 * @since 1.0.6
 */
final class TracingChannelInboundHandler extends ChannelInboundHandlerAdapter {
	static final String NAME = NettyPipeline.LEFT + "tracingChannelInboundHandler";

	final CurrentTraceContext currentTraceContext;

	TracingChannelInboundHandler(CurrentTraceContext currentTraceContext) {
		this.currentTraceContext = currentTraceContext;
	}

	@Override
	@SuppressWarnings("try")
	public void channelActive(ChannelHandlerContext ctx) {
		Connection conn = Connection.from(ctx.channel());
		if (conn instanceof ConnectionObserver) {
			TraceContext parent = ((ConnectionObserver) conn).currentContext().getOrDefault(TraceContext.class, null);
			if (parent != null) {
				try (Scope scope = currentTraceContext.maybeScope(parent)) {
					ctx.fireChannelActive();
				}
				return;
			}
		}

		ctx.fireChannelActive();
	}

	@Override
	@SuppressWarnings("try")
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		Span span = ctx.channel().attr(SPAN_ATTR_KEY).get();
		if (span != null) {
			try (Scope scope = currentTraceContext.maybeScope(span.context())) {
				ctx.fireChannelRead(msg);
			}
			return;
		}
		else {
			ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
			if (ops instanceof HttpClientResponse) {
				TraceContext parent = ((HttpClientResponse) ops).currentContextView().getOrDefault(TraceContext.class, null);
				if (parent != null) {
					try (Scope scope = currentTraceContext.maybeScope(parent)) {
						ctx.fireChannelRead(msg);
					}
					return;
				}
			}
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public boolean isSharable() {
		return true;
	}
}
