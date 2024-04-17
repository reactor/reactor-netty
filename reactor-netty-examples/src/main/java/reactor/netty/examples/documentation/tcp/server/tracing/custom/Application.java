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
package reactor.netty.examples.documentation.tcp.server.tracing.custom;

import brave.Tracing;
import brave.propagation.StrictCurrentTraceContext;
import brave.sampler.Sampler;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.observability.ReactorNettyTracingObservationHandler;
import reactor.netty.tcp.TcpServer;
import reactor.netty.tcp.TcpSslContextSpec;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;
import zipkin2.reporter.urlconnection.URLConnectionSender;

import java.io.File;

import static reactor.netty.Metrics.OBSERVATION_REGISTRY;

public class Application {

	public static void main(String[] args) {
		init(); //<1>

		File cert = new File("certificate.crt");
		File key = new File("private.key");

		TcpSslContextSpec tcpSslContextSpec = TcpSslContextSpec.forServer(cert, key);

		DisposableServer server =
				TcpServer.create()
				         .metrics(true) //<2>
				         .doOnChannelInit((observer, channel, address) -> channel.pipeline().addAfter(
				                 NettyPipeline.SslHandler, "custom-channel-handler", CustomChannelInboundHandler.INSTANCE)) //<3>
				         .secure(spec -> spec.sslContext(tcpSslContextSpec))
				         .handle((inbound, outbound) -> outbound.sendString(Mono.just("hello")))
				         .bindNow();

		server.onDispose()
		      .block();
	}

	static final class CustomChannelInboundHandler extends ChannelInboundHandlerAdapter {

		static final ChannelHandler INSTANCE = new CustomChannelInboundHandler();

		@Override
		@SuppressWarnings("try")
		public void channelActive(ChannelHandlerContext ctx) {
			try (ContextSnapshot.Scope scope = ContextSnapshotFactory.builder().build().setThreadLocalsFrom(ctx.channel())) {
				System.out.println("Current Observation in Scope: " + OBSERVATION_REGISTRY.getCurrentObservation());
				ctx.fireChannelActive();
			}
			System.out.println("Current Observation: " + OBSERVATION_REGISTRY.getCurrentObservation());
		}

		@Override
		public boolean isSharable() {
			return true;
		}
	}

	/**
	 * This setup is based on
	 * <a href="https://micrometer.io/docs/tracing#_micrometer_tracing_brave_setup">Micrometer Tracing Brave Setup</a>.
	 */
	static void init() {
		AsyncZipkinSpanHandler spanHandler = AsyncZipkinSpanHandler
				.create(URLConnectionSender.create("http://localhost:9411/api/v2/spans"));

		StrictCurrentTraceContext braveCurrentTraceContext = StrictCurrentTraceContext.create();

		CurrentTraceContext bridgeContext = new BraveCurrentTraceContext(braveCurrentTraceContext);

		Tracing tracing =
				Tracing.newBuilder()
				       .currentTraceContext(braveCurrentTraceContext)
				       .supportsJoin(false)
				       .traceId128Bit(true)
				       .sampler(Sampler.ALWAYS_SAMPLE)
				       .addSpanHandler(spanHandler)
				       .localServiceName("reactor-netty-examples")
				       .build();

		brave.Tracer braveTracer = tracing.tracer();

		Tracer tracer = new BraveTracer(braveTracer, bridgeContext, new BraveBaggageManager());

		OBSERVATION_REGISTRY.observationConfig()
		                    .observationHandler(new ReactorNettyTracingObservationHandler(tracer));
	}
}