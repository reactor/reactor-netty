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
import io.micrometer.core.instrument.tracing.context.HttpClientHandlerContext;
import io.micrometer.core.instrument.transport.http.HttpClientRequest;
import io.micrometer.core.instrument.transport.http.HttpClientResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import reactor.netty.Metrics;
import reactor.netty.observability.ReactorNettyHandlerContext;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collection;
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

	WriteHandlerContext responseTimeHandlerContext;
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
		dataReceivedTimeBuilder
//				.tags()
				.register(REGISTRY)
				.record(Duration.ofNanos(dataReceivedTime));

//		responseTimeHandlerContext.status = status;
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
		dataSentTimeBuilder
				.register(REGISTRY)
				.record(Duration.ofNanos(dataSentTime));

		recorder().recordDataSent(address, path, dataSent);
	}

	@Override
	protected void reset() {
		super.reset();
		responseTimeHandlerContext = null;
		responseTimeSample = null;
	}

	// reading the response
	@Override
	protected void startRead(HttpResponse msg, SocketAddress address) {
		responseTimeHandlerContext.setResponse(new HttpClientResponse() {
			@Override
			public int statusCode() {
				return msg.status().code();
			}

			@Override
			public Collection<String> headerNames() {
				return msg.headers().names();
			}

			@Override
			public Object unwrap() {
				return msg;
			}
		});
		dataReceivedTime = System.nanoTime();
	}

	// writing the request
	@Override
	protected void startWrite(HttpRequest msg, SocketAddress address) {
		HttpClientRequest httpClientRequest = new HttpClientRequest() {
			@Override
			public void header(String name, String value) {
				msg.headers().set(name, value);
			}

			@Override
			public String method() {
				return msg.method().name();
			}

			@Override
			public String path() {
				// TODO: Resource consuming?
				return java.net.URI.create(msg.uri()).getPath();
			}

			@Override
			public String url() {
				return msg.uri();
			}

			@Override
			public String header(String name) {
				return msg.headers().get(name);
			}

			@Override
			public Collection<String> headerNames() {
				return msg.headers().names();
			}

			@Override
			public Object unwrap() {
				return msg;
			}
		};
		responseTimeHandlerContext = new WriteHandlerContext(httpClientRequest, address);
		responseTimeSample = Timer.start(REGISTRY, responseTimeHandlerContext);
	}

	static final class ReadHandlerContext extends Timer.HandlerContext implements ReactorNettyHandlerContext {

		final String method;
		final String path;
		final String remoteAddress;

		// status might not be known beforehand
		String status;

		ReadHandlerContext(String method, String path, SocketAddress remoteAddress) {
			this(method, path, remoteAddress, null);
		}

		ReadHandlerContext(String method, String path, SocketAddress remoteAddress, @Nullable String status) {
			this.method = method;
			this.path = path;
			this.remoteAddress = formatSocketAddress(remoteAddress);
			this.status = status;
			put(SocketAddress.class, remoteAddress);
		}

		@Override
		public Tags getHighCardinalityTags() {
			// TODO: Externalize the tags?
			return Tags.of("http.status", status, "reactor.netty.type", "client", "reactor.netty.protocol", "http");
		}

		@Override
		public Tags getLowCardinalityTags() {
			return Tags.of(REMOTE_ADDRESS, remoteAddress, URI, path, METHOD, method, STATUS, status);
		}

		@Override
		public String getSimpleName() {
			return method;
		}
	}

	static class WriteHandlerContext extends HttpClientHandlerContext implements ReactorNettyHandlerContext {

		final String method;
		final String path;
		final String remoteAddress;

		WriteHandlerContext(HttpClientRequest request, SocketAddress remoteAddress) {
			super(request);
			this.method = request.method();
			this.path = request.path();
			this.remoteAddress = formatSocketAddress(remoteAddress);
			put(HttpClientRequest.class, request);
			put(SocketAddress.class, remoteAddress);
		}

		@Override
		public HttpClientHandlerContext setResponse(HttpClientResponse response) {
			put(HttpClientResponse.class, response);
			return super.setResponse(response);
		}

		@Override
		public Tags getLowCardinalityTags() {
			return Tags.of(REMOTE_ADDRESS, remoteAddress, URI, path, METHOD, method);
		}

		@Override
		public String getSimpleName() {
			return "request data sent";
		}
	}

}
