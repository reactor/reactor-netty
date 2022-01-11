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
package reactor.netty.http.server;

import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingRecordingHandlerSpanCustomizer;

import java.time.Duration;

// TODO: Public for easier testing, maybe should go to a separate class or sth
public class HttpServerMetricsTracingRecordingHandlerSpanCustomizer implements TracingRecordingHandlerSpanCustomizer<Timer.HandlerContext> {

	@Override
	public boolean supportsContext(Timer.HandlerContext handlerContext) {
		return handlerContext instanceof MicrometerHttpServerMetricsHandler.WriteHandlerContext || handlerContext instanceof MicrometerHttpServerMetricsHandler.ReadHandlerContext;
	}

	@Override
	public void customizeSpanOnStop(Span span, Timer.Sample sample, Timer.HandlerContext context, Timer timer, Duration duration) {
		String metricName = timer.getId().getName();
		String clientDot = "client.";
		int clientIndex = metricName.indexOf(clientDot);
		int lastDot = metricName.lastIndexOf(".");
		String spanName = metricName.substring(clientIndex + clientDot.length(), lastDot).replace(".", " ");
		span.name(spanName);
		String[] tokenizedMetric = metricName.split("\\.");
		// TODO: handle bad metric names
		String protocol = tokenizedMetric[2];
		String type = tokenizedMetric[3];
		// TODO: Externalize this to a class
		span.tag("reactor.netty.type", type)
				.tag("reactor.netty.protocol", protocol);
		/**

		 reactor.netty.http.client.tls.handshake.time

		 name:
		 - tls handshake
		 tags:
		 - reactor.netty.type = client
		 - reactor.netty.protocol = http


		 reactor.netty.http.client.connect.time

		 name:
		 - connect
		 tags:
		 - reactor.netty.type = client
		 - reactor.netty.protocol = http


		 reactor.netty.http.client.data.sent.time

		 name:
		 - data sent
		 tags:
		 - reactor.netty.type = client
		 - reactor.netty.protocol = http

		 reactor.netty.http.client.response.time

		 name:
		 - response
		 tags:
		 - reactor.netty.type = client
		 - reactor.netty.protocol = http
		 events:
		 - client sent
		 - wire sent
		 - client received

		 reactor.netty.http.client.data.received.time

		 name:
		 - data received
		 tags:
		 - reactor.netty.type = client
		 - reactor.netty.protocol = http
		 events:
		 - wire received
		 - client received ??

		 */
	}
}
