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

import java.time.Duration;
import java.util.Collection;
import java.util.function.Function;

import io.micrometer.api.instrument.Tags;
import io.micrometer.api.instrument.observation.Observation;
import io.micrometer.api.instrument.transport.http.HttpServerRequest;
import io.micrometer.api.instrument.transport.http.HttpServerResponse;
import io.micrometer.api.instrument.transport.http.context.HttpServerHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import reactor.netty.observability.ReactorNettyHandlerContext;
import reactor.util.annotation.Nullable;

//import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.RESPONSE_TIME;

/**
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
final class MicrometerHttpServerMetricsHandler extends AbstractHttpServerMetricsHandler {
	static final io.micrometer.api.instrument.MeterRegistry REGISTRY = io.micrometer.api.instrument.Metrics.globalRegistry;

	final MicrometerHttpServerMetricsRecorder recorder;
	final String responseTimeName;

	WriteHandlerContext responseTimeHandlerContext;
	Observation responseTimeObservation;

	MicrometerHttpServerMetricsHandler(MicrometerHttpServerMetricsRecorder recorder,
			@Nullable Function<String, String> uriTagValue) {
		super(uriTagValue);
		this.recorder = recorder;
		this.responseTimeName = recorder.name() + RESPONSE_TIME;
	}

	@Override
	protected HttpServerMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	protected void recordWrite(HttpServerOperations ops, String path, String method, String status) {
		Duration dataSentTimeDuration = Duration.ofNanos(System.nanoTime() - dataSentTime);
		recorder().recordDataSentTime(path, method, status, dataSentTimeDuration);

		// Always take the remote address from the operations in order to consider proxy information
		recorder().recordDataSent(ops.remoteAddress(), path, dataSent);

		// TODO
		// Cannot invoke the recorder any more:
		// 1. The recorder is one instance only, it is invoked for all requests that can happen
		// 2. The recorder does not have knowledge about request lifecycle
		//
		// Move the implementation from the recorder here
		//
		// Important:
		// Cannot cache the Timer anymore - need to test the performance
		responseTimeObservation.stop();

		responseTimeHandlerContext = null;
		responseTimeObservation = null;
	}

	@Override
	protected void startRead(HttpServerOperations ops, String path, String method) {
		super.startRead(ops, path, method);

		responseTimeHandlerContext = new WriteHandlerContext(
				new ObservationHttpServerRequest(ops.nettyRequest, method, path), recorder.protocol());
		responseTimeObservation = Observation.start(this.responseTimeName, responseTimeHandlerContext, REGISTRY);
	}

	// response
	@Override
	protected void startWrite(HttpServerOperations ops, String path, String method, String status) {
		super.startWrite(ops, path, method, status);

		if (responseTimeObservation == null) {
			responseTimeHandlerContext = new WriteHandlerContext(
					new ObservationHttpServerRequest(ops.nettyRequest, method, path), recorder.protocol());
			responseTimeObservation = Observation.start(this.responseTimeName, responseTimeHandlerContext, REGISTRY);
		}
		responseTimeHandlerContext.setResponse(new ObservationHttpServerResponse(ops.nettyResponse));
		responseTimeHandlerContext.status = status;
	}

	static final class ObservationHttpServerRequest implements HttpServerRequest {

		final String method;
		final HttpRequest nettyRequest;
		final String path;

		ObservationHttpServerRequest(HttpRequest nettyRequest, String method, String path) {
			this.method = method;
			this.nettyRequest = nettyRequest;
			this.path = path;
		}

		@Override
		public String method() {
			return method;
		}

		@Override
		public String path() {
			return path;
		}

		@Override
		public String url() {
			return nettyRequest.uri();
		}

		@Override
		public String header(String name) {
			return nettyRequest.headers().get(name);
		}

		@Override
		public Collection<String> headerNames() {
			return nettyRequest.headers().names();
		}

		@Override
		public Object unwrap() {
			return nettyRequest;
		}
	}

	static final class ObservationHttpServerResponse implements HttpServerResponse {

		final HttpResponse nettyResponse;

		ObservationHttpServerResponse(HttpResponse nettyResponse) {
			this.nettyResponse = nettyResponse;
		}

		@Override
		public int statusCode() {
			return nettyResponse.status().code();
		}

		@Override
		public Collection<String> headerNames() {
			return nettyResponse.headers().names();
		}

		@Override
		public Object unwrap() {
			return nettyResponse;
		}
	}

	static final class WriteHandlerContext extends HttpServerHandlerContext implements ReactorNettyHandlerContext {
		static final String CONTEXTUAL_NAME = "request data received";
		static final String TYPE = "server";

		final String method;
		final String path;
		final String protocol;

		// status might not be known beforehand
		String status;

		WriteHandlerContext(HttpServerRequest request, String protocol) {
			super(request);
			this.method = request.method();
			this.path = request.path();
			this.protocol = protocol;
			put(HttpServerRequest.class, request);
		}

		@Override
		public String getContextualName() {
			return CONTEXTUAL_NAME;
		}

		@Override
		public Tags getHighCardinalityTags() {
			// TODO cache
			return Tags.of(HttpServerObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_STATUS.of(status),
			               HttpServerObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_TYPE.of(TYPE),
			               HttpServerObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_PROTOCOL.of(protocol));
		}

		@Override
		public Tags getLowCardinalityTags() {
			// TODO cache
			return Tags.of(HttpServerObservations.ResponseTimeLowCardinalityTags.URI.of(path),
			               HttpServerObservations.ResponseTimeLowCardinalityTags.METHOD.of(method),
			               HttpServerObservations.ResponseTimeLowCardinalityTags.STATUS.of(status));
		}

		@Override
		public HttpServerHandlerContext setResponse(HttpServerResponse response) {
			put(HttpServerResponse.class, response);
			return super.setResponse(response);
		}
	}
}
