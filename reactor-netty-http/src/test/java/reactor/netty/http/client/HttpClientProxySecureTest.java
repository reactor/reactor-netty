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

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.transport.ProxyProvider;
import reactor.test.StepVerifier;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuple2;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Test for http client using a secured proxy.
 * @author Pierre De Rop
 */
@SuppressWarnings("FutureReturnValueIgnored")
class HttpClientProxySecureTest extends BaseHttpTest {

	/**
	 * Self-signed certificate used to configure secured client/proxy/server.
	 */
	static SelfSignedCertificate ssc;

	/**
	 * A secured proxy for testing. The client establishes a secured tunnel to the proxy,
	 * and on top of the tunnel then establishes a new secured connection to the server.
	 */
	static ProxySecure proxy;

	/**
	 * The client establishes a secured channel to this proxy.
	 */
	static class ProxySecure {
		// The proxy inbound and outbound channels are handled by one single threaded loop.
		final EventLoopGroup group = new NioEventLoopGroup(1);

		// The proxy port
		final int proxyPort;

		// The unsecured channel between the proxy and the server
		Channel outboundChannel;

		// The secured channel
		Channel inboundChannel;

		// The proxy server socket (accepts)
		Channel proxy;

		// The ssl context used to accept secured proxy client channels
		final SslContext sslProxy;

		/**
		 * Represents a CONNECT header. Is decoded by the ConnectCodec netty decoder below.
		 */
		static class HostHeader {
			final String ip;
			final int port;

			HostHeader(String ip, int port) {
				this.ip = ip;
				this.port = port;
			}
		}

		/**
		 * When the client establishes a secured connection to the proxy, it will then send a CONNECT request which is decoded by this codec.
		 */
		static class ConnectCodec extends LineBasedFrameDecoder {
			// Holds all the CONNECT header lines. Once we receive the last empty line, then this codec will remove itself from the pipeline.
			final List<String> lines = new ArrayList<>();

			// Codec channel pipeline. The codec unregisters from it once it has fully decoded the CONNECT header.
			ChannelPipeline pipeline;

			// The codec handler name
			final static String NAME = "codec.connect";

			ConnectCodec() {
				super(1024);
			}

			@Override
			public void channelActive(ChannelHandlerContext ctx) {
				this.pipeline = ctx.channel().pipeline();
				ctx.fireChannelActive();
			}

			@Override
			@Nullable
			protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
				ByteBuf line = (ByteBuf) super.decode(ctx, buffer);
				if (line != null) {
					String lineStr = line.toString(Charset.defaultCharset());
					line.release();
					lines.add(lineStr);

					if (lineStr.length() == 0) {
						// we have fully parsed connect message, lookup the Host header.
						for (String header : lines) {
							int colon = header.indexOf(':');
							if (colon == -1) {
								throw new IllegalStateException("Missing colon in Connect headers");
							}
							String headerName = header.substring(0, colon).trim();
							if (headerName.equalsIgnoreCase("host")) {
								String headerValue = header.substring(colon + 1).trim();
								colon = headerValue.indexOf(":");
								if (colon == -1) {
									throw new IllegalStateException("Missing colon in Connect Host header value");
								}
								String host = headerValue.substring(0, colon).trim();
								String port = headerValue.substring(colon + 1);
								pipeline.remove(NAME);
								return new HostHeader(host, Integer.parseInt(port));
							}
						}
						throw new IllegalStateException("Host header not found in CONNECT message: " + lines);
					}
				}
				return null;
			}
		}

