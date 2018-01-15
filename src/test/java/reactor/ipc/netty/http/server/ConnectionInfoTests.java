/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.http.server;


import java.util.function.Consumer;

import io.netty.handler.codec.http.HttpMethod;
import org.junit.After;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientRequest;

import static org.assertj.core.api.Assertions.assertThat;

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
				clientRequest -> {
				},
				serverRequest -> {
					assertThat(serverRequest.hostAddress().getHostString()).isIn("0:0:0:0:0:0:0:1", "127.0.0.1");
					assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.connection.address().getPort());
				});
	}

	@Test
	public void forwardedHost() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("Forwarded", "host=192.168.0.1");
				},
				serverRequest -> {
					assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("192.168.0.1");
					assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.connection.address().getPort());
				});
	}

	@Test
	public void forwardedHostIpV6() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("Forwarded", "host=[1abc:2abc:3abc::5ABC:6abc]");
				},
				serverRequest -> {
					assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.connection.address().getPort());
				});
	}

	@Test
	public void xForwardedFor() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("X-Forwarded-For", "[1abc:2abc:3abc::5ABC:6abc]:8080, 192.168.0.1");
				},
				serverRequest -> {
					assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					assertThat(serverRequest.hostAddress().getPort()).isEqualTo(8080);
				});
	}

	@Test
	public void xForwardedHost() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("X-Forwarded-Host", "[1abc:2abc:3abc::5ABC:6abc], 192.168.0.1");
				},
				serverRequest -> {
					assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("1abc:2abc:3abc:0:0:0:5abc:6abc");
					assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.connection.address().getPort());
				});
	}

	@Test
	public void xForwardedHostAndPort() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("X-Forwarded-Host", "192.168.0.1");
					clientRequest.addHeader("X-Forwarded-Port", "8080");
				},
				serverRequest -> {
					assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("192.168.0.1");
					assertThat(serverRequest.hostAddress().getPort()).isEqualTo(8080);
				});
	}

	@Test
	public void forwardedMultipleHosts() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("Forwarded", "host=a.example.com,host=b.example.com, host=c.example.com");
				},
				serverRequest -> {
					assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.connection.address().getPort());
				});
	}

	@Test
	public void forwardedAllDirectives() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("Forwarded", "host=a.example.com:443;proto=https");
				},
				serverRequest -> {
					assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					assertThat(serverRequest.hostAddress().getPort()).isEqualTo(443);
					assertThat(serverRequest.scheme()).isEqualTo("https");
				});
	}

	@Test
	public void forwardedAllDirectivesQuoted() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("Forwarded", "host=\"a.example.com:443\";proto=\"https\"");
				},
				serverRequest -> {
					assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					assertThat(serverRequest.hostAddress().getPort()).isEqualTo(443);
					assertThat(serverRequest.scheme()).isEqualTo("https");
				});
	}

	@Test
	public void forwardedMultipleHeaders() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("Forwarded", "host=a.example.com:443;proto=https");
					clientRequest.addHeader("Forwarded", "host=b.example.com");
				},
				serverRequest -> {
					assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
					assertThat(serverRequest.hostAddress().getPort()).isEqualTo(443);
					assertThat(serverRequest.scheme()).isEqualTo("https");
				});
	}

	@Test
	public void forwardedForHostname() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("Forwarded", "for=\"_gazonk\"");
				},
				serverRequest -> {
					assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("_gazonk");
					assertThat(serverRequest.remoteAddress().getPort()).isPositive();
				});
	}

	@Test
	public void forwardedForIp() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("Forwarded", "for=192.0.2.60;proto=http;by=203.0.113.43");
				},
				serverRequest -> {
					assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("192.0.2.60");
					assertThat(serverRequest.remoteAddress().getPort()).isPositive();
					assertThat(serverRequest.scheme()).isEqualTo("http");
				});
	}

	@Test
	public void forwardedForIpV6() {
		testClientRequest(
				clientRequest -> {
					clientRequest.addHeader("Forwarded", "for=\"[2001:db8:cafe::17]:4711\"");
				},
				serverRequest -> {
					assertThat(serverRequest.remoteAddress().getHostString()).isEqualTo("2001:db8:cafe:0:0:0:0:17");
					assertThat(serverRequest.remoteAddress().getPort()).isEqualTo(4711);
				});
	}


	private void testClientRequest(Consumer<HttpClientRequest> clientConsumer,
			Consumer<HttpServerRequest> serverConsumer) {

		this.connection = HttpServer.create().forwarded().port(0).handler((req, res) -> {
			try {
				serverConsumer.accept(req);
				return res.status(200).sendString(Mono.just("OK"));
			}
			catch (Throwable e) {
				return res.status(500).sendString(Mono.just(e.getMessage()));
			}
		}).bindNow();

		String response =
				HttpClient.prepare()
				          .port(this.connection.address().getPort())
				          .wiretap()
				          .request(HttpMethod.GET)
				          .uri("/test")
				          .send((req, out) -> {
				                  clientConsumer.accept(req);
				                  return req.sendHeaders();
				          })
				          .responseContent()
				          .aggregate()
				          .asString()
				          .block();

		assertThat(response).isEqualTo("OK");
	}

	@After
	public void tearDown() {
		this.connection.dispose();
	}

}