/*
 * Copyright (c) 2018-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.net.ssl.SSLException;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.Connection;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.forwardheaderhandler.CustomXForwardedHeadersHandler;
import reactor.netty.tcp.TcpClient;
import reactor.netty.transport.AddressUtils;
import reactor.test.StepVerifier;
import reactor.util.annotation.Nullable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static reactor.netty.http.server.ConnectionInfo.DEFAULT_HOST_NAME;
import static reactor.netty.http.server.ConnectionInfo.DEFAULT_HTTPS_PORT;
import static reactor.netty.http.server.ConnectionInfo.DEFAULT_HTTP_PORT;
import static reactor.netty.http.server.ConnectionInfo.getDefaultHostPort;

/**
 * Tests for {@link ConnectionInfo}.
 *
 * @author Brian Clozel
 */
class ConnectionInfoTests extends BaseHttpTest {

	static SelfSignedCertificate ssc;

	protected HttpClient customizeClientOptions(HttpClient httpClient) {
		return httpClient;
	}

	protected HttpServer customizeServerOptions(HttpServer httpServer) {
		return httpServer;
	}

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	static Stream<Arguments> forwardedProtoOnlyParams() {
		return Stream.of(
				Arguments.of("http", true),
				Arguments.of("http", false),
				Arguments.of("https", true),
				Arguments.of("https", false),
				Arguments.of("wss", true),
				Arguments.of("wss", false));
	}

	@Nullable
	static BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> getForwardedHandler(boolean useCustomForwardedHandler) {
		return useCustomForwardedHandler ? CustomXForwardedHeadersHandler.INSTANCE::apply : null;
	}

