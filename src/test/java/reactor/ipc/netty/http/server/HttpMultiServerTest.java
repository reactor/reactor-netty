/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

import java.security.cert.CertificateException;
import javax.net.ssl.SSLException;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.tcp.BlockingNettyContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Simon Baslé
 */
public class HttpMultiServerTest {

	@Test
	public void httpAndHttpsMultiBinding() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient().trustManager(ssc.cert()).build();

		BlockingNettyContext server =
				HttpServer.bindMultiple(bind -> bind.host("localhost")
				                                    .port(4090)
				                                    .sslContext(sslServer))
				          .and(bind -> bind.host("localhost").port(4080))
				          .start((request, response) -> response.sendString(Mono.just(request.uri())));

		HttpClientResponse secureResponse = HttpClient.create(opt -> opt.port(4090)
		                                                                .sslContext(sslClient))
		                                              .get("/foo/secure/")
		                                              .block();

		String secureContent = secureResponse.receiveContent()
		                                     .map(HttpContent::content)
		                                     .as(ByteBufFlux::fromInbound)
		                                     .aggregate()
		                                     .asString()
		                                     .block();

		HttpClientResponse normalResponse = HttpClient.create(opt -> opt.port(4080))
		                                              .get("/bar/normal/")
		                                              .block();

		String normalContent = normalResponse.receiveContent()
		                                     .map(HttpContent::content)
		                                     .as(ByteBufFlux::fromInbound)
		                                     .aggregate()
		                                     .asString()
		                                     .block();

		server.shutdown();

		assertThat(secureResponse.status().code()).isEqualTo(200);
		assertThat(normalResponse.status().code()).isEqualTo(200);
		assertThat(secureContent).isEqualTo("/foo/secure/");
		assertThat(normalContent).isEqualTo("/bar/normal/");
	}

	@Test
	public void httpAndHttpsHybrid() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient().trustManager(ssc.cert()).build();

		BlockingNettyContext server =
				HttpServer.hybridSecure("localhost", 4090, 4080, sslServer)
				          .start((request, response) -> response.sendString(Mono.just(request.uri())));

		HttpClientResponse secureResponse = HttpClient.create(opt -> opt.port(4090)
		                                                                .sslContext(sslClient))
		                                              .get("/foo/secure/")
		                                              .block();

		String secureContent = secureResponse.receiveContent()
		                                     .map(HttpContent::content)
		                                     .as(ByteBufFlux::fromInbound)
		                                     .aggregate()
		                                     .asString()
		                                     .block();

		HttpClientResponse normalResponse = HttpClient.create(opt -> opt.port(4080))
		                                              .get("/bar/normal/")
		                                              .block();

		String normalContent = normalResponse.receiveContent()
		                                     .map(HttpContent::content)
		                                     .as(ByteBufFlux::fromInbound)
		                                     .aggregate()
		                                     .asString()
		                                     .block();

		server.shutdown();

		assertThat(secureResponse.status().code()).isEqualTo(200);
		assertThat(normalResponse.status().code()).isEqualTo(200);
		assertThat(secureContent).isEqualTo("/foo/secure/");
		assertThat(normalContent).isEqualTo("/bar/normal/");
	}

}