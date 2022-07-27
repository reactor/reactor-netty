/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.transport.RequestReplyReceiverContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import reactor.netty.observability.ReactorNettyHandlerContext;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

import static reactor.netty.Metrics.OBSERVATION_REGISTRY;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.http.server.HttpServerObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_PROTOCOL;
import static reactor.netty.http.server.HttpServerObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_STATUS;
import static reactor.netty.http.server.HttpServerObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_TYPE;
import static reactor.netty.http.server.HttpServerObservations.ResponseTimeLowCardinalityTags.METHOD;
import static reactor.netty.http.server.HttpServerObservations.ResponseTimeLowCardinalityTags.STATUS;
import static reactor.netty.http.server.HttpServerObservations.ResponseTimeLowCardinalityTags.URI;

/**
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
final class MicrometerHttpServerMetricsHandler extends AbstractHttpServerMetricsHandler {
	final MicrometerHttpServerMetricsRecorder recorder;
	final String responseTimeName;

	ResponseTimeHandlerContext responseTimeHandlerContext;
	Observation responseTimeObservation;

	MicrometerHttpServerMetricsHandler(MicrometerHttpServerMetricsRecorder recorder,
			@Nullable Function<String, String> uriTagValue) {
		super(uriTagValue);
		this.recorder = recorder;
		this.responseTimeName = recorder.name() + RESPONSE_TIME;
	}

	MicrometerHttpServerMetricsHandler(MicrometerHttpServerMetricsHandler copy) {
		super(copy);
		this.recorder = copy.recorder;
		this.responseTimeName = copy.responseTimeName;

		this.responseTimeHandlerContext = copy.responseTimeHandlerContext;
		this.responseTimeObservation = copy.responseTimeObservation;
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

		// Cannot invoke the recorder anymore:
		// 1. The recorder is one instance only, it is invoked for all requests that can happen
		// 2. The recorder does not have knowledge about request lifecycle
		//
		// Move the implementation from the recorder here
		responseTimeObservation.stop();

		responseTimeHandlerContext = null;
		responseTimeObservation = null;
	}

	@Override
	protected void startRead(HttpServerOperations ops, String path, String method) {
		super.startRead(ops, path, method);

		responseTimeHandlerContext = new ResponseTimeHandlerContext(recorder, path, ops.nettyRequest);
		responseTimeObservation = Observation.start(this.responseTimeName, responseTimeHandlerContext, OBSERVATION_REGISTRY);
	}

	// response
	@Override
	protected void startWrite(HttpServerOperations ops, String path, String method, String status) {
		super.startWrite(ops, path, method, status);

		if (responseTimeObservation == null) {
			responseTimeHandlerContext = new ResponseTimeHandlerContext(recorder, path, ops.nettyRequest);
			responseTimeObservation = Observation.start(this.responseTimeName, responseTimeHandlerContext, OBSERVATION_REGISTRY);
		}
		responseTimeHandlerContext.setResponse(ops.nettyResponse); // TODO: Is this OK?
		responseTimeHandlerContext.status = status;
	}

	static final class ResponseTimeHandlerContext extends RequestReplyReceiverContext<HttpRequest, HttpResponse>
			implements ReactorNettyHandlerContext {
		static final String TYPE = "server";

		final String method;
		final String path;
		final MicrometerHttpServerMetricsRecorder recorder;

		// status might not be known beforehand
		String status;

		ResponseTimeHandlerContext(MicrometerHttpServerMetricsRecorder recorder, String path, HttpRequest request) {
			super((carrier, key) -> Objects.requireNonNull(carrier).headers().get(key));
			this.recorder = recorder;
			this.method = request.method().name();
			this.path = path;
			put(HttpServerRequest.class, request);
			setCarrier(request);
			setContextualName(this.method);
		}

		@Override
		public Timer getTimer() {
			return recorder.getResponseTimeTimer(getName(), path, method, status);
		}

		@Override
		public KeyValues getHighCardinalityKeyValues() {
			return KeyValues.of(REACTOR_NETTY_PROTOCOL.asString(), recorder.protocol(),
					REACTOR_NETTY_STATUS.asString(), status, REACTOR_NETTY_TYPE.asString(), TYPE);
		}

		@Override
		public KeyValues getLowCardinalityKeyValues() {
			return KeyValues.of(METHOD.asString(), method, STATUS.asString(), status, URI.asString(), path);
		}

		@Override
		public void setResponse(HttpResponse response) {
			super.setResponse(response);
			put(HttpServerResponse.class, response);
		}
	}
}
