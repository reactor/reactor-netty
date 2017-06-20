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

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.AttributeKey;
import reactor.ipc.netty.options.ServerOptions;
import reactor.ipc.netty.resources.LoopResources;

/**
 * Encapsulates configuration options for http server.
 *
 * @author Stephane Maldini
 */
public final class HttpServerOptions extends ServerOptions {

	int minCompressionResponseSize = -1;

	/**
	 * Create a new server builder
	 *
	 * @return a new server builder
	 */
	public static HttpServerOptions create() {
		return new HttpServerOptions();
	}

	HttpServerOptions() {
	}

	HttpServerOptions(HttpServerOptions options) {
		super(options);
		this.minCompressionResponseSize = options.minCompressionResponseSize;
	}

	@Override
	public HttpServerOptions afterChannelInit(Consumer<? super Channel> afterChannelInit) {
		super.afterChannelInit(afterChannelInit);
		return this;
	}

	@Override
	public <T> ServerOptions attr(AttributeKey<T> key, T value) {
		super.attr(key, value);
		return this;
	}

	@Override
	public HttpServerOptions channelGroup(ChannelGroup channelGroup) {
		super.channelGroup(channelGroup);
		return this;
	}

	@Override
	public HttpServerOptions loopResources(LoopResources eventLoopSelector) {
		super.loopResources(eventLoopSelector);
		return this;
	}

	@Override
	public HttpServerOptions duplicate() {
		return new HttpServerOptions(this);
	}

	@Override
	public HttpServerOptions eventLoopGroup(EventLoopGroup eventLoopGroup) {
		super.eventLoopGroup(eventLoopGroup);
		return this;
	}

	@Override
	public HttpServerOptions listen(String host, int port) {
		super.listen(host, port);
		return this;
	}

	@Override
	public HttpServerOptions listen(InetSocketAddress listenAddress) {
		super.listen(listenAddress);
		return this;
	}

	@Override
	public HttpServerOptions listen(int port) {
		super.listen(port);
		return this;
	}

	@Override
	public HttpServerOptions listen(String host) {
		super.listen(host);
		return this;
	}

	@Override
	public HttpServerOptions onChannelInit(Predicate<? super Channel> onChannelInit) {
		super.onChannelInit(onChannelInit);
		return this;
	}

	@Override
	public <T> HttpServerOptions option(ChannelOption<T> key, T value) {
		super.option(key, value);
		return this;
	}

	@Override
	public <T> HttpServerOptions selectorAttr(AttributeKey<T> key, T value) {
		super.selectorAttr(key, value);
		return this;
	}

	@Override
	public <T> HttpServerOptions selectorOption(ChannelOption<T> key, T value) {
		super.selectorOption(key, value);
		return this;
	}

	@Override
	public HttpServerOptions preferNative(boolean preferNative) {
		super.preferNative(preferNative);
		return this;
	}

	@Override
	public HttpServerOptions sslContext(SslContext sslContext) {
		super.sslContext(sslContext);
		return this;
	}

	@Override
	public HttpServerOptions sslHandshakeTimeout(Duration sslHandshakeTimeout) {
		super.sslHandshakeTimeout(sslHandshakeTimeout);
		return this;
	}

	@Override
	public HttpServerOptions sslHandshakeTimeoutMillis(long sslHandshakeTimeoutMillis) {
		super.sslHandshakeTimeoutMillis(sslHandshakeTimeoutMillis);
		return this;
	}

	@Override
	public HttpServerOptions sslSelfSigned(Consumer<? super SslContextBuilder> configurator) {
		super.sslSelfSigned(configurator);
		return this;
	}

	@Override
	public HttpServerOptions sslSelfSigned() {
		super.sslSelfSigned();
		return this;
	}

	/**
	 * Enable GZip response compression if the client request presents accept encoding
	 * headers
	 *
	 * @param enabled true whether compression is enabled
	 *
	 * @return this builder
	 */
	public HttpServerOptions compression(boolean enabled) {
		this.minCompressionResponseSize = enabled ? 0 : -1;
		return this;
	}

	/**
	 * Enable GZip response compression if the client request presents accept encoding
	 * headers
	 * AND the response reaches a minimum threshold
	 *
	 * @param minResponseSize compression is performed once response size exceeds given
	 * value in byte
	 *
	 * @return this builder
	 */
	public HttpServerOptions compression(int minResponseSize) {
		if (minResponseSize < 0) {
			throw new IllegalArgumentException("minResponseSize must be positive");
		}
		this.minCompressionResponseSize = minResponseSize;
		return this;
	}

	@Override
	public String asSimpleString() {
		StringBuilder s = new StringBuilder(super.asSimpleString());

		if (minCompressionResponseSize >= 0) {
			s.append(", gzip");
			if (minCompressionResponseSize > 0) {
				s.append( " over ").append(minCompressionResponseSize).append(" bytes");
			}
		}

		return s.toString();
	}

	@Override
	public String asDetailedString() {
		return super.asDetailedString() +
				", minCompressionResponseSize=" + minCompressionResponseSize;
	}

	@Override
	public String toString() {
		return "HttpServerOptions{" + asDetailedString() + "}";
	}
}
