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

import java.util.Objects;
import java.util.function.Function;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.util.annotation.Nullable;

import java.util.function.BiPredicate;

import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.DEFAULT_INITIAL_BUFFER_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.DEFAULT_MAX_CHUNK_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.DEFAULT_MAX_HEADER_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.DEFAULT_MAX_INITIAL_LINE_LENGTH;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.DEFAULT_VALIDATE_HEADERS;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.INITIAL_BUFFER_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.MAX_CHUNK_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.MAX_HEADER_SIZE;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.MAX_INITIAL_LINE_LENGTH;
import static reactor.ipc.netty.http.server.HttpRequestDecoderConfiguration.VALIDATE_HEADERS;

/**
 * @author Stephane Maldini
 */
final class HttpServerBind extends HttpServer implements Function<ServerBootstrap, ServerBootstrap> {

	static final HttpServerBind INSTANCE = new HttpServerBind();

	final TcpServer tcpServer;

	HttpServerBind() {
		this(DEFAULT_TCP_SERVER);
	}

	HttpServerBind(TcpServer tcpServer) {
		this.tcpServer = Objects.requireNonNull(tcpServer, "tcpServer");
	}

	@Override
	protected TcpServer tcpConfiguration() {
		return tcpServer;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<? extends DisposableServer> bind(TcpServer delegate) {
		return delegate.bootstrap(this)
		               .bind();
	}

	@Override
	public ServerBootstrap apply(ServerBootstrap b) {
		if (b.config()
		     .group() == null) {
			LoopResources loops = HttpResources.get();

			boolean useNative =
					LoopResources.DEFAULT_NATIVE && !(tcpConfiguration().sslContext() instanceof JdkSslContext);

			EventLoopGroup selector = loops.onServerSelect(useNative);
			EventLoopGroup elg = loops.onServer(useNative);

			b.group(selector, elg)
			 .channel(loops.onServerChannel(elg));
		}

		Integer minCompressionSize = getAttributeValue(b, PRODUCE_GZIP, null);

		Integer line = getAttributeValue(b, MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_INITIAL_LINE_LENGTH);

		Integer header = getAttributeValue(b, MAX_HEADER_SIZE, DEFAULT_MAX_HEADER_SIZE);

		Integer chunk = getAttributeValue(b, MAX_CHUNK_SIZE, DEFAULT_MAX_CHUNK_SIZE);

		Boolean validate = getAttributeValue(b, VALIDATE_HEADERS, DEFAULT_VALIDATE_HEADERS);

		Integer buffer = getAttributeValue(b, INITIAL_BUFFER_SIZE, DEFAULT_INITIAL_BUFFER_SIZE);

		ChannelOperations.OnSetup ops = BootstrapHandlers.channelOperationFactory(b);

		BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.HttpInitializer,
				(listener, channel) -> {
					ChannelPipeline p = channel.pipeline();

					p.addLast(NettyPipeline.HttpCodec, new HttpServerCodec(line, header, chunk, validate, buffer));

					BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate =
							compressPredicate(channel);

					int minResponseSize = minCompressionSize != null ? minCompressionSize.intValue() : -1;

					boolean alwaysCompress = compressPredicate == null && minResponseSize == 0;
					if(alwaysCompress) {
						p.addLast(NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
					}

					p.addLast(NettyPipeline.HttpServerHandler,
							new HttpServerHandler(ops, listener));
				});
		return b;
	}

	private BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate(
			Channel channel) {

		Attribute<Integer> minCompressionSize = channel.attr(HttpServerBind.PRODUCE_GZIP);
		final int minResponseSize;
		if (minCompressionSize != null && minCompressionSize.get() != null) {
			minResponseSize = minCompressionSize.get();
		}
		else {
			minResponseSize = -1;
		}

		Attribute<BiPredicate<HttpServerRequest, HttpServerResponse>> predicate =
				channel.attr(HttpServerBind.PRODUCE_GZIP_PREDICATE);
		final BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate;
		if (predicate != null && predicate.get() != null) {
			compressionPredicate = predicate.get();
		}
		else {
			compressionPredicate = null;
		}

		if (minResponseSize <= 0){
			if (compressionPredicate != null) {
				return compressionPredicate;
			}
			else {
				return null;
			}
		}

		BiPredicate<HttpServerRequest, HttpServerResponse> lengthPredicate =
				(req, res) -> {
					String length = res.responseHeaders()
							.get(HttpHeaderNames.CONTENT_LENGTH);

					if (length == null) {
						return true;
					}

					try {
						return Long.parseLong(length) >= minResponseSize;
					}
					catch (NumberFormatException nfe) {
						return true;
					}
				};

		if (compressionPredicate == null) {
			return lengthPredicate;
		}

		return lengthPredicate.and(compressionPredicate);
	}
	static final AttributeKey<Integer> PRODUCE_GZIP =
			AttributeKey.newInstance("produceGzip");
	static final AttributeKey<BiPredicate<HttpServerRequest, HttpServerResponse>> PRODUCE_GZIP_PREDICATE =
			AttributeKey.newInstance("produceGzipPredicate");

	@SuppressWarnings("unchecked")
	@Nullable
	static  <T> T getAttributeValue(ServerBootstrap bootstrap, AttributeKey<T>
			attributeKey, @Nullable T defaultValue) {
		T result = bootstrap.config().attrs().get(attributeKey) != null
				? (T) bootstrap.config().attrs().get(attributeKey)
				: defaultValue;
		bootstrap.attr(attributeKey, null);
		return result;
	}
}
