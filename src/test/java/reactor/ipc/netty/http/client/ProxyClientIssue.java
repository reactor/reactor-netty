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

package reactor.ipc.netty.http.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.http.server.HttpServerRequest;
import reactor.ipc.netty.http.server.HttpServerResponse;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static java.util.Collections.addAll;
import static java.util.Locale.ENGLISH;

/**
 * A manual test for https://github.com/reactor/reactor-netty/issues/163
 */
public class ProxyClientIssue {

	private static final Set<String> headersRemovedOnRequest =
			new HashSet<>(Arrays.asList("proxy-connection",
					"connection",
					"keep-alive",
					"transfer-encoding",
					"te",
					"trailer",
					"proxy-authorization",
					"proxy-authenticate",
					"upgrade"));

	private static final Set<String> headersRemovedOnResponse =
			new HashSet<>(headersRemovedOnRequest);

	static {
		addAll(headersRemovedOnResponse, "date", "server");
	}

	private static int PROXY_PORT          = 1082;
	private static int CONTENT_SERVER_PORT = 2379;

	@Test
	@Ignore
	public void startContentServer() throws Exception {
		Random random = new Random(0);
		byte[] content = new byte[1024 * 10];
		random.nextBytes(content);

		HttpServer server = HttpServer.create()
		                              .port(CONTENT_SERVER_PORT)
		                              .tcpConfiguration(tcpServer -> tcpServer.host("0.0.0.0")
		                                                                      .option(ChannelOption.SO_LINGER,
				                                                        -1));

		server.router(routes -> routes.get("/**",
				(req, res) -> res.header("Content-length", String.valueOf(content.length))
				                 .header("Content-type", "application/octet-stream")
				                 .header("Connection", "Close")
				                 .sendByteArray(Flux.just(content))))
				.wiretap()
				.bindNow();

	}

	@Test
	@Ignore
	public void startEmulatingClients() throws Exception {

		int concurrencyLevel = 10;
		int requestCount = 100;

		List<Mono<Void>> parallelFlows = new ArrayList<>();
		for (int i = 0; i < concurrencyLevel; i++) {
			Mono<Void> currentRequestFlow = Mono.empty();
			for (int j = 0; j < requestCount; j++) {
				currentRequestFlow = currentRequestFlow.then(HttpClient.prepare()
				                                                       .wiretap()
				                                                       .get()
				                                                       .uri("http://localhost:" + PROXY_PORT + "/0/content/" + ThreadLocalRandom.current()
				                                                                                                                                .nextInt(
				                                                                                                                                        1000))
				                                                       .response((clientResponse, buf) -> {
							                                                       HttpResponseStatus
									                                                       statusCode =
									                                                       clientResponse.status();
							                                                       if (statusCode != OK) {
								                                                       System.out.println(
										                                                       "Unsuccessful status code: " + statusCode);
							                                                       }
							                                                       else {
								                                                       System.out.println(
										                                                       ".");
							                                                       }
							                                                       return buf;
						                                                       })
				                                                       .then());
			}
			parallelFlows.add(currentRequestFlow);
		}
		Flux.merge(parallelFlows)
		    .blockLast();
	}

	@Test
	@Ignore
	public void startProxyServer() throws Exception {
		HttpServer server = HttpServer.create()
		                              .port(PROXY_PORT)
		                              .tcpConfiguration(tcpServer -> tcpServer.host("0.0.0.0"));

		server.router(routes -> routes.get("/0/**", this::proxy))
		      .wiretap()
		      .bindNow();
	}

	private Mono<Void> proxy(HttpServerRequest request, HttpServerResponse response) {
		return HttpClient.prepare()
		                 .wiretap()
		                 .headers(h -> h.add(filterRequestHeaders(request.requestHeaders())))
		                 .get()
		                 .uri(URI.create("http://localhost:" + CONTENT_SERVER_PORT +
						                 "/" + request.path())
		                         .toString())

		                 .response((targetResponse, buf) -> response.headers(filterResponseHeaders(targetResponse.responseHeaders()))
		                                                    .send(buf.retain())
		                                                    .then())
		                 .then();
	}

	private HttpHeaders filterRequestHeaders(HttpHeaders httpHeaders) {
		return filterHeaders(httpHeaders, headersRemovedOnRequest);
	}
	private HttpHeaders filterResponseHeaders(HttpHeaders headers) {
		return filterHeaders(headers, headersRemovedOnResponse);
	}

	private HttpHeaders filterHeaders(HttpHeaders headers, Set<String> removedHeaders) {
		return headers.entries()
		              .stream()
		              .filter(entry -> !removedHeaders.contains(entry.getKey()
		                                                             .toLowerCase(ENGLISH)))
		              .collect(DefaultHttpHeaders::new,
				              (h, e) -> h.set(e.getKey(), e.getValue()),
				              HttpHeaders::set);
	}
}
