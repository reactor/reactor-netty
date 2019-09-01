/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.client;

import static reactor.netty.Metrics.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import reactor.netty.Metrics;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * @author Violeta Georgieva
 */
final class HttpClientMetricsHandler extends ChannelDuplexHandler {

	HttpRequest request;

	HttpResponse response;


	long dataReceived;

	long dataSent;


	Timer.Sample dataReceivedTimeSample;

	Timer.Sample dataSentTimeSample;


	final MeterRegistry registry;

	final String name;


	final Timer.Builder dataReceivedTimeBuilder;

	final Timer.Builder dataSentTimeBuilder;

	final Timer.Builder responseTimeBuilder;

	public HttpClientMetricsHandler(MeterRegistry registry, String name) {
		this.registry = registry;
		this.name = name;
		dataReceivedTimeBuilder = Timer.builder(name + DATA_RECEIVED_TIME)
		                               .description("Time that is spent in consuming incoming data");
		dataSentTimeBuilder = Timer.builder(name + DATA_SENT_TIME)
		                           .description("Time that is spent in sending outgoing data");
		responseTimeBuilder = Timer.builder(name + RESPONSE_TIME)
		                           .description("Total time for the request/response");
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if (msg instanceof HttpRequest) {
			request = (HttpRequest) msg;

			dataSentTimeSample = Timer.start(registry);
		}

		if (msg instanceof ByteBufHolder) {
			dataSent += ((ByteBufHolder) msg).content().readableBytes();
		}
		else if (msg instanceof ByteBuf) {
			dataSent += ((ByteBuf) msg).readableBytes();
		}

		if (msg instanceof LastHttpContent) {
			promise.addListener(future -> {
				String address = Metrics.formatSocketAddress(ctx.channel().remoteAddress());

				Timer dataSentTime =
						dataSentTimeBuilder.tags(REMOTE_ADDRESS, address,
						                         URI, request.uri(),
						                         METHOD, request.method().name())
						                   .register(registry);
				dataSentTimeSample.stop(dataSentTime);

				DistributionSummary.builder(name + DATA_SENT)
				                   .baseUnit("bytes")
				                   .description("Amount of the data that is sent, in bytes")
				                   .tags(REMOTE_ADDRESS, address, URI, request.uri())
				                   .register(registry)
				                   .record(dataSent);
			});
		}

		super.write(ctx, msg, promise);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof HttpResponse) {
			response = (HttpResponse) msg;

			dataReceivedTimeSample = Timer.start(registry);
		}

		if (msg instanceof ByteBufHolder) {
			dataReceived += ((ByteBufHolder) msg).content().readableBytes();
		}
		else if (msg instanceof ByteBuf) {
			dataReceived += ((ByteBuf) msg).readableBytes();
		}

		if (msg instanceof LastHttpContent) {
			String address = Metrics.formatSocketAddress(ctx.channel().remoteAddress());

			dataReceivedTimeSample.stop(dataReceivedTimeBuilder.tags(REMOTE_ADDRESS, address,
			                                                         URI, request.uri(),
			                                                         METHOD, request.method().name(),
			                                                         STATUS, response.status().codeAsText().toString())
			                                                   .register(registry));
			Timer responseTime =
					responseTimeBuilder.tags(REMOTE_ADDRESS, address,
					                         URI, request.uri(),
					                         METHOD, request.method().name(),
					                         STATUS, response.status().codeAsText().toString())
					                   .register(registry);
			dataSentTimeSample.stop(responseTime);
			DistributionSummary.builder(name + DATA_RECEIVED)
			                   .baseUnit("bytes")
			                   .description("Amount of the data that is received, in bytes")
			                   .tags(REMOTE_ADDRESS, address, URI, request.uri())
			                   .register(registry)
			                   .record(dataReceived);
			reset();
		}

		super.channelRead(ctx, msg);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		Counter.builder(name + ERRORS)
		       .description("Number of the errors that are occurred")
		       .tags(REMOTE_ADDRESS, Metrics.formatSocketAddress(ctx.channel().remoteAddress()), URI, request.uri())
		       .register(registry)
		       .increment();

		super.exceptionCaught(ctx, cause);
	}

	private void reset() {
		request = null;
		response = null;
		dataReceived = 0;
		dataSent = 0;
		dataReceivedTimeSample = null;
		dataSentTimeSample = null;
	}
}
