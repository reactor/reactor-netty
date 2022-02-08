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
package reactor.netty.http.client;

import io.micrometer.api.instrument.Tags;
import io.micrometer.api.instrument.observation.Observation;
import io.micrometer.api.instrument.transport.http.HttpClientRequest;
import io.micrometer.api.instrument.transport.http.HttpClientResponse;
import io.micrometer.api.instrument.transport.http.context.HttpClientHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import reactor.netty.observability.ReactorNettyHandlerContext;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.function.Function;

//import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.formatSocketAddress;

/**
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
final class MicrometerHttpClientMetricsHandler extends AbstractHttpClientMetricsHandler {
	static final io.micrometer.api.instrument.MeterRegistry REGISTRY = io.micrometer.api.instrument.Metrics.globalRegistry;

	final MicrometerHttpClientMetricsRecorder recorder;

	ReadHandlerContext responseTimeHandlerContext;
	Observation responseTimeObservation;

	MicrometerHttpClientMetricsHandler(MicrometerHttpClientMetricsRecorder recorder,
			@Nullable Function<String, String> uriTagValue) {
		super(uriTagValue);
		this.recorder = recorder;
	}

	@Override
	protected HttpClientMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	protected void recordRead(SocketAddress address) {
		recorder().recordDataReceivedTime(address,
				path, method, status,
				Duration.ofNanos(System.nanoTime() - dataReceivedTime));

		recorder().recordDataReceived(address, path, dataReceived);

		// TODO
		// Cannot invoke the recorder any more:
		// 1. The recorder is one instance only, it is invoked for all requests that can happen
		// 2. The recorder does not have knowledge about request lifecycle
		//
		// Move the implementation from the recorder here
		//
		// Important:
		// Cannot cache the Timer anymore - need to test the performance
		responseTimeHandlerContext.status = status;
		responseTimeObservation.stop();
	}

	@Override
	protected void reset() {
		super.reset();
		responseTimeHandlerContext = null;
		responseTimeObservation = null;
	}

	// reading the response
	@Override
	protected void startRead(HttpResponse msg, SocketAddress address) {
		super.startRead(msg, address);

		responseTimeHandlerContext.setResponse(new ObservationHttpClientResponse(msg));
	}

	// writing the request
	@Override
	protected void startWrite(HttpRequest msg, SocketAddress address) {
		super.startWrite(msg, address);

		HttpClientRequest httpClientRequest = new ObservationHttpClientRequest(msg, method, path);
		responseTimeHandlerContext = new ReadHandlerContext(httpClientRequest, address, recorder.protocol());
		responseTimeObservation = Observation.start(recorder.name() + RESPONSE_TIME, responseTimeHandlerContext, REGISTRY);
	}

	static final class ObservationHttpClientRequest implements io.micrometer.api.instrument.transport.http.HttpClientRequest {

		final String method;
		final HttpRequest nettyRequest;
		final String path;

		ObservationHttpClientRequest(HttpRequest nettyRequest, String method, String path) {
			this.method = method;
			this.nettyRequest = nettyRequest;
			this.path = path;
		}

		@Override
		public void header(String name, String value) {
			nettyRequest.headers().set(name, value);
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

	static final class ObservationHttpClientResponse implements io.micrometer.api.instrument.transport.http.HttpClientResponse {

		final HttpResponse nettyResponse;

		ObservationHttpClientResponse(HttpResponse nettyResponse) {
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

	static final class ReadHandlerContext extends HttpClientHandlerContext implements ReactorNettyHandlerContext {
		static final String CONTEXTUAL_NAME = "request data sent";
		static final String TYPE = "client";

		final String method;
		final String path;
		final String protocol;
		final String remoteAddress;

		// status might not be known beforehand
		String status;

		ReadHandlerContext(HttpClientRequest request, SocketAddress remoteAddress, String protocol) {
			super(request);
			this.method = request.method();
			this.path = request.path();
			this.protocol = protocol;
			this.remoteAddress = formatSocketAddress(remoteAddress);
			put(HttpClientRequest.class, request);
			put(SocketAddress.class, remoteAddress);
		}

		@Override
		public String getContextualName() {
			return CONTEXTUAL_NAME;
		}

		@Override
		public Tags getHighCardinalityTags() {
			// TODO cache
			return Tags.of(HttpClientObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_STATUS.of(status),
			               HttpClientObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_TYPE.of(TYPE),
			               HttpClientObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_PROTOCOL.of(protocol));
		}

		@Override
		public Tags getLowCardinalityTags() {
			return Tags.of(HttpClientObservations.ResponseTimeLowCardinalityTags.REMOTE_ADDRESS.of(remoteAddress),
			               HttpClientObservations.ResponseTimeLowCardinalityTags.URI.of(path),
			               HttpClientObservations.ResponseTimeLowCardinalityTags.METHOD.of(method),
			               HttpClientObservations.ResponseTimeLowCardinalityTags.STATUS.of(status));
		}

		@Override
		public HttpClientHandlerContext setResponse(HttpClientResponse response) {
			put(HttpClientResponse.class, response);
			return super.setResponse(response);
		}
	}
}
