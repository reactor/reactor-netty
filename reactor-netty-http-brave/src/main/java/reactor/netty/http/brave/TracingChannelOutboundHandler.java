/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
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
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.client.HttpClientRequest;

import static reactor.netty.http.brave.ReactorNettyHttpTracing.SPAN_ATTR_KEY;

/**
 * {@link io.netty.channel.ChannelOutboundHandler} to set the {@link Scope}.
 *
 * @author Violeta Georgieva
 * @since 1.0.6
 */
final class TracingChannelOutboundHandler extends ChannelOutboundHandlerAdapter {
	static final String NAME = NettyPipeline.RIGHT + "tracingChannelOutboundHandler";

	final CurrentTraceContext currentTraceContext;

	TracingChannelOutboundHandler(CurrentTraceContext currentTraceContext) {
		this.currentTraceContext = currentTraceContext;
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		//"FutureReturnValueIgnored" this is deliberate
		maybeScope(ctx.channel(), () -> ctx.write(msg, promise));
	}

	@Override
	public void flush(ChannelHandlerContext ctx) {
		maybeScope(ctx.channel(), () -> ctx.flush());
	}

	@Override
	public boolean isSharable() {
		return true;
	}

	@SuppressWarnings("try")
	void maybeScope(Channel channel, Runnable runnable) {
		Span span = channel.attr(SPAN_ATTR_KEY).get();
		if (span != null) {
			try (Scope scope = currentTraceContext.maybeScope(span.context())) {
				runnable.run();
			}
			return;
		}
		else {
			ChannelOperations<?, ?> ops = ChannelOperations.get(channel);
			if (ops instanceof HttpClientRequest) {
				TraceContext parent = ((HttpClientRequest) ops).currentContextView().getOrDefault(TraceContext.class, null);
				if (parent != null) {
					try (Scope scope = currentTraceContext.maybeScope(parent)) {
						runnable.run();
					}
					return;
				}
			}
		}

		runnable.run();
	}
}
