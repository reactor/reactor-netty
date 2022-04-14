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

import io.micrometer.common.KeyValues;
import io.micrometer.contextpropagation.ContextContainer;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.transport.http.HttpClientRequest;
import io.micrometer.observation.transport.http.HttpClientResponse;
import io.micrometer.observation.transport.http.context.HttpClientContext;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import reactor.netty.observability.ReactorNettyHandlerContext;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.function.Function;

import static reactor.netty.Metrics.OBSERVATION_REGISTRY;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.formatSocketAddress;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_PROTOCOL;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_STATUS;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_TYPE;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeLowCardinalityTags.METHOD;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeLowCardinalityTags.REMOTE_ADDRESS;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeLowCardinalityTags.STATUS;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeLowCardinalityTags.URI;

/**
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
final class MicrometerHttpClientMetricsHandler extends AbstractHttpClientMetricsHandler {
	final MicrometerHttpClientMetricsRecorder recorder;

	ResponseTimeHandlerContext responseTimeHandlerContext;
	Observation responseTimeObservation;

	MicrometerHttpClientMetricsHandler(MicrometerHttpClientMetricsRecorder recorder,
			@Nullable Function<String, String> uriTagValue) {
		super(uriTagValue);
		this.recorder = recorder;
	}

	MicrometerHttpClientMetricsHandler(MicrometerHttpClientMetricsHandler copy) {
		super(copy);
		this.recorder = copy.recorder;

		this.responseTimeHandlerContext = copy.responseTimeHandlerContext;
		this.responseTimeObservation = copy.responseTimeObservation;
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

		// Cannot invoke the recorder anymore:
		// 1. The recorder is one instance only, it is invoked for all requests that can happen
		// 2. The recorder does not have knowledge about request lifecycle
		//
		// Move the implementation from the recorder here
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
	protected void startRead(HttpResponse msg) {
		super.startRead(msg);

		responseTimeHandlerContext.setResponse(new ObservationHttpClientResponse(msg));
	}

	// writing the request
	@Override
	@SuppressWarnings("try")
	protected void startWrite(HttpRequest msg, Channel channel) {
		super.startWrite(msg, channel);

		HttpClientRequest httpClientRequest = new ObservationHttpClientRequest(msg, method, path);
		responseTimeHandlerContext = new ResponseTimeHandlerContext(recorder, httpClientRequest, channel.remoteAddress());
		ContextContainer container = ContextContainer.restore(channel);
		try (ContextContainer.Scope scope = container.restoreThreadLocalValues()) {
			responseTimeObservation = Observation.start(recorder.name() + RESPONSE_TIME, responseTimeHandlerContext, OBSERVATION_REGISTRY);
		}
	}

	static final class ObservationHttpClientRequest implements HttpClientRequest {

		final String method;
		final HttpRequest nettyRequest;
		final String path;

		ObservationHttpClientRequest(HttpRequest nettyRequest, String method, String path) {
			this.method = method;
			this.nettyRequest = nettyRequest;
			this.path = path;
		}

		@Override
		public String header(String name) {
			return nettyRequest.headers().get(name);
		}

		@Override
		public void header(String name, String value) {
			nettyRequest.headers().set(name, value);
		}

		@Override
		public Collection<String> headerNames() {
			return nettyRequest.headers().names();
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
		public Object unwrap() {
			return nettyRequest;
		}

		@Override
		public String url() {
			return nettyRequest.uri();
		}
	}

	static final class ObservationHttpClientResponse implements HttpClientResponse {

		final HttpResponse nettyResponse;

		ObservationHttpClientResponse(HttpResponse nettyResponse) {
			this.nettyResponse = nettyResponse;
		}

		@Override
		public Collection<String> headerNames() {
			return nettyResponse.headers().names();
		}

		@Override
		public int statusCode() {
			return nettyResponse.status().code();
		}

		@Override
		public Object unwrap() {
			return nettyResponse;
		}
	}

	static final class ResponseTimeHandlerContext extends HttpClientContext implements ReactorNettyHandlerContext {
		static final String TYPE = "client";

		final String method;
		final String path;
		final String remoteAddress;
		final MicrometerHttpClientMetricsRecorder recorder;

		// status might not be known beforehand
		String status;

		ResponseTimeHandlerContext(MicrometerHttpClientMetricsRecorder recorder, HttpClientRequest request, SocketAddress remoteAddress) {
			super(request);
			this.recorder = recorder;
			this.method = request.method();
			this.path = request.path();
			this.remoteAddress = formatSocketAddress(remoteAddress);
			put(HttpClientRequest.class, request);
		}

		@Override
		public Timer getTimer() {
			return recorder.getResponseTimeTimer(getName(), remoteAddress, path, method, status);
		}

		@Override
		public KeyValues getHighCardinalityKeyValues() {
			return KeyValues.of(REACTOR_NETTY_PROTOCOL.getKeyName(), recorder.protocol(),
					REACTOR_NETTY_STATUS.getKeyName(), status, REACTOR_NETTY_TYPE.getKeyName(), TYPE);
		}

		@Override
		public KeyValues getLowCardinalityKeyValues() {
			return KeyValues.of(METHOD.getKeyName(), method, REMOTE_ADDRESS.getKeyName(), remoteAddress,
					STATUS.getKeyName(), status, URI.getKeyName(), path);
		}

		@Override
		public HttpClientContext setResponse(HttpClientResponse response) {
			put(HttpClientResponse.class, response);
			return super.setResponse(response);
		}
	}
}
