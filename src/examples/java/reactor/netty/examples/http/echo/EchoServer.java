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
package reactor.netty.examples.http.echo;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.TEXT_PLAIN;

/**
 * An HTTP server that expects POST request and sends back the content of the received HTTP request.
 *
 * @author Violeta Georgieva
 */
public final class EchoServer {

	static final boolean SECURE = System.getProperty("secure") != null;
	static final int PORT = Integer.parseInt(System.getProperty("port", SECURE ? "8443" : "8080"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;
	static final boolean COMPRESS = System.getProperty("compress") != null;
	static final boolean HTTP2 = System.getProperty("http2") != null;

	public static void main(String[] args) throws Exception {
		HttpServer server =
				HttpServer.create()
				          .port(PORT)
				          .wiretap(WIRETAP)
				          .compress(COMPRESS)
				          .route(r -> r.post("/echo",
				                  (req, res) -> res.header(CONTENT_TYPE, TEXT_PLAIN)
				                                   .send(req.receive().retain())));

		if (SECURE) {
			SelfSignedCertificate ssc = new SelfSignedCertificate();
			server = server.secure(
					spec -> spec.sslContext(SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())));
		}

		if (HTTP2) {
			server = server.protocol(HttpProtocol.H2);
		}

		server.bindNow()
		      .onDispose()
		      .block();
	}
}
