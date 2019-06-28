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

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

/**
 * Tests for {@link ConnectionInfo}
 *
 * @author Brian Clozel
 */
public class ConnectionInfoTests {

	private DisposableServer connection;

	@Test
	public void noHeaders() {
		testClientRequest(
				clientRequestHeaders -> {},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString())
					          .containsPattern("^0:0:0:0:0:0:0:1(%\\w*)?|127.0.0.1$");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.connection.address().getPort());
				});
	}

	@Test
	public void forwardedHost() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "host=192.168.0.1"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.connection.address().getPort());
				});
	}

	@Test
	public void forwardedHostIpV6() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "host=[1abc:2abc:3abc::5ABC:6abc]"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.connection.address().getPort());
				});
	}

	@Test
	public void xForwardedFor() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("X-Forwarded-For",
						"[1abc:2abc:3abc::5ABC:6abc]:8080, 192.168.0.1"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					Assertions.assertThat(serverRequest.remoteAddress().getPort()).isEqualTo(8080);
				});
	}

	@Test
	public void xForwardedHost() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("X-Forwarded-Host",
						"[1abc:2abc:3abc::5ABC:6abc], 192.168.0.1"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.connection.address().getPort());
				});
	}

	@Test
	public void xForwardedHostAndPort() {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("X-Forwarded-Host", "192.168.0.1");
					clientRequestHeaders.add("X-Forwarded-Port", "8080");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(8080);
				});
	}

	@Test
	public void xForwardedForHostAndPort() {
		testClientRequest(
				clientRequestHeaders -> {
                    clientRequestHeaders.add("X-Forwarded-For", "192.168.0.1");
					clientRequestHeaders.add("X-Forwarded-Host", "a.example.com");
					clientRequestHeaders.add("X-Forwarded-Port", "8080");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("192.168.0.1") ;
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(8080);
				});
	}

	@Test
	public void proxyProtocol() throws InterruptedException {
		String remoteAddress = "202.112.144.236";
		ArrayBlockingQueue<String> resultQueue = new ArrayBlockingQueue<>(1);

		Consumer<HttpServerRequest> requestConsumer = serverRequest -> {
			String remoteAddrFromRequest = serverRequest.remoteAddress().getHostString();
			resultQueue.add(remoteAddrFromRequest);
		};

		this.connection =
				HttpServer.create()
				          .port(0)
				          .proxyProtocol(true)
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
				          .wiretap(true)
				          .bindNow();

		Connection clientConn =
				TcpClient.create()
				         .port(this.connection.port())
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
	}

	@Test
	public void forwardedMultipleHosts() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded",
						"host=a.example.com,host=b.example.com, host=c.example.com"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.connection.address().getPort());
				});
	}

	@Test
	public void forwardedAllDirectives() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "host=a.example.com:443;proto=https"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(443);
					Assertions.assertThat(serverRequest.scheme()).isEqualTo("https");
				});
	}

	@Test
	public void forwardedAllDirectivesQuoted() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded",
						"host=\"a.example.com:443\";proto=\"https\""),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(443);
					Assertions.assertThat(serverRequest.scheme()).isEqualTo("https");
				});
	}

	@Test
	public void forwardedMultipleHeaders() {
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("Forwarded", "host=a.example.com:443;proto=https");
					clientRequestHeaders.add("Forwarded", "host=b.example.com");
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(443);
					Assertions.assertThat(serverRequest.scheme()).isEqualTo("https");
				});
	}

	@Test
	public void forwardedForHostname() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "for=\"_gazonk\""),
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("_gazonk");
					Assertions.assertThat(serverRequest.remoteAddress().getPort()).isPositive();
				});
	}

	@Test
	public void forwardedForIp() {
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
	public void forwardedForIpV6() {
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "for=\"[2001:db8:cafe::17]:4711\""),
				serverRequest -> {
					Assertions.assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("2001:db8:cafe:0:0:0:0:17");
					Assertions.assertThat(serverRequest.remoteAddress().getPort()).isEqualTo(4711);
				});
	}

	@Test
	public void parseAddressForHostNameNoPort() {
		testParseAddress("a.example.com", 8080, inetSocketAddress -> {
			Assertions.assertThat(inetSocketAddress.getHostName()).isEqualTo("a.example.com");
			Assertions.assertThat(inetSocketAddress.getPort()).isEqualTo(8080);
		});
	}

	@Test
	public void parseAddressForHostNameWithPort() {
		testParseAddress("a.example.com:443", 8080, inetSocketAddress -> {
			Assertions.assertThat(inetSocketAddress.getHostName()).isEqualTo("a.example.com");
			Assertions.assertThat(inetSocketAddress.getPort()).isEqualTo(443);
		});
	}

	@Test
	public void parseAddressForIpV4NoPort() {
		testParseAddress("192.0.2.60", 8080, inetSocketAddress -> {
			Assertions.assertThat(inetSocketAddress.getHostName()).isEqualTo("192.0.2.60");
			Assertions.assertThat(inetSocketAddress.getPort()).isEqualTo(8080);
		});
	}

	@Test
	public void parseAddressForIpV4WithPort() {
		testParseAddress("192.0.2.60:443", 8080, inetSocketAddress -> {
			Assertions.assertThat(inetSocketAddress.getHostName()).isEqualTo("192.0.2.60");
			Assertions.assertThat(inetSocketAddress.getPort()).isEqualTo(443);
		});
	}

	@Test
	public void parseAddressForIpV6NoPortNoBrackets() {
		testParseAddress("1abc:2abc:3abc:0:0:0:5abc:6abc", 8080, inetSocketAddress -> {
			Assertions.assertThat(inetSocketAddress.getHostName()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
			Assertions.assertThat(inetSocketAddress.getPort()).isEqualTo(8080);
		});
	}

	@Test
	public void parseAddressForIpV6NoPortWithBrackets() {
		testParseAddress("[1abc:2abc:3abc::5ABC:6abc]", 8080, inetSocketAddress -> {
			Assertions.assertThat(inetSocketAddress.getHostName()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
			Assertions.assertThat(inetSocketAddress.getPort()).isEqualTo(8080);
		});
	}

	@Test
	public void parseAddressForIpV6WithPortAndBrackets_1() {
		testParseAddress("[1abc:2abc:3abc::5ABC:6abc]:443", 8080, inetSocketAddress -> {
			Assertions.assertThat(inetSocketAddress.getHostName()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
			Assertions.assertThat(inetSocketAddress.getPort()).isEqualTo(443);
		});
	}

	@Test
	public void parseAddressForIpV6WithPortAndBrackets_2() {
		testParseAddress("[2001:db8:a0b:12f0::1]:dba2", 8080, inetSocketAddress -> {
			Assertions.assertThat(inetSocketAddress.getHostName()).isEqualTo("2001:db8:a0b:12f0:0:0:0:1");
			Assertions.assertThat(inetSocketAddress.getPort()).isEqualTo(8080);
		});
	}

	private void testClientRequest(Consumer<HttpHeaders> clientRequestHeadersConsumer,
			Consumer<HttpServerRequest> serverConsumer) {

		this.connection =
				HttpServer.create()
				          .forwarded(true)
				          .port(0)
				          .handle((req, res) -> {
				              try {
				                  serverConsumer.accept(req);
				                  return res.status(200)
				                            .sendString(Mono.just("OK"));
				              }
				              catch (Throwable e) {
				                  return res.status(500)
				                            .sendString(Mono.just(e.getMessage()));
				              }
				          })
				          .wiretap(true)
				          .bindNow();

		String response =
				HttpClient.create()
				          .port(this.connection.address().getPort())
				          .wiretap(true)
				          .headers(clientRequestHeadersConsumer)
				          .get()
				          .uri("/test")
				          .responseContent()
				          .aggregate()
				          .asString()
				          .block();

		assertThat(response).isEqualTo("OK");
	}

	private void testParseAddress(String address, int defaultPort, Consumer<InetSocketAddress> inetSocketAddressConsumer) {
		inetSocketAddressConsumer.accept(ConnectionInfo.parseAddress(address, defaultPort));
	}

	@After
	public void tearDown() {
		if(null != this.connection)
			this.connection.disposeNow();
	}

}
