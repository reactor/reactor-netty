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

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import reactor.util.annotation.Nullable;

import java.util.function.Function;

import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;

/**
 * @author Violeta Georgieva
 * @since 1.1.0
 */
final class MicrometerHttpServerMetricsHandler extends AbstractHttpServerMetricsHandler {

	final Timer.Builder dataReceivedTimeBuilder;
	final Timer.Builder dataSentTimeBuilder;
	final MicrometerHttpServerMetricsRecorder recorder;
	final Timer.Builder responseTimeBuilder;

	Timer.Sample dataReceivedTimeSample;
	Timer.Sample dataSentTimeSample;
	WriteHandlerContext responseTimeHandlerContext;
	Timer.Sample responseTimeSample;

	MicrometerHttpServerMetricsHandler(MicrometerHttpServerMetricsRecorder recorder,
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
	protected HttpServerMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	protected void recordRead(HttpServerOperations ops, String path, String method) {
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

		// Always take the remote address from the operations in order to consider proxy information
		recorder().recordDataReceived(ops.remoteAddress(), path, dataReceived);

		dataReceivedTimeSample = null;
	}

	@Override
	protected void recordWrite(HttpServerOperations ops, String path, String method, String status) {
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

		responseTimeSample.stop(responseTimeBuilder);

		// Always take the remote address from the operations in order to consider proxy information
		recorder().recordDataSent(ops.remoteAddress(), path, dataSent);

		dataSentTimeSample = null;
		responseTimeHandlerContext = null;
		responseTimeSample = null;
	}

	@Override
	protected void startRead(String path, String method) {
		dataReceivedTimeSample = Timer.start(REGISTRY, new ReadHandlerContext(method, path));

		responseTimeHandlerContext = new WriteHandlerContext(method, path);
		responseTimeSample = Timer.start(REGISTRY, responseTimeHandlerContext);
	}

	@Override
	protected void startWrite(String path, String method, String status) {
		dataSentTimeSample = Timer.start(REGISTRY, new WriteHandlerContext(method, path, status));

		if (responseTimeSample == null) {
			responseTimeSample = Timer.start(REGISTRY, new WriteHandlerContext(method, path, status));
		}
		else {
			responseTimeHandlerContext.status = status;
		}
	}

	static final class ReadHandlerContext extends Timer.HandlerContext {

		final String method;
		final String path;

		ReadHandlerContext(String method, String path) {
			this.method = method;
			this.path = path;
		}

		@Override
		public Tags getLowCardinalityTags() {
			return Tags.of(URI, path, METHOD, method);
		}
	}

	static final class WriteHandlerContext extends Timer.HandlerContext {

		final String method;
		final String path;

		// status might not be known beforehand
		String status;

		WriteHandlerContext(String method, String path) {
			this(method, path, null);
		}

		WriteHandlerContext(String method, String path, @Nullable String status) {
			this.method = method;
			this.path = path;
			this.status = status;
		}

		@Override
		public Tags getLowCardinalityTags() {
			return Tags.of(URI, path, METHOD, method, STATUS, status);
		}
	}
}
