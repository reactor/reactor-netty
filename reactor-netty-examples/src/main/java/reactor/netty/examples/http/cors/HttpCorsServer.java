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
package reactor.netty.examples.http.cors;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.security.cert.CertificateException;
import java.time.Duration;

import reactor.netty.Connection;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.Http3SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * An HTTP server that handles CORS (Cross-Origin Resource Sharing) requests.
 *
 * @author Jack Cheng
 */
public class HttpCorsServer {

	static final boolean SECURE = System.getProperty("secure") != null;
	static final int PORT = Integer.parseInt(System.getProperty("port", SECURE ? "8443" : "8080"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;
	static final boolean COMPRESS = System.getProperty("compress") != null;
	static final boolean HTTP2 = System.getProperty("http2") != null;
	static final boolean HTTP3 = System.getProperty("http3") != null;

	public static void main(String... args) throws CertificateException {
		HttpServer server =
				HttpServer.create()
				          .port(PORT)
				          .wiretap(WIRETAP)
				          .compress(COMPRESS)
				          .doOnConnection(HttpCorsServer::addCorsHandler)
				          .route(routes -> routes.route(r -> true, HttpCorsServer::okResponse));

		if (SECURE) {
			SelfSignedCertificate ssc = new SelfSignedCertificate("localhost");
			if (HTTP2) {
				server = server.secure(spec -> spec.sslContext(Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())));
			}
			else if (HTTP3) {
				server = server.secure(spec -> spec.sslContext(Http3SslContextSpec.forServer(ssc.key(), null, ssc.cert())));
			}
			else {
				server = server.secure(spec -> spec.sslContext(Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())));
			}
		}

		if (HTTP2) {
			server = server.protocol(HttpProtocol.H2);
		}

		if (HTTP3) {
			server =
					server.protocol(HttpProtocol.HTTP3)
					      .http3Settings(spec -> spec.idleTimeout(Duration.ofSeconds(5))
					                                 .maxData(10000000)
					                                 .maxStreamDataBidirectionalLocal(1000000)
					                                 .maxStreamDataBidirectionalRemote(1000000)
					                                 .maxStreamsBidirectional(100));
		}

		server.bindNow()
		      .onDispose()
		      .block();
	}

	private static NettyOutbound okResponse(HttpServerRequest request, HttpServerResponse response) {
		response.status(HttpResponseStatus.OK);
		response.header("custom-response-header", "Some value");
		return response;
	}

	private static void addCorsHandler(Connection connection) {
		CorsConfig corsConfig = CorsConfigBuilder.forOrigin("example.com").allowNullOrigin().allowCredentials().allowedRequestHeaders("custom-request-header").build();
		if (HTTP2) {
			connection.channel().pipeline().addAfter(NettyPipeline.H2ToHttp11Codec, "Cors", new CorsHandler(corsConfig));
		}
		else if (HTTP3) {
			connection.channel().pipeline().addAfter(NettyPipeline.H3ToHttp11Codec, "Cors", new CorsHandler(corsConfig));
		}
		else {
			connection.channel().pipeline().addAfter(NettyPipeline.HttpCodec, "Cors", new CorsHandler(corsConfig));
		}
	}
}
