/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.http.snoop;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.NettyOutbound;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.util.Arrays;
import java.util.List;

/**
 * An HTTP server that receives any request and tells the client the details of the request in a formatted string.
 * @author Kun.Long
 **/
public class HttpSnoopServer {

	public static void main(String[] args) {
		HttpSnoopServer server = new HttpSnoopServer(8080);
		server.startup();
	}

	/**
	 * the port of the server
	 **/
	private final int port;

	/**
	 * the server instance
	 **/
	private DisposableServer server;

	public HttpSnoopServer(int port) {
		this.port = port;
	}

	/**
	 * startup the server
	 **/
	public void startup() {
		// 1.create and config the server instance
		HttpServer server = HttpServer
				.create()
				.port(port)
				.wiretap(false);

		// 2.config the route rule, this server will receive any request that has any path and any method,
		// and then send back the details of the received HTTP request
		server = server.route(routes -> routes.route(
				req -> true,
				this::parseRequestAndSendResponse
		));

		// 3. start the server
		this.server = server.bindNow();

		// 4. block the main thread to keep the server running
		this.server.onDispose().block();
	}

	private NettyOutbound parseRequestAndSendResponse(HttpServerRequest request, HttpServerResponse response) {
		StringBuilder buf = new StringBuilder();
		buf.append("request received, the details are: \n\n");

		// append the request method, uri and protocol version
		buf.append("your host: ").append(request.hostAddress()).append("\n");
		buf.append("http method: ").append(request.method().name()).append("\n");
		buf.append("http version: ").append(request.version().text()).append("\n");
		buf.append("request path: ").append(request.path()).append("\n\n");

		// append the request headers
		buf.append("headers: \n");
		request.requestHeaders().forEach(entry -> {
			buf.append(entry.getKey());
			buf.append(" : ");
			buf.append(entry.getValue());
			buf.append("\n");
		});
		buf.append("\n");

		// append the query parameters
		buf.append("query parameters: \n");
		new QueryStringDecoder(request.uri()).parameters().forEach((key, value) -> {
			buf.append(key);
			buf.append(" : ");
			buf.append(value);
			buf.append("\n");
		});
		buf.append("\n");

		// append the request content if there is any
		buf.append("content: \n");
		String contentType = request.requestHeaders().get(HttpHeaderNames.CONTENT_TYPE);
		Mono<String> responseContent;
		if (this.isATextualContentType(contentType)) {
			responseContent = request.receive().aggregate().asString()
				   .onErrorReturn("")
				   .switchIfEmpty(Mono.just(""))
			       .flatMap(content -> {
						buf.append(content);
						buf.append("\n");
						return Mono.just(buf.toString());
					});
		} else {
			buf.append("there is no content or the content is not textual, so we don't print it here\n");
			responseContent = Mono.just(buf.toString());
		}

		// 6. send the response
		response.status(HttpResponseStatus.OK);
		response.header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);

		return response.sendString(responseContent);
	}

	/**
	 * check whether the content type is textual
	 **/
	private boolean isATextualContentType(String contentType) {
		if (contentType == null) {
			return false;
		}

		for (String textBaseContentType : TEXTUAL_CONTENT_TYPE) {
			if (contentType.startsWith(textBaseContentType)) {
				return true;
			}
		}
		return false;
	}

	private static final List<String> TEXTUAL_CONTENT_TYPE = Arrays.asList(
			"text/plain",
			"text/html",
			"text/xml",
			"text/css",
			"text/javascript",
			"application/json",
			"application/xml",
			"application/javascript",
			"application/x-javascript",
			"application/xhtml+xml",
			"application/x-www-form-urlencoded"
	);
}
