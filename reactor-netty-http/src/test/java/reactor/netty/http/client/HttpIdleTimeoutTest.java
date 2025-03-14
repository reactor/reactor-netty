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
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.HttpProtocol.H2;
import static reactor.netty.http.HttpProtocol.H2C;
import static reactor.netty.http.HttpProtocol.HTTP11;

class HttpIdleTimeoutTest extends BaseHttpTest {

	static SslContext sslServer;
	static SslContext sslClient;

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
				.build();
		sslClient = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE)
				.build();
	}

	@Test
	void maintainConnectionWithoutIdleTimeoutInHttp11() {
		disposableServer = createServer()
				.protocol(HTTP11)
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(HTTP11)
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofSeconds(2))
				.block();

		assertThat(channel.isOpen()).isTrue();
	}

	@Test
	void maintainConnectionWithoutIdleTimeoutInH2C() {
		disposableServer = createServer()
				.protocol(HTTP11, H2C)
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(HTTP11, H2C)
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofSeconds(2))
				.block();

		assertThat(channel.parent().isOpen()).isTrue();
	}

	@Test
	void maintainConnectionWithoutIdleTimeoutInHttp2() {
		disposableServer = createServer()
				.protocol(H2)
				.secure(spec -> spec.sslContext(sslServer))
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(H2)
				.secure(spec -> spec.sslContext(sslClient))
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofSeconds(2))
				.block();

		assertThat(channel.parent().isOpen()).isTrue();
	}

	@Test
	void idleTimeoutInHttp11() {
		disposableServer = createServer()
				.protocol(HTTP11)
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(HTTP11)
				.idleTimeout(Duration.ofSeconds(3))
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofSeconds(2))
				.block();

		assertThat(channel.isOpen()).isTrue();
	}

	@Test
	void idleTimeoutInH2C() {
		disposableServer = createServer()
				.protocol(HTTP11, H2C)
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(HTTP11, H2C)
				.idleTimeout(Duration.ofSeconds(3))
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofSeconds(2))
				.block();

		assertThat(channel.parent().isOpen()).isTrue();
	}

	@Test
	void idleTimeoutInHttp2() {
		disposableServer = createServer()
				.protocol(H2)
				.secure(spec -> spec.sslContext(sslServer))
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(H2)
				.secure(spec -> spec.sslContext(sslClient))
				.idleTimeout(Duration.ofSeconds(3))
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofSeconds(2))
				.block();

		assertThat(channel.parent().isOpen()).isTrue();
	}

	@Test
	void closeAfterIdleTimeoutInHttp11() {
		disposableServer = createServer()
				.protocol(HTTP11)
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(HTTP11)
				.idleTimeout(Duration.ofSeconds(2))
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofSeconds(3))
				.block();

		assertThat(channel.isOpen()).isFalse();
	}

	@Test
	void closeAfterIdleTimeoutInH2C() {
		disposableServer = createServer()
				.protocol(HTTP11, H2C)
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(HTTP11, H2C)
				.idleTimeout(Duration.ofSeconds(2))
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofSeconds(3))
				.block();

		assertThat(channel.parent().isOpen()).isFalse();
	}

	@Test
	void closeAfterIdleTimeoutInHttp2() {
		disposableServer = createServer()
				.protocol(H2)
				.secure(spec -> spec.sslContext(sslServer))
				.handle((req, resp) -> resp.sendString(Mono.just("Test")))
				.bindNow();

		Channel channel = createClient(disposableServer::address)
				.protocol(H2)
				.secure(spec -> spec.sslContext(sslClient))
				.idleTimeout(Duration.ofSeconds(2))
				.get()
				.uri("/")
				.responseConnection((conn, receiver) -> Mono.just(receiver.channel()))
				.single()
				.block();

		Mono.delay(Duration.ofSeconds(3))
				.block();

		assertThat(channel.parent().isOpen()).isFalse();
	}
}
