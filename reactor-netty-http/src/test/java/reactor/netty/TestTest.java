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
package reactor.netty;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingRecordingHandlerSpanCustomizer;
import io.micrometer.tracing.test.SampleTestRunner;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientMetricsTracingRecordingHandlerSpanCustomizer;
import reactor.netty.http.server.HttpServerMetricsTracingRecordingHandlerSpanCustomizer;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Function;

class TestTest extends SampleTestRunner {

	TestTest() {
		super(SamplerRunnerConfig
						.builder()
						.build(),
				Metrics.REGISTRY);
	}

	@Override
	public List<TracingRecordingHandlerSpanCustomizer> getTracingRecordingHandlerSpanCustomizers() {
		return Arrays.asList(new HttpClientMetricsTracingRecordingHandlerSpanCustomizer(), new HttpServerMetricsTracingRecordingHandlerSpanCustomizer());
	}

	@Override
	public TracingSetup[] getTracingSetup() {
		return new TracingSetup[]{TracingSetup.ZIPKIN_BRAVE};
	}

	@Override
	public BiConsumer<Tracer, MeterRegistry> yourCode() {
		byte[] bytes = new byte[1024 * 8];
		Random rndm = new Random();
		rndm.nextBytes(bytes);
		return (tracer, meterRegistry) ->
				HttpClient.create()
						.wiretap(true)
						.metrics(true, Function.identity())
						.post()
						.uri("https://httpbin.org/post")
						.send(ByteBufMono.fromString(Mono.just(new String(bytes))))
						.responseContent()
						.aggregate()
						.block();
	}
}
