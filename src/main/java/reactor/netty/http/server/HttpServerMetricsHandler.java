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
package reactor.netty.http.server;

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
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import reactor.netty.Metrics;
import reactor.netty.channel.ChannelOperations;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static reactor.netty.Metrics.*;

/**
 * @author Violeta Georgieva
 */
final class HttpServerMetricsHandler extends ChannelDuplexHandler {

	long dataReceived;

	long dataSent;


	Timer.Sample dataReceivedTimeSample;

	Timer.Sample dataSentTimeSample;


	final MeterRegistry registry;

	final String name;

	final Timer.Builder dataReceivedTimeBuilder;

	final Timer.Builder dataSentTimeBuilder;

	final Timer.Builder responseTimeBuilder;

	String uri;

	String method;

	HttpServerMetricsHandler(MeterRegistry registry, String name) {
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
		if (msg instanceof HttpResponse) {
			if (((HttpResponse) msg).status().equals(HttpResponseStatus.CONTINUE)) {
				ctx.write(msg, promise);
				return;
			}

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
				ChannelOperations channelOps = ChannelOperations.get(ctx.channel());
				if (channelOps instanceof HttpServerOperations) {
					HttpServerOperations ops = (HttpServerOperations) channelOps;
					dataSentTimeSample.stop(dataSentTimeBuilder.tags(URI, ops.uri(),
					                                                 METHOD, ops.method().name(),
					                                                 STATUS, ops.status().codeAsText().toString())
					                                           .register(registry));
					Timer responseTime = responseTimeBuilder.tags(URI, ops.uri(),
					                                              METHOD, ops.method().name(),
					                                              STATUS, ops.status().codeAsText().toString())
					                                        .register(registry);
					if (dataReceivedTimeSample != null) {
						dataReceivedTimeSample.stop(responseTime);
					}
					else {
						dataSentTimeSample.stop(responseTime);
					}
					DistributionSummary.builder(name + DATA_SENT)
					                   .baseUnit("bytes")
					                   .description("Amount of the data that is sent, in bytes")
					                   .tags(REMOTE_ADDRESS, Metrics.formatSocketAddress(ops.remoteAddress()), URI, ops.uri())
					                   .register(registry)
					                   .record(dataSent);

					dataSent = 0;
				}
			});
		}

		super.write(ctx, msg, promise);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof HttpRequest) {
			dataReceivedTimeSample = Timer.start(registry);
			HttpRequest request = (HttpRequest) msg;
			uri = request.uri();
			method = request.method()
			                .name();
		}

		if (msg instanceof ByteBufHolder) {
			dataReceived += ((ByteBufHolder) msg).content().readableBytes();
		}
		else if (msg instanceof ByteBuf) {
			dataReceived += ((ByteBuf) msg).readableBytes();
		}

		if (msg instanceof LastHttpContent) {
			Timer dataReceivedTime = dataReceivedTimeBuilder.tags(URI, uri, METHOD, method)
			                                                .register(registry);


			dataReceivedTimeSample.stop(dataReceivedTime);
				DistributionSummary.builder(name + DATA_RECEIVED)
				                   .baseUnit("bytes")
				                   .description("Amount of the data that is received, in bytes")
				                   .tags(REMOTE_ADDRESS, Metrics.formatSocketAddress(ctx.channel().remoteAddress()), URI, uri)
				                   .register(registry)
				                   .record(dataReceived);

				dataReceived = 0;
		}

		super.channelRead(ctx, msg);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {

		if (uri != null) {
			Counter.builder(name + ERRORS)
			       .description("Number of the errors that are occurred")
			       .tags(REMOTE_ADDRESS,
					       Metrics.formatSocketAddress(ctx.channel().remoteAddress()),
					       URI,
					       uri)
			       .register(registry)
			       .increment();
		}

		super.exceptionCaught(ctx, cause);
	}
}