		/**
		 * Handles the unsecured channel established between the proxy and the outbound server.
		 */
		final class ProxyOutboundHandler extends ChannelInboundHandlerAdapter {
			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg) {
				inboundChannel.writeAndFlush(msg);
			}
		}

		/**
		 * Handles the secured channel established between the client and the proxy.
		 */
		final class ProxyInboundHandler extends ChannelInboundHandlerAdapter {
			@Override
			public void channelActive(ChannelHandlerContext ctx) {
				inboundChannel = ctx.channel();
			}

			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg) {
				if (msg instanceof HostHeader) {
					// The ConnectHandler has decoded a CONNECT message. connect to the outbound server
					// and send 200 Connection established to the client.
					HostHeader header = (HostHeader) msg;
					InetSocketAddress outboundAddress = new InetSocketAddress(header.ip, header.port);
					ctx.channel().config().setOption(ChannelOption.AUTO_READ, false);

					connectOutbound(outboundAddress).addListener(future -> {
						sendConnectResponse();
						ctx.channel().config().setOption(ChannelOption.AUTO_READ, true);
						ctx.channel().read();
					});
				}
				else {
					outboundChannel.writeAndFlush(msg);
				}
			}

			private void sendConnectResponse() {
				ByteBuf buf = Unpooled.copiedBuffer("HTTP/1.1 200 Connection established\r\n\r\n", CharsetUtil.UTF_8);
				inboundChannel.writeAndFlush(buf);
			}

			private ChannelFuture connectOutbound(InetSocketAddress outboundAddress) {
				Bootstrap bootstrap = new Bootstrap();
				bootstrap.group(group);

				bootstrap.channel(NioSocketChannel.class)
						.remoteAddress(outboundAddress)
						.handler(new ChannelInitializer<Channel>() {
							protected void initChannel(Channel ch) {
								ch.pipeline().addLast(new ProxyOutboundHandler());
							}
						});

				ChannelFuture cf = bootstrap.connect();
				outboundChannel = cf.channel();
				return cf;
			}
		}

		ProxySecure(int proxyPort) throws Exception {
			this.proxyPort = proxyPort;
			this.sslProxy = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
					.sslProvider(SslProvider.JDK)
					.build();
		}

		public int port() {
			return ((InetSocketAddress) (proxy.localAddress())).getPort();
		}

		public void start() throws Exception {
			// start proxy
			ServerBootstrap serverBootstrap = new ServerBootstrap()
					.group(group)
					.channel(NioServerSocketChannel.class)
					.localAddress(new InetSocketAddress("localhost", proxyPort))
					.childHandler(new ChannelInitializer<Channel>() {
						@Override
						protected void initChannel(Channel ch) {
							ch.pipeline().addLast("log", new LoggingHandler(LogLevel.DEBUG));
							ch.pipeline().addLast("ssl", sslProxy.newHandler(ch.alloc()));
							ch.pipeline().addLast(ConnectCodec.NAME, new ConnectCodec());
							ch.pipeline().addLast("proxy.inbound", new ProxyInboundHandler());
						}
					})
					.childOption(ChannelOption.AUTO_READ, true);
			ChannelFuture serverChannelFuture = serverBootstrap.bind().sync();
			proxy = serverChannelFuture.channel();
		}

		public void stop() throws InterruptedException {
			if (inboundChannel != null) {
				inboundChannel.close().sync();
			}
			if (outboundChannel != null) {
				outboundChannel.close().sync();
			}
			if (proxy != null) {
				proxy.close().sync();
			}
			group.shutdownGracefully();
		}
	}

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@BeforeEach
	void setUp() throws Exception {
		Http11SslContextSpec sslServer = Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		disposableServer = createServer()
				.host("localhost")
				.port(0)
				.wiretap(true)
				.secure(ssl -> ssl.sslContext(sslServer))
				.handle((req, res) -> res.sendString(Mono.just("test")))
				.bindNow();

		proxy = new ProxySecure(0);
		proxy.start();
	}

	@AfterEach
	void disposeProxy() throws Exception {
		if (proxy != null) {
			proxy.stop();
		}
	}

	@Test
	void httpClientWithSecuredProxy() throws SSLException {
		SslContext proxySsl = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE).build();

		Http11SslContextSpec http11SslContextSpec =
				Http11SslContextSpec.forClient()
						.configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		Mono<Tuple2<String, HttpHeaders>> result = HttpClient.create()
				.proxy(ops -> ops
						.type(ProxyProvider.Proxy.HTTP)
						.host("localhost")
						.secure(spec -> spec.sslContext(proxySsl))
						.port(proxy.port()))
				.remoteAddress(disposableServer::address)
				.secure(spec -> spec.sslContext(http11SslContextSpec))
				.wiretap(true)
				.get()
				.uri("/test")
				.responseSingle((response, body) -> Mono.zip(body.asString(),
						Mono.just(response.responseHeaders())));

		StepVerifier.create(result)
				.expectNextMatches(t -> "test".equals(t.getT1()))
				.expectComplete()
				.verify(Duration.ofSeconds(30000));
	}

}
