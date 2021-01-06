/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ByteBufFlux}
 *
 * @author Silvano Riz
 */
class ByteBufFluxTest extends BaseHttpTest {

	@Test
	void testByteBufFluxFromPathWithoutSecurity() throws Exception {
		doTestByteBufFluxFromPath(false);
	}

	@Test
	void testByteBufFluxFromPathWithSecurity() throws Exception {
		doTestByteBufFluxFromPath(true);
	}

	private void doTestByteBufFluxFromPath(boolean withSecurity) throws Exception {
		HttpServer server = createServer();
		HttpClient client = createClient(() -> disposableServer.address());
		if (withSecurity) {
			SelfSignedCertificate ssc = new SelfSignedCertificate();
			SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
			SslContext sslClient = SslContextBuilder.forClient()
			                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
			server = server.secure(ssl -> ssl.sslContext(sslServer));
			client = client.secure(ssl -> ssl.sslContext(sslClient));
		}

		Path path = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		disposableServer = server.handle((req, res) ->
		                                   res.send(ByteBufFlux.fromPath(path))
		                                      .then())
		                        .bindNow();

		AtomicLong counter = new AtomicLong(0);
		client.get()
		      .uri("/download")
		      .responseContent()
		      .doOnNext(b -> counter.addAndGet(b.readableBytes()))
		      .blockLast(Duration.ofSeconds(30));

		assertThat(counter.get()).isEqualTo(1245);
	}
}