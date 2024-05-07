/*
 * Copyright (c) 2022-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import static reactor.netty.Metrics.NA;
import static reactor.netty.Metrics.OBSERVATION_REGISTRY;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.UNKNOWN;
import static reactor.netty.Metrics.formatSocketAddress;
import static reactor.netty.Metrics.updateChannelContext;
import static reactor.netty.ReactorNetty.setChannelContext;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeHighCardinalityTags.HTTP_STATUS_CODE;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeHighCardinalityTags.HTTP_URL;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeHighCardinalityTags.NET_PEER_NAME;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeHighCardinalityTags.NET_PEER_PORT;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeHighCardinalityTags.REACTOR_NETTY_TYPE;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeLowCardinalityTags.METHOD;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeLowCardinalityTags.PROXY_ADDRESS;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeLowCardinalityTags.REMOTE_ADDRESS;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeLowCardinalityTags.STATUS;
import static reactor.netty.http.client.HttpClientObservations.ResponseTimeLowCardinalityTags.URI;

/**
 * {@link AbstractHttpClientMetricsHandler} for Reactor Netty built-in integration with Micrometer.
 *
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
final class MicrometerHttpClientMetricsHandler extends AbstractHttpClientMetricsHandler {
	final MicrometerHttpClientMetricsRecorder recorder;

	ResponseTimeHandlerContext responseTimeHandlerContext;
	Observation responseTimeObservation;
	ContextView parentContextView;

	MicrometerHttpClientMetricsHandler(MicrometerHttpClientMetricsRecorder recorder,
			SocketAddress remoteAddress,
			@Nullable SocketAddress proxyAddress,
			@Nullable Function<String, String> uriTagValue) {
		super(remoteAddress, proxyAddress, uriTagValue);
		this.recorder = recorder;
	}

	MicrometerHttpClientMetricsHandler(MicrometerHttpClientMetricsHandler copy) {
		super(copy);
		this.recorder = copy.recorder;

		this.responseTimeHandlerContext = copy.responseTimeHandlerContext;
		this.responseTimeObservation = copy.responseTimeObservation;
		this.parentContextView = copy.parentContextView;
	}

	@Override
	protected HttpClientMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	protected void recordRead(Channel channel, SocketAddress address) {
		if (proxyAddress == null) {
			recorder().recordDataReceivedTime(address, path, method, status,
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));

			recorder().recordDataReceived(address, path, dataReceived);
		}
		else {
			recorder().recordDataReceivedTime(address, proxyAddress, path, method, status,
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));

			recorder().recordDataReceived(address, proxyAddress, path, dataReceived);
		}

		// Cannot invoke the recorder anymore:
		// 1. The recorder is one instance only, it is invoked for all requests that can happen
		// 2. The recorder does not have knowledge about request lifecycle
		//
		// Move the implementation from the recorder here
		responseTimeObservation.stop();

		setChannelContext(channel, parentContextView);
	}

	@Override
	protected void reset() {
		super.reset();
		responseTimeHandlerContext = null;
		responseTimeObservation = null;
		parentContextView = null;
	}

	// reading the response
	@Override
	protected void startRead(HttpResponse msg) {
		super.startRead(msg);

		responseTimeHandlerContext.setResponse(msg);
		responseTimeHandlerContext.status = status;
	}

	// writing the request
	@Override
	protected void startWrite(HttpRequest msg, Channel channel, SocketAddress address) {
		super.startWrite(msg, channel, address);

		responseTimeHandlerContext = new ResponseTimeHandlerContext(recorder, msg, path, address, proxyAddress);
		responseTimeObservation = Observation.createNotStarted(recorder.name() + RESPONSE_TIME, responseTimeHandlerContext, OBSERVATION_REGISTRY);
		parentContextView = updateChannelContext(channel, responseTimeObservation);
		responseTimeObservation.start();
	}

	/*
	 * Requirements for HTTP clients
	 * <p>https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/http.md#name
	 * The contextual name must be in the format 'HTTP &lt;method&gt;'
	 * <p>https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/http.md#common-attributes
	 * <p>https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/http.md#http-client
	 */
	static final class ResponseTimeHandlerContext extends RequestReplySenderContext<HttpRequest, HttpResponse>
			implements ReactorNettyHandlerContext, Supplier<Observation.Context> {
		static final String HTTP_PREFIX = "http ";
		static final String TYPE = "client";

		final String method;
		final String netPeerName;
		final String netPeerPort;
		final String path;
		final String proxyAddress;
		final MicrometerHttpClientMetricsRecorder recorder;

		// status might not be known beforehand
		String status = UNKNOWN;

		ResponseTimeHandlerContext(MicrometerHttpClientMetricsRecorder recorder, HttpRequest request, String path,
				SocketAddress remoteAddress, @Nullable SocketAddress proxyAddress) {
			super((carrier, key, value) -> Objects.requireNonNull(carrier).headers().set(key, value));
			this.recorder = recorder;
			this.method = request.method().name();
			if (remoteAddress instanceof InetSocketAddress) {
				InetSocketAddress address = (InetSocketAddress) remoteAddress;
				this.netPeerName = address.getHostString();
				this.netPeerPort = address.getPort() + "";
			}
			else {
				this.netPeerName = remoteAddress.toString();
				this.netPeerPort = "";
			}
			this.path = path;
			this.proxyAddress = formatSocketAddress(proxyAddress);
			setCarrier(request);
			setContextualName(HTTP_PREFIX + this.method);
		}

		@Override
		public Observation.Context get() {
			return this;
		}

		@Override
		public Timer getTimer() {
			return recorder.getResponseTimeTimer(getName(), netPeerName + ":" + netPeerPort, proxyAddress == null ? NA : proxyAddress, path, method, status);
		}

		@Override
		public KeyValues getHighCardinalityKeyValues() {
			return KeyValues.of(REACTOR_NETTY_TYPE.asString(), TYPE,
					HTTP_URL.asString(), path, HTTP_STATUS_CODE.asString(), status,
					NET_PEER_NAME.asString(), netPeerName, NET_PEER_PORT.asString(), netPeerPort);
		}

		@Override
		public KeyValues getLowCardinalityKeyValues() {
			return KeyValues.of(METHOD.asString(), method, REMOTE_ADDRESS.asString(), netPeerName + ":" + netPeerPort,
					PROXY_ADDRESS.asString(), proxyAddress == null ? NA : proxyAddress,
					STATUS.asString(), status, URI.asString(), path);
		}
	}
}
