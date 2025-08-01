/*
 * Copyright (c) 2024-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.quic.QuicChannel;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.http.logging.HttpMessageLogFactory;
import reactor.netty.http.server.compression.HttpCompressionOptionsSpec;

import java.net.SocketAddress;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

final class Http3ServerOperations extends HttpServerOperations {

	Http3ServerOperations(HttpServerOperations replaced) {
		super(replaced);
	}

	Http3ServerOperations(
			Connection c,
			ConnectionObserver listener,
			HttpRequest nettyRequest,
			@Nullable HttpCompressionOptionsSpec compressionOptions,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate,
			ConnectionInfo connectionInfo,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			HttpServerFormDecoderProvider formDecoderProvider,
			HttpMessageLogFactory httpMessageLogFactory,
			boolean isHttp2,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			boolean secured,
			ZonedDateTime timestamp) {
		super(c, listener, nettyRequest, compressionOptions, compressionPredicate, connectionInfo, decoder, encoder, formDecoderProvider,
				httpMessageLogFactory, isHttp2, mapHandle, readTimeout, requestTimeout, secured, timestamp, true);
	}

	@Override
	public @Nullable SocketAddress connectionHostAddress() {
		return ((QuicChannel) channel().parent()).localSocketAddress();
	}

	@Override
	public @Nullable SocketAddress connectionRemoteAddress() {
		return ((QuicChannel) channel().parent()).remoteSocketAddress();
	}

	@Override
	public String protocol() {
		return H3.text();
	}

	@Override
	public HttpVersion version() {
		if (nettyRequest != null) {
			return H3;
		}
		throw new IllegalStateException("request not parsed");
	}

	static final HttpVersion H3 = HttpVersion.valueOf("HTTP/3.0");
}
