/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.http.Http2SslContextSpec;

import java.security.cert.CertificateException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.HttpProtocol.H2;

/**
 * This test class verifies {@link Http2ConnectionLivenessHandler}.
 *
 * @author raccoonback
 * @since 1.2.3
 */
class Http2ConnectionLivenessHandlerTest extends BaseHttpTest {

	static SelfSignedCertificate ssc;

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@Test
	void successReceiveResponse() {
		DisposableServer disposableServer = createServer()
				.protocol(H2)
				.secure(spec -> spec.sslContext(
						Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())
				))
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		String result = createClient(disposableServer::address)
				.protocol(H2)
				.secure(spec -> spec.sslContext(
						Http2SslContextSpec.forClient()
								.configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE))
				))
				.get()
				.uri("/")
				.responseSingle((resp, bytes) -> bytes.asString())
				.block();

		assertThat(result).isEqualTo("Test");
	}

	@Test
	void noPingCheckWhenNotConfigured() {
		Http2PingFrameHandler handler = new Http2PingFrameHandler();

		DisposableServer disposableServer = createServer()
				.protocol(H2)
				.maxKeepAliveRequests(1)
				.secure(spec -> spec.sslContext(
						Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())
				))
				.doOnChannelInit((connectionObserver, channel, remoteAddress) -> {
					Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forServer()
							.autoAckPingFrame(false)
							.autoAckSettingsFrame(true)
							.build();

					channel.pipeline().replace(NettyPipeline.HttpCodec, NettyPipeline.HttpCodec, http2FrameCodec);
					channel.pipeline().addLast(handler);
				})
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(H2)
				.keepAlive(true)
				.secure(spec -> spec.sslContext(
						Http2SslContextSpec.forClient()
								.configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE))
				))
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofMillis(100))
				.block();

		assertThat(handler.getReceivedPingTimes()).isEmpty();
		assertThat(channel.parent().isOpen()).isTrue();
	}

	@Test
	void closePingFrameIfDelayed() {
		Http2PingFrameHandler handler = new Http2PingFrameHandler(
				(ctx, frame) -> Mono.delay(Duration.ofMillis(150))
						.doOnNext(unUsed -> ctx.writeAndFlush(new DefaultHttp2PingFrame(frame.content(), true)))
						.subscribe()
		);

		DisposableServer disposableServer = createServer()
				.protocol(H2)
				.maxKeepAliveRequests(1)
				.secure(spec -> spec.sslContext(
						Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())
				))
				.doOnChannelInit((connectionObserver, channel, remoteAddress) -> {
					Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forServer()
							.autoAckPingFrame(false)
							.autoAckSettingsFrame(true)
							.build();

					channel.pipeline().replace(NettyPipeline.HttpCodec, NettyPipeline.HttpCodec, http2FrameCodec);
					channel.pipeline().addLast(handler);
				})
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(H2)
				.keepAlive(true)
				.secure(spec -> spec.sslContext(
						Http2SslContextSpec.forClient()
								.configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE))
				))
				.http2Settings(builder -> {
					builder.pingInterval(Duration.ofMillis(100));
				})
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofMillis(600))
				.block();

		assertThat(handler.getReceivedPingTimes()).hasSize(1);
		assertThat(channel.parent().isOpen()).isFalse();
	}

	@Test
	void ackPingFrameWithinInterval() {
		Http2PingFrameHandler handler = new Http2PingFrameHandler();

		DisposableServer disposableServer = createServer()
				.protocol(H2)
				.maxKeepAliveRequests(1)
				.secure(spec -> spec.sslContext(
						Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())
				))
				.doOnChannelInit((connectionObserver, channel, remoteAddress) -> {
					Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forServer()
							.autoAckPingFrame(false)
							.autoAckSettingsFrame(true)
							.build();

					channel.pipeline().replace(NettyPipeline.HttpCodec, NettyPipeline.HttpCodec, http2FrameCodec);
					channel.pipeline().addLast(handler);
				})
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(H2)
				.keepAlive(true)
				.secure(spec -> spec.sslContext(
						Http2SslContextSpec.forClient()
								.configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE))
				))
				.http2Settings(builder -> {
					builder.pingInterval(Duration.ofMillis(100));
				})
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofMillis(1000))
				.block();

		assertThat(handler.getReceivedPingTimes()).hasSizeGreaterThanOrEqualTo(2);
		assertThat(channel.parent().isOpen()).isTrue();
	}

	private static class Http2PingFrameHandler extends SimpleChannelInboundHandler<Http2PingFrame> {

		private List<LocalDateTime> receivedPingTimes = new ArrayList<>();

		private final BiConsumer<ChannelHandlerContext, Http2PingFrame> consumer;

		private Http2PingFrameHandler() {
			this.consumer = (ctx, frame) -> ctx.writeAndFlush(new DefaultHttp2PingFrame(frame.content(), true));
		}

		private Http2PingFrameHandler(BiConsumer<ChannelHandlerContext, Http2PingFrame> consumer) {
			this.consumer = consumer;
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, Http2PingFrame frame) throws InterruptedException {
			receivedPingTimes.add(LocalDateTime.now());
			consumer.accept(ctx, frame);
		}

		public List<LocalDateTime> getReceivedPingTimes() {
			return receivedPingTimes.stream()
					.sorted()
					.collect(Collectors.toList());
		}
	}
}
