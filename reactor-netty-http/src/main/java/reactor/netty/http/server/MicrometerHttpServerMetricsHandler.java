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
import io.micrometer.core.instrument.tracing.context.HttpServerHandlerContext;
import io.micrometer.core.instrument.transport.http.HttpServerRequest;
import io.micrometer.core.instrument.transport.http.HttpServerResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import reactor.netty.observability.ReactorNettyHandlerContext;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Function;

import static reactor.netty.Metrics.*;

/**
 * @author Violeta Georgieva
 * @since 1.1.0
 */
final class MicrometerHttpServerMetricsHandler extends AbstractHttpServerMetricsHandler {

	final Timer.Builder dataReceivedTimeBuilder;
	final Timer.Builder dataSentTimeBuilder;
	final MicrometerHttpServerMetricsRecorder recorder;
	final Timer.Builder responseTimeBuilder;

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
		dataReceivedTimeBuilder.register(REGISTRY).record(Duration.ofNanos(dataReceivedTime));

		// Always take the remote address from the operations in order to consider proxy information
		recorder().recordDataReceived(ops.remoteAddress(), path, dataReceived);
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
		dataSentTimeBuilder.register(REGISTRY).record(Duration.ofNanos(dataSentTime));

		responseTimeSample.stop(responseTimeBuilder);

		// Always take the remote address from the operations in order to consider proxy information
		recorder().recordDataSent(ops.remoteAddress(), path, dataSent);

		responseTimeHandlerContext = null;
		responseTimeSample = null;
	}

	@Override
	protected void startRead(String path, String method, HttpRequest ops) {
		responseTimeHandlerContext = new WriteHandlerContext(toHttpServerRequest(ops));
		responseTimeSample = Timer.start(REGISTRY, responseTimeHandlerContext);
	}

	private HttpServerRequest toHttpServerRequest(HttpRequest ops) {
		return new HttpServerRequest() {
			@Override
			public String method() {
				return ops.method().name();
			}

			@Override
			public String path() {
				return java.net.URI.create(ops.uri()).getPath();
			}

			@Override
			public String url() {
				return ops.uri();
			}

			@Override
			public String header(String name) {
				return ops.headers().get(name);
			}

			@Override
			public Collection<String> headerNames() {
				return ops.headers().names();
			}

			@Override
			public Object unwrap() {
				return ops;
			}
		};
	}

	// response
	@Override
	protected void startWrite(String path, String method, String status, HttpRequest nettyRequest, HttpResponse ops) {
		if (responseTimeSample == null) {
			responseTimeSample = Timer.start(REGISTRY, responseTimeHandlerContext);
		}
		responseTimeHandlerContext.setResponse(toHttpServerResponse(ops));
		responseTimeHandlerContext.status = status;
		dataSentTimeBuilder
				.register(REGISTRY)
				.record(Duration.ofNanos(dataSentTime));
	}

	private HttpServerResponse toHttpServerResponse(HttpResponse ops) {
		return new HttpServerResponse() {
			@Override
			public int statusCode() {
				return ops.status().code();
			}

			@Override
			public Collection<String> headerNames() {
				return ops.headers().names();
			}

			@Override
			public Object unwrap() {
				return ops;
			}
		};
	}

	static final class ReadHandlerContext extends Timer.HandlerContext implements ReactorNettyHandlerContext {

		final String method;
		final String path;

		ReadHandlerContext(String method, String path) {
			this.method = method;
			this.path = path;
		}

		@Override
		public Tags getHighCardinalityTags() {
			// TODO: Externalize the tags?
			return Tags.of("reactor.netty.type", "server", "reactor.netty.protocol", "http");
		}

		@Override
		public Tags getLowCardinalityTags() {
			return Tags.of(URI, path, METHOD, method);
		}

		@Override
		public String getSimpleName() {
			return method + " " + path;
		}
	}

	static class WriteHandlerContext extends HttpServerHandlerContext implements ReactorNettyHandlerContext {

		// status might not be known beforehand
		String status;

		final String method;
		final String path;

		WriteHandlerContext(HttpServerRequest request) {
			super(request);
			this.method = request.method();
			this.path = request.path();
			put(HttpServerRequest.class, request);
		}

		@Override
		public HttpServerHandlerContext setResponse(io.micrometer.core.instrument.transport.http.HttpServerResponse response) {
			put(io.micrometer.core.instrument.transport.http.HttpServerResponse.class, response);
			return super.setResponse(response);
		}

		@Override
		public Tags getLowCardinalityTags() {
			return Tags.of(URI, path, METHOD, method, STATUS, status);
		}

		@Override
		public String getSimpleName() {
			return "request data received";
		}
	}
}
