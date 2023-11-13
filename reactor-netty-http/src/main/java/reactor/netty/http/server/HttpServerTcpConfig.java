/*
 * Copyright (c) 2018-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.logging.LogLevel;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpServer;
import reactor.netty.tcp.TcpServerConfig;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * This class provides a migration for the {@link HttpServer#tcpConfiguration(Function)} in 0.9.x.
 *
 * @author Violeta Georgieva
 * @deprecated Use {@link HttpServer} methods for TCP level configurations. This class will be removed in version 1.1.0.
 */
@Deprecated
final class HttpServerTcpConfig extends TcpServer {
	HttpServer httpServer;

	HttpServerTcpConfig(HttpServer httpServer) {
		this.httpServer = httpServer;
	}

	@Override
	public <A> TcpServer attr(AttributeKey<A> key, @Nullable A value) {
		httpServer = httpServer.attr(key, value);
		return this;
	}

	@Override
	public Mono<? extends DisposableServer> bind() {
		throw new UnsupportedOperationException();
	}

	@Override
	public TcpServer bindAddress(Supplier<? extends SocketAddress> bindAddressSupplier) {
		httpServer = httpServer.bindAddress(bindAddressSupplier);
		return this;
	}

	@Override
	public TcpServer channelGroup(ChannelGroup channelGroup) {
		httpServer = httpServer.channelGroup(channelGroup);
		return this;
	}

	@Override
	public <A> TcpServer childAttr(AttributeKey<A> key, @Nullable A value) {
		httpServer = httpServer.childAttr(key, value);
		return this;
	}

	@Override
	public TcpServer childObserve(ConnectionObserver observer) {
		httpServer = httpServer.childObserve(observer);
		return this;
	}

	@Override
	public <A> TcpServer childOption(ChannelOption<A> key, @Nullable A value) {
		httpServer = httpServer.childOption(key, value);
		return this;
	}

	@Override
	public TcpServerConfig configuration() {
		throw new UnsupportedOperationException();
	}

	@Override
	public TcpServer doOnBind(Consumer<? super TcpServerConfig> doOnBind) {
		throw new UnsupportedOperationException();
	}

	@Override
	public TcpServer doOnBound(Consumer<? super DisposableServer> doOnBound) {
		httpServer = httpServer.doOnBound(doOnBound);
		return this;
	}

	@Override
	public TcpServer doOnChannelInit(ChannelPipelineConfigurer doOnChannelInit) {
		httpServer = httpServer.doOnChannelInit(doOnChannelInit);
		return this;
	}

	@Override
	public TcpServer doOnConnection(Consumer<? super Connection> doOnConnection) {
		httpServer = httpServer.doOnConnection(doOnConnection);
		return this;
	}

	@Override
	public TcpServer doOnUnbound(Consumer<? super DisposableServer> doOnUnbound) {
		httpServer = httpServer.doOnUnbound(doOnUnbound);
		return this;
	}

	@Override
	public TcpServer handle(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		httpServer = httpServer.handle(handler);
		return this;
	}

	@Override
	public TcpServer host(String host) {
		httpServer = httpServer.host(host);
		return this;
	}

	@Override
	public TcpServer metrics(boolean enable) {
		httpServer = httpServer.metrics(enable, Function.identity());
		return this;
	}

	@Override
	public TcpServer metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder) {
		httpServer = httpServer.metrics(enable, recorder);
		return this;
	}

	@Override
	public TcpServer observe(ConnectionObserver observer) {
		httpServer = httpServer.observe(observer);
		return this;
	}

	@Override
	public <O> TcpServer option(ChannelOption<O> key, @Nullable O value) {
		httpServer = httpServer.option(key, value);
		return this;
	}

	@Override
	public TcpServer noSSL() {
		httpServer = httpServer.noSSL();
		return this;
	}

	@Override
	public TcpServer port(int port) {
		httpServer = httpServer.port(port);
		return this;
	}

	@Override
	public TcpServer runOn(EventLoopGroup eventLoopGroup) {
		httpServer = httpServer.runOn(eventLoopGroup);
		return this;
	}

	@Override
	public TcpServer runOn(LoopResources channelResources) {
		httpServer = httpServer.runOn(channelResources);
		return this;
	}

	@Override
	public TcpServer runOn(LoopResources loopResources, boolean preferNative) {
		httpServer = httpServer.runOn(loopResources, preferNative);
		return this;
	}

	@Override
	public TcpServer secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		httpServer = httpServer.secure(sslProviderBuilder);
		return this;
	}

	@Override
	public TcpServer secure(SslProvider sslProvider) {
		httpServer = httpServer.secure(sslProvider);
		return this;
	}

	@Override
	public TcpServer wiretap(boolean enable) {
		httpServer = httpServer.wiretap(enable);
		return this;
	}

	@Override
	public TcpServer wiretap(String category) {
		httpServer = httpServer.wiretap(category);
		return this;
	}

	@Override
	public TcpServer wiretap(String category, LogLevel level) {
		httpServer = httpServer.wiretap(category, level);
		return this;
	}

	@Override
	protected TcpServer duplicate() {
		throw new UnsupportedOperationException();
	}
}
