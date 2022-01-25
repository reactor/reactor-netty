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
package reactor.netty.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingRecordingHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class ReactorNettyTracingRecordingHandler extends DefaultTracingRecordingHandler {

	public ReactorNettyTracingRecordingHandler(Tracer tracer) {
		super(tracer);
	}

	@Override
	public void tagSpan(Timer.HandlerContext context, Meter.Id id, Span span) {
		// TODO: Duplication
		SocketAddress address = context.get(SocketAddress.class);
		if (address != null && address instanceof InetSocketAddress) {
			InetSocketAddress inet = (InetSocketAddress) address;
			span.remoteIpAndPort(inet.getHostString(), inet.getPort());
		}
		for (Tag tag : context.getHighCardinalityTags()) {
			span.tag(tag.getKey(), tag.getValue());
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public String getSpanName(Timer.HandlerContext context, Meter.Id id) {
		ReactorNettyHandlerContext reactorNettyHandlerContext = (ReactorNettyHandlerContext) context;
		String name = reactorNettyHandlerContext.getSimpleName();
		if (name != null) {
			return name;
		}
		return super.getSpanName(context, id);
	}

	@Override
	public boolean supportsContext(Timer.HandlerContext handlerContext) {
		return handlerContext instanceof ReactorNettyHandlerContext;
	}
}
