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
package reactor.netty.http.client;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingRecordingHandler;
import io.micrometer.tracing.handler.TracingRecordingHandlerSpanCustomizer;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.function.Function;

import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;
import static reactor.netty.Metrics.formatSocketAddress;

/**
 * @author Violeta Georgieva
 * @since 1.1.0
 */
final class MicrometerHttpClientMetricsHandler extends AbstractHttpClientMetricsHandler {

	final Timer.Builder dataReceivedTimeBuilder;
	final Timer.Builder dataSentTimeBuilder;
	final MicrometerHttpClientMetricsRecorder recorder;
	final Timer.Builder responseTimeBuilder;

	Timer.Sample dataReceivedTimeSample;
	Timer.Sample dataSentTimeSample;
	ReadHandlerContext responseTimeHandlerContext;
	Timer.Sample responseTimeSample;

	MicrometerHttpClientMetricsHandler(MicrometerHttpClientMetricsRecorder recorder,
			@Nullable Function<String, String> uriTagValue) {
		super(uriTagValue);
		this.recorder = recorder;

		this.dataReceivedTimeBuilder =
				Timer.builder(recorder.name() + DATA_RECEIVED_TIME)
				     .description("Time spent in consuming incoming data");

		this.dataSentTimeBuilder =
				Timer.builder(recorder.name() + DATA_SENT_TIME)
				     .description("Time spent in sending outgoing data");

		this.responseTimeBuilder =
				Timer.builder(recorder.name() + RESPONSE_TIME)
				     .description("Total time for the request/response");
	}

	@Override
	protected HttpClientMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	protected void recordRead(SocketAddress address) {
		// TODO
		// Cannot invoke the recorder any more:
		// 1. The recorder is one instance only, it is invoked for all requests that can happen
		// 2. The recorder does not have knowledge about request lifecycle
		//
		// Move the implementation from the recorder here
		//
		// Important:
		// Cannot cache the Timer anymore - need to test the performance
		// Can we use sample.stop(Timer)
		dataReceivedTimeSample.stop(dataReceivedTimeBuilder);

		responseTimeHandlerContext.status = status;
		responseTimeSample.stop(responseTimeBuilder);

		recorder().recordDataReceived(address, path, dataReceived);
	}

	@Override
	protected void recordWrite(SocketAddress address) {
		// TODO
		// Cannot invoke the recorder any more:
		// 1. The recorder is one instance only, it is invoked for all requests that can happen
		// 2. The recorder does not have knowledge about request lifecycle
		//
		// Move the implementation from the recorder here
		//
		// Important:
		// Cannot cache the Timer anymore - need to test the performance
		// Can we use sample.stop(Timer)
		dataSentTimeSample.stop(dataSentTimeBuilder);

		recorder().recordDataSent(address, path, dataSent);
	}

	@Override
	protected void reset() {
		super.reset();
		dataReceivedTimeSample = null;
		dataSentTimeSample = null;
		responseTimeHandlerContext = null;
		responseTimeSample = null;
	}

	@Override
	protected void startRead(SocketAddress address) {
		dataReceivedTimeSample = Timer.start(REGISTRY, new ReadHandlerContext(method, path,
				formatSocketAddress(address), status));
	}

	@Override
	protected void startWrite(SocketAddress address) {
		dataSentTimeSample = Timer.start(REGISTRY, new WriteHandlerContext(method, path, formatSocketAddress(address)));

		responseTimeHandlerContext = new ReadHandlerContext(method, path, formatSocketAddress(address));
		responseTimeSample = Timer.start(REGISTRY, responseTimeHandlerContext);
	}

	static final class ReadHandlerContext extends Timer.HandlerContext {

		final String method;
		final String path;
		final String remoteAddress;

		// status might not be known beforehand
		String status;

		ReadHandlerContext(String method, String path, String remoteAddress) {
			this(method, path, remoteAddress, null);
		}

		ReadHandlerContext(String method, String path, String remoteAddress, @Nullable String status) {
			this.method = method;
			this.path = path;
			this.remoteAddress = remoteAddress;
			this.status = status;
		}

		// TODO: What do we do about these in tracing ?
		@Override
		public Tags getLowCardinalityTags() {
			return Tags.of(REMOTE_ADDRESS, remoteAddress, URI, path, METHOD, method, STATUS, status);
		}
	}

	static final class WriteHandlerContext extends Timer.HandlerContext {

		final String method;
		final String path;
		final String remoteAddress;

		WriteHandlerContext(String method, String path, String remoteAddress) {
			this.method = method;
			this.path = path;
			this.remoteAddress = remoteAddress;
		}

		// TODO: What do we do about these in tracing ?
		@Override
		public Tags getLowCardinalityTags() {
			return Tags.of(REMOTE_ADDRESS, remoteAddress, URI, path, METHOD, method);
		}
	}

}
