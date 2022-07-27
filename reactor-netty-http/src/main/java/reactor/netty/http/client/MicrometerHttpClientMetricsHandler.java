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
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.transport.RequestReplySenderContext;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import reactor.netty.observability.ReactorNettyHandlerContext;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

import static reactor.netty.Metrics.OBSERVATION_KEY;
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

		responseTimeHandlerContext.setResponse(msg);
	}

	// writing the request
	@Override
	@SuppressWarnings("try")
	protected void startWrite(HttpRequest msg, Channel channel, @Nullable ContextView contextView) {
		super.startWrite(msg, channel, contextView);

		responseTimeHandlerContext = new ResponseTimeHandlerContext(recorder, msg, path, channel.remoteAddress());
		responseTimeObservation = Observation.createNotStarted(recorder.name() + RESPONSE_TIME, responseTimeHandlerContext, OBSERVATION_REGISTRY);
		if (contextView != null && contextView.hasKey(OBSERVATION_KEY)) {
			responseTimeObservation.parentObservation(contextView.get(OBSERVATION_KEY));
		}
		responseTimeObservation.start();
	}

	static final class ResponseTimeHandlerContext extends RequestReplySenderContext<HttpRequest, HttpResponse>
			implements ReactorNettyHandlerContext {
		static final String TYPE = "client";

		final String method;
		final String path;
		final String remoteAddress;
		final MicrometerHttpClientMetricsRecorder recorder;

		// status might not be known beforehand
		String status;

		ResponseTimeHandlerContext(MicrometerHttpClientMetricsRecorder recorder, HttpRequest request, String path, SocketAddress remoteAddress) {
			super((carrier, key, value) -> Objects.requireNonNull(carrier).headers().set(key, value));
			this.recorder = recorder;
			this.method = request.method().name();
			this.path = path;
			this.remoteAddress = formatSocketAddress(remoteAddress);
			put(HttpClientRequest.class, request);
			setCarrier(request);
			setContextualName(this.method);
		}

		@Override
		public Timer getTimer() {
			return recorder.getResponseTimeTimer(getName(), remoteAddress, path, method, status);
		}

		@Override
		public KeyValues getHighCardinalityKeyValues() {
			return KeyValues.of(REACTOR_NETTY_PROTOCOL.asString(), recorder.protocol(),
					REACTOR_NETTY_STATUS.asString(), status, REACTOR_NETTY_TYPE.asString(), TYPE);
		}

		@Override
		public KeyValues getLowCardinalityKeyValues() {
			return KeyValues.of(METHOD.asString(), method, REMOTE_ADDRESS.asString(), remoteAddress,
					STATUS.asString(), status, URI.asString(), path);
		}

		@Override
		public void setResponse(HttpResponse response) {
			put(HttpClientResponse.class, response);
			super.setResponse(response);
		}
	}
}
