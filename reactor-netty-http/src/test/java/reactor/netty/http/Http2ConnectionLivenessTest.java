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
package reactor.netty.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.function.Consumer3;
import reactor.netty.BaseHttpTest;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;
import reactor.test.StepVerifier;
import reactor.util.annotation.Nullable;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.netty.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_SUCCESSFUL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This test class verifies {@link HttpConnectionLiveness} with server side.
 *
 * @author raccoonback
 * @author Violeta Georgieva
 * @since 1.2.12
 */
class Http2ConnectionLivenessTest extends BaseHttpTest {

	static Http2SslContextSpec clientCtx2;
	static Http2SslContextSpec serverCtx2;
	static SelfSignedCertificate ssc;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new SelfSignedCertificate();
		clientCtx2 = Http2SslContextSpec.forClient()
		                                .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		serverCtx2 = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleProtocols")
	void serverPingAckFrameWithinThreshold(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx, @Nullable SslProvider.ProtocolSslContextSpec clientCtx) throws Exception {
		Http2PingFrameHandler clientHandler = new Http2PingFrameHandler((ctx, frame, receivedPingTimes) -> {
			if (receivedPingTimes.size() % 2 == 0) {
				ctx.writeAndFlush(new DefaultHttp2PingFrame(frame.content(), true))
				   .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
			}
		});
		testServer(clientCtx, clientHandler, clientProtocols, 2, serverCtx,
				builder -> builder.pingAckTimeout(Duration.ofMillis(100))
				                  .pingAckDropThreshold(2),
				serverProtocols, "serverPingAckFrameWithinThreshold", Duration.ofMillis(600));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleProtocols")
	void clientPingAckFrameWithinThreshold(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx, @Nullable SslProvider.ProtocolSslContextSpec clientCtx) throws Exception {
		Http2PingFrameHandler serverHandler = new Http2PingFrameHandler((ctx, frame, receivedPingTimes) -> {
			if (receivedPingTimes.size() % 2 == 0) {
				ctx.writeAndFlush(new DefaultHttp2PingFrame(frame.content(), true))
				   .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
			}
		});
		testClient(clientCtx,
				builder -> builder.pingAckTimeout(Duration.ofMillis(100))
				                  .pingAckDropThreshold(2),
				clientProtocols, Duration.ZERO, serverCtx, serverHandler, serverProtocols, 2,
				"clientPingAckFrameWithinThreshold", Duration.ofMillis(600));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleProtocols")
	void serverAckPingFrameWithinTimeout(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx, @Nullable SslProvider.ProtocolSslContextSpec clientCtx) throws Exception {
		testServer(clientCtx, new Http2PingFrameHandler(), clientProtocols, 1, serverCtx,
				builder -> builder.pingAckTimeout(Duration.ofMillis(100)),
				serverProtocols, "serverAckPingFrameWithinTimeout", Duration.ofMillis(500));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleProtocols")
	void clientAckPingFrameWithinTimeout(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx, @Nullable SslProvider.ProtocolSslContextSpec clientCtx) throws Exception {
		testClient(clientCtx, builder -> builder.pingAckTimeout(Duration.ofMillis(100)),
				clientProtocols, Duration.ZERO, serverCtx, new Http2PingFrameHandler(), serverProtocols, 1,
				"clientAckPingFrameWithinTimeout", Duration.ofMillis(500));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleProtocols")
	void serverCloseConnectionIfPingFrameDelayed(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx, @Nullable SslProvider.ProtocolSslContextSpec clientCtx) throws Exception {
		Http2PingFrameHandler clientHandler = new Http2PingFrameHandler((ctx, frame, receivedPingTimes) -> {});
		testServer(clientCtx, clientHandler, clientProtocols, 1, serverCtx,
				builder -> builder.pingAckTimeout(Duration.ofMillis(100)),
				serverProtocols, "serverCloseConnectionIfPingFrameDelayed", null);

		clientHandler = new Http2PingFrameHandler((ctx, frame, receivedPingTimes) -> {});
		testServer(clientCtx, clientHandler, clientProtocols, 2, serverCtx,
				builder -> builder.pingAckTimeout(Duration.ofMillis(100))
						.pingAckDropThreshold(2),
				serverProtocols, "serverCloseConnectionIfPingFrameDelayed", null);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleProtocols")
	void clientCloseConnectionIfPingFrameDelayed(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx, @Nullable SslProvider.ProtocolSslContextSpec clientCtx) throws Exception {
		Http2PingFrameHandler serverHandler = new Http2PingFrameHandler((ctx, frame, receivedPingTimes) -> {});
		testClient(clientCtx, builder -> builder.pingAckTimeout(Duration.ofMillis(100)),
				clientProtocols, Duration.ZERO, serverCtx, serverHandler, serverProtocols, 1,
				"clientCloseConnectionIfPingFrameDelayed", null);

		serverHandler = new Http2PingFrameHandler((ctx, frame, receivedPingTimes) -> {});
		testClient(clientCtx,
				builder -> builder.pingAckTimeout(Duration.ofMillis(100))
				                  .pingAckDropThreshold(2),
				clientProtocols, Duration.ZERO, serverCtx, serverHandler, serverProtocols, 2,
				"clientCloseConnectionIfPingFrameDelayed", null);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleProtocols")
	void serverCloseConnectionWithoutPingCheckWhenNotConfigured(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx, @Nullable SslProvider.ProtocolSslContextSpec clientCtx) throws Exception {
		testServer(clientCtx, new Http2PingFrameHandler(), clientProtocols, 0, serverCtx, null,
				serverProtocols, "serverCloseConnectionWithoutPingCheckWhenNotConfigured", null);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleProtocols")
	void clientCloseConnectionWithoutPingCheckWhenNotConfigured(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx, @Nullable SslProvider.ProtocolSslContextSpec clientCtx) throws Exception {
		testClient(clientCtx, null,
				clientProtocols, Duration.ofMillis(300), serverCtx, new Http2PingFrameHandler(), serverProtocols, 0,
				"clientCloseConnectionWithoutPingCheckWhenNotConfigured", null);
	}

	@SuppressWarnings("deprecation")
	void testClient(
			@Nullable SslProvider.ProtocolSslContextSpec clientCtx,
			@Nullable Consumer<Http2SettingsSpec.Builder> clientHttp2Settings,
			HttpProtocol[] clientProtocols,
			Duration evictionInBackground,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx,
			Http2PingFrameHandler serverHandler,
			HttpProtocol[] serverProtocols,
			int serverReceivedPingTimes,
			String serverResponse,
			@Nullable Duration waitTime) throws Exception {
		disposableServer =
				(serverCtx == null ? createServer() : createServer().secure(spec -> spec.sslContext(serverCtx)))
				        .protocol(serverProtocols)
				        .doOnConnection(conn -> {
				            Channel channel = conn.channel() instanceof Http2StreamChannel ? conn.channel().parent() : conn.channel();
				            Http2FrameCodec http2FrameCodec = channel.pipeline().get(Http2FrameCodec.class);
				            if (http2FrameCodec != null) {
				                if (serverHandler.consumer != null) {
				                    setValueReflection(http2FrameCodec.decoder(), true);
				                }
				                channel.pipeline().addLast(serverHandler);
				            }
				        })
				        .handle((req, resp) -> resp.sendString(Mono.just(serverResponse)))
				        .bindNow();

		ConnectionProvider provider =
				ConnectionProvider.builder("testClient")
				                  .maxIdleTime(Duration.ofMillis(300))
				                  .evictInBackground(evictionInBackground)
				                  .build();
		try {
			HttpClient client = (clientCtx == null ?
					createClient(provider, disposableServer::address) :
					createClient(provider, disposableServer::address).secure(spec -> spec.sslContext(clientCtx)));

			if (clientHttp2Settings != null) {
				client = client.http2Settings(clientHttp2Settings);
			}

			AtomicReference<Channel> connectedClientChannel = new AtomicReference<>();
			CountDownLatch connectedClientChannelClosed = new CountDownLatch(1);
			client.protocol(clientProtocols)
			      .doOnResponse((req, conn) -> {
			          if (waitTime != null) {
			              connectedClientChannel.set(conn.channel());
			          }
			          else {
			              conn.channel().parent().closeFuture()
			                      .addListener(f -> connectedClientChannelClosed.countDown());
			          }
			      })
			      .get()
			      .uri("/")
			      .responseContent()
			      .aggregate()
			      .asString()
			      .as(StepVerifier::create)
			      .expectNext(serverResponse)
			      .expectComplete()
			      .verify(Duration.ofSeconds(10));

			if (waitTime != null) {
				Mono.delay(waitTime)
				    .block();

				assertThat(connectedClientChannel.get()).isNotNull();
				assertThat(connectedClientChannel.get().parent().isOpen()).isEqualTo(true);
			}
			else {
				assertThat(connectedClientChannelClosed.await(5, TimeUnit.SECONDS)).isTrue();
			}

			assertThat(serverHandler.getReceivedPingTimes()).hasSize(serverReceivedPingTimes);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@SuppressWarnings("deprecation")
	void testServer(
			@Nullable SslProvider.ProtocolSslContextSpec clientCtx,
			Http2PingFrameHandler clientHandler,
			HttpProtocol[] clientProtocols,
			int clientReceivedPingTimes,
			@Nullable SslProvider.ProtocolSslContextSpec serverCtx,
			@Nullable Consumer<Http2SettingsSpec.Builder> serverHttp2Settings,
			HttpProtocol[] serverProtocols,
			String serverResponse,
			@Nullable Duration waitTime) throws Exception {
		AtomicReference<Channel> connectedServerChannel = new AtomicReference<>();
		CountDownLatch connectedServerChannelClosed = new CountDownLatch(1);
		HttpServer server =
				(serverCtx == null ? createServer() : createServer().secure(spec -> spec.sslContext(serverCtx)))
				        .protocol(serverProtocols)
				        .doOnConnection(conn -> {
				            if (waitTime != null) {
				                connectedServerChannel.set(conn.channel());
				            }
				            else {
				                conn.channel().parent().closeFuture()
				                        .addListener(f -> connectedServerChannelClosed.countDown());
				            }
				        })
				        .idleTimeout(Duration.ofMillis(300));
		if (serverHttp2Settings != null) {
			server = server.http2Settings(serverHttp2Settings);
		}
		disposableServer =
				server.handle((req, resp) -> resp.sendString(Mono.just(serverResponse)))
				      .bindNow();

		(clientCtx == null ? createClient(disposableServer::address) : createClient(disposableServer::address).secure(spec -> spec.sslContext(clientCtx)))
		        .protocol(clientProtocols)
		        .doOnChannelInit((connectionObserver, channel, remoteAddress) -> {
		            Http2FrameCodec http2FrameCodec = channel.pipeline().get(Http2FrameCodec.class);
		            if (http2FrameCodec != null) {
		                if (clientHandler.consumer != null) {
		                    setValueReflection(http2FrameCodec.decoder(), false);
		                }
		                channel.pipeline().addLast(clientHandler);
		            }
		            else if (channel.pipeline().get(NettyPipeline.H2OrHttp11Codec) != null) {
		                channel.pipeline().addAfter(NettyPipeline.H2OrHttp11Codec, "test", new ChannelInboundHandlerAdapter() {

		                    @Override
		                    public void channelActive(ChannelHandlerContext ctx) {
		                        ctx.fireChannelActive();
		                        Http2FrameCodec http2FrameCodec = channel.pipeline().get(Http2FrameCodec.class);
		                        if (http2FrameCodec != null) {
		                            if (clientHandler.consumer != null) {
		                                setValueReflection(http2FrameCodec.decoder(), false);
		                            }
		                            channel.pipeline().addLast(clientHandler);
		                        }
		                        ctx.channel().pipeline().remove(this);
		                    }
		                });
		            }
		            else if (channel.pipeline().get(NettyPipeline.H2CUpgradeHandler) != null) {
		                channel.pipeline().addAfter(NettyPipeline.H2CUpgradeHandler, "test", new ChannelInboundHandlerAdapter() {
		                    @Override
		                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
		                        ctx.fireUserEventTriggered(evt);
		                        if (evt == UPGRADE_SUCCESSFUL) {
		                            Http2FrameCodec http2FrameCodec = channel.pipeline().get(Http2FrameCodec.class);
		                            if (http2FrameCodec != null) {
		                                if (clientHandler.consumer != null) {
		                                    setValueReflection(http2FrameCodec.decoder(), false);
		                                }
		                                channel.pipeline().addLast(clientHandler);
		                            }
		                            ctx.channel().pipeline().remove(this);
		                        }
		                    }
		                });
		            }
		        })
		        .get()
		        .uri("/")
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext(serverResponse)
		        .expectComplete()
		        .verify(Duration.ofSeconds(10));

		if (waitTime != null) {
			Mono.delay(waitTime)
			    .block();

			assertThat(connectedServerChannel.get()).isNotNull();
			assertThat(connectedServerChannel.get().parent().isOpen()).isEqualTo(true);
		}
		else {
			assertThat(connectedServerChannelClosed.await(5, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(clientHandler.getReceivedPingTimes()).hasSize(clientReceivedPingTimes);
	}

	static void setValueReflection(Object obj, boolean onServer) {
		try {
			Field delegate = obj.getClass().getSuperclass().getDeclaredField("delegate");
			delegate.setAccessible(true);
			Object delegateObj = delegate.get(obj);
			if (onServer) {
				delegate = delegateObj.getClass().getSuperclass().getDeclaredField("delegate");
				delegate.setAccessible(true);
				delegateObj = delegate.get(delegateObj);
			}
			Field field = delegateObj.getClass().getDeclaredField("autoAckPing");
			field.setAccessible(true);
			field.setBoolean(delegateObj, false);
		}
		catch (NoSuchFieldException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	static Stream<Arguments> http2CompatibleProtocols() {
		return Stream.of(
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C}, new HttpProtocol[]{HttpProtocol.H2C}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, null, null)
		);
	}

	private static final class Http2PingFrameHandler extends SimpleChannelInboundHandler<Http2PingFrame> {

		private final List<LocalDateTime> receivedPingTimes = new ArrayList<>();

		private final Consumer3<ChannelHandlerContext, Http2PingFrame, List<LocalDateTime>> consumer;

		private Http2PingFrameHandler() {
			this(null);
		}

		private Http2PingFrameHandler(@Nullable Consumer3<ChannelHandlerContext, Http2PingFrame, List<LocalDateTime>> consumer) {
			this.consumer = consumer;
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, Http2PingFrame frame) {
			receivedPingTimes.add(LocalDateTime.now(ZoneId.systemDefault()));
			if (consumer != null) {
				consumer.accept(ctx, frame, receivedPingTimes);
			}
		}

		List<LocalDateTime> getReceivedPingTimes() {
			return receivedPingTimes.stream()
			                        .sorted()
			                        .collect(Collectors.toList());
		}
	}
}