	@Test
	void noHeaders() {
		testClientRequest(
				clientRequestHeaders -> {},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString())
					          .containsPattern("^0:0:0:0:0:0:0:1(%\\w*)?|127.0.0.1$");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.disposableServer.port());
					Assertions.assertThat(serverRequest.hostName()).containsPattern("^\\[::1\\]|127.0.0.1$");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(this.disposableServer.port());
				});
	}

	@Test
	void noHeadersEmptyHostHeader() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.set(HttpHeaderNames.HOST, ""),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString())
					          .containsPattern("^0:0:0:0:0:0:0:1(%\\w*)?|127.0.0.1$");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.disposableServer.port());
					Assertions.assertThat(serverRequest.hostName()).isEmpty();
					int port = serverRequest.scheme().equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(port);
				});
	}

	@Test
	void hostHeaderNoForwardedHeaders() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add(HttpHeaderNames.HOST, DEFAULT_HOST_NAME),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString())
					          .containsPattern("^0:0:0:0:0:0:0:1(%\\w*)?|127.0.0.1$");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.disposableServer.port());
					Assertions.assertThat(serverRequest.hostName()).isEqualTo(DEFAULT_HOST_NAME);
					int port = serverRequest.scheme().equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(port);
				});
	}

	@Test
	void hostHeaderWithPortNoForwardedHeaders() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add(HttpHeaderNames.HOST, DEFAULT_HOST_NAME + ":" + this.disposableServer.port()),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString())
					          .containsPattern("^0:0:0:0:0:0:0:1(%\\w*)?|127.0.0.1$");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.disposableServer.port());
					Assertions.assertThat(serverRequest.hostName()).isEqualTo(DEFAULT_HOST_NAME);
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(this.disposableServer.port());
				});
	}

	@Test
	void forwardedHost() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "host=192.168.0.1"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("192.168.0.1");
					int port = serverRequest.scheme().equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(port);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(port);
				});
	}

	@Test
	void forwardedHostEmptyHostHeader() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "host=192.168.0.1")
						.set(HttpHeaderNames.HOST, ""),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("192.168.0.1");
					int port = serverRequest.scheme().equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(port);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(port);
				});
	}

	@Test
	void forwardedHostIpV6() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "host=[1abc:2abc:3abc::5ABC:6abc]"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					int port = serverRequest.scheme().equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(port);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(port);
				});
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(strings = {"http", "https", "wss"})
	void forwardedProtoOnly(String protocol) {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "proto=" + protocol)
						.set(HttpHeaderNames.HOST, "192.168.0.1"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString())
							.containsPattern("^0:0:0:0:0:0:0:1(%\\w*)?|127.0.0.1$");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.disposableServer.port());
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(getDefaultHostPort(protocol));
					Assertions.assertThat(serverRequest.scheme()).isEqualTo(protocol);
				});
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedFor(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("X-Forwarded-For",
						"[1abc:2abc:3abc::5ABC:6abc]:8080, 192.168.0.1"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					Assertions.assertThat(serverRequest.remoteAddress().getPort()).isEqualTo(8080);
				},
				useCustomForwardedHandler);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedHost(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("X-Forwarded-Host",
						"[1abc:2abc:3abc::5ABC:6abc], 192.168.0.1"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					int port = serverRequest.scheme().equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(port);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(port);
				},
				useCustomForwardedHandler);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedHostEmptyHostHeader(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("X-Forwarded-Host",
						"[1abc:2abc:3abc::5ABC:6abc], 192.168.0.1").set(HttpHeaderNames.HOST, ""),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					int port = serverRequest.scheme().equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(port);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(port);
				},
				useCustomForwardedHandler);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedHostPortIncluded(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("X-Forwarded-Host",
						"[1abc:2abc:3abc::5ABC:6abc]:9090, 192.168.0.1"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(9090);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(9090);
				},
				useCustomForwardedHandler);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedHostAndPort(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("X-Forwarded-Host", "192.168.0.1");
					clientRequestHeaders.add("X-Forwarded-Port", "8080");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(8080);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(8080);
				},
				useCustomForwardedHandler);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedHostPortIncludedAndXForwardedPort(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("X-Forwarded-Host", "192.168.0.1:9090");
					clientRequestHeaders.add("X-Forwarded-Port", "8080");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(8080);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(8080);
				},
				useCustomForwardedHandler);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedMultipleHeaders(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("X-Forwarded-Host", "192.168.0.1");
					clientRequestHeaders.add("X-Forwarded-Host", "192.168.0.2");
					clientRequestHeaders.add("X-Forwarded-Port", "8080");
					clientRequestHeaders.add("X-Forwarded-Port", "8081");
					clientRequestHeaders.add("X-Forwarded-Proto", "http");
					clientRequestHeaders.add("X-Forwarded-Proto", "https");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(8080);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(8080);
					Assertions.assertThat(serverRequest.scheme()).isEqualTo("http");
				},
				useCustomForwardedHandler);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedHostAndEmptyPort(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("X-Forwarded-Host", "192.168.0.1");
					clientRequestHeaders.add("X-Forwarded-Port", "");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("192.168.0.1");
					int port = serverRequest.scheme().equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(port);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(port);
				},
				getForwardedHandler(useCustomForwardedHandler),
				httpClient -> httpClient,
				httpServer -> httpServer.port(8080),
				false);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedHostAndNonNumericPort(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("X-Forwarded-Host", "192.168.0.1");
					clientRequestHeaders.add("X-Forwarded-Port", "test");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(8080);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(8080);
				},
				getForwardedHandler(useCustomForwardedHandler),
				httpClient -> httpClient,
				httpServer -> httpServer.port(8080),
				false,
				true);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedForHostAndPort(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("X-Forwarded-For", "192.168.0.1");
					clientRequestHeaders.add("X-Forwarded-Host", "a.example.com");
					clientRequestHeaders.add("X-Forwarded-Port", "8080");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(8080);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(8080);
				},
				useCustomForwardedHandler);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedForHostAndPortAndProto(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("X-Forwarded-For", "192.168.0.1");
					clientRequestHeaders.add("X-Forwarded-Host", "a.example.com");
					clientRequestHeaders.add("X-Forwarded-Port", "8080");
					clientRequestHeaders.add("X-Forwarded-Proto", "http");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(8080);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(8080);
					Assertions.assertThat(serverRequest.scheme()).isEqualTo("http");
				},
				useCustomForwardedHandler);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedForMultipleHostAndPortAndProto(boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("X-Forwarded-For", "192.168.0.1,10.20.30.1,10.20.30.2");
					clientRequestHeaders.add("X-Forwarded-Host", "a.example.com,b.example.com");
					clientRequestHeaders.add("X-Forwarded-Port", "8080,8090");
					clientRequestHeaders.add("X-Forwarded-Proto", "http,https");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(8080);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(8080);
					Assertions.assertThat(serverRequest.scheme()).isEqualTo("http");
				},
				useCustomForwardedHandler);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedForAndPortOnly(boolean useCustomForwardedHandler) throws SSLException {
		SslContext clientSslContext = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		SslContext serverSslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();

		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("Host", "a.example.com");
					clientRequestHeaders.add("X-Forwarded-For", "192.168.0.1");
					clientRequestHeaders.add("X-Forwarded-Port", "8443");
					clientRequestHeaders.add("X-Forwarded-Proto", "https");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostAddress().getHostString())
							.containsPattern("^0:0:0:0:0:0:0:1(%\\w*)?|127.0.0.1$");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(8443);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.scheme()).isEqualTo("https");
				},
				getForwardedHandler(useCustomForwardedHandler),
				httpClient -> httpClient.secure(ssl -> ssl.sslContext(clientSslContext)),
				httpServer -> httpServer.secure(ssl -> ssl.sslContext(serverSslContext)),
				true);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@MethodSource("forwardedProtoOnlyParams")
	void xForwardedProtoOnly(String protocol, boolean useCustomForwardedHandler) {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("Host", "a.example.com");
					clientRequestHeaders.add("X-Forwarded-Proto", protocol);
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString())
							.containsPattern("^0:0:0:0:0:0:0:1(%\\w*)?|127.0.0.1$");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.disposableServer.port());
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(getDefaultHostPort(protocol));
					Assertions.assertThat(serverRequest.scheme()).isEqualTo(protocol);
				},
				useCustomForwardedHandler);
	}

	@Test
	void customForwardedHandlerForMultipleHost() {
		testClientRequest(
				clientRequestHeaders ->
					clientRequestHeaders.add("X-Forwarded-Host", "a.example.com,b.example.com"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("b.example.com");
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("b.example.com");
				},
				(connectionInfo, request) -> {
					String hostHeader = request.headers().get(DefaultHttpForwardedHeaderHandler.X_FORWARDED_HOST_HEADER);
					if (hostHeader != null) {
						InetSocketAddress hostAddress = AddressUtils.createUnresolved(
								hostHeader.split(",", 2)[1].trim(),
								connectionInfo.getHostAddress().getPort());
						connectionInfo = connectionInfo.withHostAddress(hostAddress);
					}
					return connectionInfo;
				});
	}

	@Test
	void proxyProtocolOn() throws InterruptedException {
		String remoteAddress = "202.112.144.236";
		ArrayBlockingQueue<String> resultQueue = new ArrayBlockingQueue<>(1);

		Consumer<HttpServerRequest> requestConsumer = serverRequest -> {
			String remoteAddrFromRequest = serverRequest.remoteAddress().getHostString();
			resultQueue.add(remoteAddrFromRequest);
		};

		this.disposableServer =
				createServer()
				          .proxyProtocol(ProxyProtocolSupportType.ON)
				          .handle((req, res) -> {
				              try {
				                  requestConsumer.accept(req);
				                  return res.status(200)
				                            .sendString(Mono.just("OK"));
				              }
				              catch (Throwable e) {
				                  return res.status(500)
				                            .sendString(Mono.just(e.getMessage()));
				              }
				          })
				          .bindNow();

		Connection clientConn =
				TcpClient.create()
				         .port(this.disposableServer.port())
				         .connectNow();

		ByteBuf proxyProtocolMsg = clientConn.channel()
		                                     .alloc()
		                                     .buffer();
		proxyProtocolMsg.writeCharSequence("PROXY TCP4 " + remoteAddress + " 10.210.12.10 5678 80\r\n",
				Charset.defaultCharset());
		proxyProtocolMsg.writeCharSequence("GET /test HTTP/1.1\r\nHost: a.example.com\r\n\r\n",
				Charset.defaultCharset());
		clientConn.channel()
		          .writeAndFlush(proxyProtocolMsg)
		          .addListener(f -> {
		              if (!f.isSuccess()) {
		                  fail("Writing proxyProtocolMsg was not successful");
		              }
		          });

		assertThat(resultQueue.poll(5, TimeUnit.SECONDS)).isEqualTo(remoteAddress);

		//send a http request again to confirm that removeAddress is not changed.
		ByteBuf httpMsg = clientConn.channel()
		                            .alloc()
		                            .buffer();
		httpMsg.writeCharSequence("GET /test HTTP/1.1\r\nHost: a.example.com\r\n\r\n",
				Charset.defaultCharset());
		clientConn.channel()
		          .writeAndFlush(httpMsg)
		          .addListener(f -> {
		              if (!f.isSuccess()) {
		                  fail("Writing proxyProtocolMsg was not successful");
		              }
		          });

		assertThat(resultQueue.poll(5, TimeUnit.SECONDS)).isEqualTo(remoteAddress);
	}

	@Test
	void proxyProtocolAuto() throws InterruptedException {
		String remoteAddress = "202.112.144.236";
		ArrayBlockingQueue<String> resultQueue = new ArrayBlockingQueue<>(1);

		Consumer<HttpServerRequest> requestConsumer = serverRequest -> {
			String remoteAddrFromRequest = serverRequest.remoteAddress().getHostString();
			resultQueue.add(remoteAddrFromRequest);
		};

		this.disposableServer =
				createServer()
				          .proxyProtocol(ProxyProtocolSupportType.AUTO)
				          .handle((req, res) -> {
				              try {
				                  requestConsumer.accept(req);
				                  return res.status(200)
				                            .sendString(Mono.just("OK"));
				              }
				              catch (Throwable e) {
				                  return res.status(500)
				                            .sendString(Mono.just(e.getMessage()));
				              }
				          })
				          .bindNow();

		Connection clientConn =
				TcpClient.create()
				         .port(this.disposableServer.port())
				         .connectNow();

		ByteBuf proxyProtocolMsg = clientConn.channel()
		                                     .alloc()
		                                     .buffer();
		proxyProtocolMsg.writeCharSequence("PROXY TCP4 " + remoteAddress + " 10.210.12.10 5678 80\r\n",
				Charset.defaultCharset());
		proxyProtocolMsg.writeCharSequence("GET /test HTTP/1.1\r\nHost: a.example.com\r\n\r\n",
				Charset.defaultCharset());
		clientConn.channel()
		          .writeAndFlush(proxyProtocolMsg)
		          .addListener(f -> {
		              if (!f.isSuccess()) {
		                  fail("Writing proxyProtocolMsg was not successful");
		              }
		          });

		assertThat(resultQueue.poll(5, TimeUnit.SECONDS)).isEqualTo(remoteAddress);

		clientConn.disposeNow();

		clientConn =
				TcpClient.create()
				          .port(this.disposableServer.port())
				          .connectNow();

		// Send a http request without proxy protocol in a new connection,
		// server should support this when proxyProtocol is set to ProxyProtocolSupportType.AUTO
		ByteBuf httpMsg = clientConn.channel()
		                            .alloc()
		                            .buffer();
		httpMsg.writeCharSequence("GET /test HTTP/1.1\r\nHost: a.example.com\r\n\r\n",
				Charset.defaultCharset());
		clientConn.channel()
		          .writeAndFlush(httpMsg)
		          .addListener(f -> {
		              if (!f.isSuccess()) {
		                  fail("Writing proxyProtocolMsg was not successful");
		              }
		          });

		assertThat(resultQueue.poll(5, TimeUnit.SECONDS))
				.containsPattern("^0:0:0:0:0:0:0:1(%\\w*)?|127.0.0.1$");
	}

	@Test
	void https() throws SSLException {
		SslContext clientSslContext = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		SslContext serverSslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();

		testClientRequest(
				clientRequestHeaders -> {},
				serverRequest -> Assertions.assertThat(serverRequest.scheme()).isEqualTo("https"),
				httpClient -> httpClient.secure(ssl -> ssl.sslContext(clientSslContext)),
				httpServer -> httpServer.secure(ssl -> ssl.sslContext(serverSslContext)),
				true);
	}

	// Users may add SslHandler themselves, not by using `httpServer.secure`
	@Test
	void httpsUserAddedSslHandler() throws SSLException {
		SslContext clientSslContext = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		SslContext serverSslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();

		testClientRequest(
				clientRequestHeaders -> {},
				serverRequest -> Assertions.assertThat(serverRequest.scheme()).isEqualTo("https"),
				httpClient -> httpClient.secure(ssl -> ssl.sslContext(clientSslContext)),
				httpServer -> httpServer.doOnChannelInit((observer, channel, address) -> {
								SslHandler sslHandler = serverSslContext.newHandler(channel.alloc());
								if (channel.pipeline().get(NettyPipeline.SslHandler) == null) {
									channel.pipeline().addFirst(NettyPipeline.SslHandler, sslHandler);
								}
							}),
				true);
	}

	@Test
	void forwardedMultipleHosts() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded",
						"host=a.example.com,host=b.example.com, host=c.example.com"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					int port = serverRequest.scheme().equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(port);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(port);
				});
	}

	@Test
	void forwardedAllDirectives() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "host=a.example.com:443;proto=https"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(DEFAULT_HTTPS_PORT);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(DEFAULT_HTTPS_PORT);
					Assertions.assertThat(serverRequest.scheme()).isEqualTo("https");
				});
	}

	@Test
	void forwardedAllDirectivesQuoted() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded",
						"host=\"a.example.com:443\";proto=\"https\""),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(DEFAULT_HTTPS_PORT);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(DEFAULT_HTTPS_PORT);
					Assertions.assertThat(serverRequest.scheme()).isEqualTo("https");
				});
	}

	@Test
	void forwardedMultipleHeaders() {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("Forwarded", "host=a.example.com:443;proto=https");
					clientRequestHeaders.add("Forwarded", "host=b.example.com");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(DEFAULT_HTTPS_PORT);
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(DEFAULT_HTTPS_PORT);
					Assertions.assertThat(serverRequest.scheme()).isEqualTo("https");
				});
	}

	@Test
	void forwardedForHostname() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "for=\"_gazonk\""),
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("_gazonk");
					Assertions.assertThat(serverRequest.remoteAddress().getPort()).isPositive();
				});
	}

	@Test
	void forwardedForIp() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded",
						"for=192.0.2.60;proto=http;by=203.0.113.43"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("192.0.2.60");
					Assertions.assertThat(serverRequest.remoteAddress().getPort()).isPositive();
					Assertions.assertThat(serverRequest.scheme()).isEqualTo("http");
				});
	}

	@Test
	void forwardedForIpV6() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "for=\"[2001:db8:cafe::17]:4711\""),
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("2001:db8:cafe:0:0:0:0:17");
					Assertions.assertThat(serverRequest.remoteAddress().getPort()).isEqualTo(4711);
				});
	}

	private void testClientRequest(Consumer<HttpHeaders> clientRequestHeadersConsumer,
	                               Consumer<HttpServerRequest> serverRequestConsumer,
	                               boolean useCustomForwardedHandler) {
		testClientRequest(clientRequestHeadersConsumer, serverRequestConsumer, getForwardedHandler(useCustomForwardedHandler),
				Function.identity(), Function.identity(), false);
	}

	private void testClientRequest(Consumer<HttpHeaders> clientRequestHeadersConsumer,
			Consumer<HttpServerRequest> serverRequestConsumer) {
		testClientRequest(clientRequestHeadersConsumer, serverRequestConsumer, null, Function.identity(), Function.identity(), false);
	}

	private void testClientRequest(Consumer<HttpHeaders> clientRequestHeadersConsumer,
			Consumer<HttpServerRequest> serverRequestConsumer,
			BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler) {
		testClientRequest(clientRequestHeadersConsumer, serverRequestConsumer, forwardedHeaderHandler, Function.identity(), Function.identity(), false);
	}

	private void testClientRequest(Consumer<HttpHeaders> clientRequestHeadersConsumer,
			Consumer<HttpServerRequest> serverRequestConsumer,
			Function<HttpClient, HttpClient> clientConfigFunction,
			Function<HttpServer, HttpServer> serverConfigFunction,
			boolean useHttps) {
		testClientRequest(clientRequestHeadersConsumer, serverRequestConsumer, null, clientConfigFunction, serverConfigFunction, useHttps);
	}

	private void testClientRequest(Consumer<HttpHeaders> clientRequestHeadersConsumer,
			Consumer<HttpServerRequest> serverRequestConsumer,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			Function<HttpClient, HttpClient> clientConfigFunction,
			Function<HttpServer, HttpServer> serverConfigFunction,
			boolean useHttps) {
		testClientRequest(clientRequestHeadersConsumer, serverRequestConsumer, forwardedHeaderHandler,
				clientConfigFunction, serverConfigFunction, useHttps, false);
	}

	private void testClientRequest(Consumer<HttpHeaders> clientRequestHeadersConsumer,
				Consumer<HttpServerRequest> serverRequestConsumer,
				@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
				Function<HttpClient, HttpClient> clientConfigFunction,
				Function<HttpServer, HttpServer> serverConfigFunction,
				boolean useHttps,
				boolean is400BadRequest) {

		HttpServer server = createServer().forwarded(true);
		if (forwardedHeaderHandler != null) {
			server = server.forwarded(forwardedHeaderHandler);
		}
		this.disposableServer =
				customizeServerOptions(serverConfigFunction.apply(server))
				        .handle((req, res) -> {
				            try {
				                serverRequestConsumer.accept(req);
				                return res.status(200)
				                          .sendString(Mono.just("OK"));
				            }
				            catch (Throwable e) {
				                return res.status(500)
				                          .sendString(Mono.just(e.getMessage()));
				            }
				        })
				        .bindNow();

		String uri = "/test";
		if (useHttps) {
			uri += "https://localhost:" + this.disposableServer.port();
		}

		if (!is400BadRequest) {
			customizeClientOptions(clientConfigFunction.apply(createClient(this.disposableServer.port())))
			        .headers(clientRequestHeadersConsumer)
			        .get()
			        .uri(uri)
			        .responseContent()
			        .aggregate()
			        .asString()
			        .as(StepVerifier::create)
			        .expectNext("OK")
			        .expectComplete()
			        .verify(Duration.ofSeconds(30));
		}
		else {
			customizeClientOptions(clientConfigFunction.apply(createClient(this.disposableServer.port())))
			        .headers(clientRequestHeadersConsumer)
			        .get()
			        .uri(uri)
			        .responseSingle((res, bytes) -> Mono.just(res.status().toString()))
			        .as(StepVerifier::create)
			        .expectNext("400 Bad Request")
			        .expectComplete()
			        .verify(Duration.ofSeconds(30));
		}
	}
}
