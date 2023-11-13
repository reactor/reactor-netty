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
package reactor.netty.http.client;

import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.logging.LogLevel;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpClientConfig;
import reactor.netty.transport.ProxyProvider;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * This class provides a migration for the {@link HttpClient#tcpConfiguration(Function)} in 0.9.x.
 *
 * @author Violeta Georgieva
 * @deprecated Use {@link HttpClient} methods for TCP level configurations. This class will be removed in version 1.1.0.
 */
@Deprecated
final class HttpClientTcpConfig extends TcpClient {
	HttpClient httpClient;

	HttpClientTcpConfig(HttpClient httpClient) {
		this.httpClient = httpClient;
	}

	@Override
	public <A> TcpClient attr(AttributeKey<A> key, @Nullable A value) {
		httpClient = httpClient.attr(key, value);
		return this;
	}

	@Override
	public TcpClient bindAddress(Supplier<? extends SocketAddress> bindAddressSupplier) {
		httpClient = httpClient.bindAddress(bindAddressSupplier);
		return this;
	}

	@Override
	public TcpClient channelGroup(ChannelGroup channelGroup) {
		httpClient = httpClient.channelGroup(channelGroup);
		return this;
	}

	@Override
	public TcpClientConfig configuration() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Mono<? extends Connection> connect() {
		throw new UnsupportedOperationException();
	}

	@Override
	public TcpClient doOnChannelInit(ChannelPipelineConfigurer doOnChannelInit) {
		httpClient = httpClient.doOnChannelInit(doOnChannelInit);
		return this;
	}

	@Override
	public TcpClient doOnConnect(Consumer<? super TcpClientConfig> doOnConnect) {
		throw new UnsupportedOperationException();
	}

	@Override
	public TcpClient doOnConnected(Consumer<? super Connection> doOnConnected) {
		httpClient = httpClient.doOnConnected(doOnConnected);
		return this;
	}

	@Override
	public TcpClient doOnDisconnected(Consumer<? super Connection> doOnDisconnected) {
		httpClient = httpClient.doOnDisconnected(doOnDisconnected);
		return this;
	}

	@Override
	public TcpClient handle(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler) {
		throw new UnsupportedOperationException();
	}

	@Override
	public TcpClient host(String host) {
		httpClient = httpClient.host(host);
		return this;
	}

	@Override
	public TcpClient metrics(boolean enable) {
		httpClient = httpClient.metrics(enable, Function.identity());
		return this;
	}

	@Override
	public TcpClient metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder) {
		httpClient = httpClient.metrics(enable, recorder);
		return this;
	}

	@Override
	public TcpClient noProxy() {
		httpClient = httpClient.noProxy();
		return this;
	}

	@Override
	public TcpClient noSSL() {
		httpClient = httpClient.noSSL();
		return this;
	}

	@Override
	public TcpClient observe(ConnectionObserver observer) {
		httpClient = httpClient.observe(observer);
		return this;
	}

	@Override
	public <O> TcpClient option(ChannelOption<O> key, @Nullable O value) {
		httpClient = httpClient.option(key, value);
		return this;
	}

	@Override
	public TcpClient port(int port) {
		httpClient = httpClient.port(port);
		return this;
	}

	@Override
	public TcpClient proxy(Consumer<? super ProxyProvider.TypeSpec> proxyOptions) {
		httpClient = httpClient.proxy(proxyOptions);
		return this;
	}

	@Override
	public TcpClient remoteAddress(Supplier<? extends SocketAddress> remoteAddressSupplier) {
		httpClient = httpClient.remoteAddress(remoteAddressSupplier);
		return this;
	}

	@Override
	public TcpClient resolver(AddressResolverGroup<?> resolver) {
		httpClient = httpClient.resolver(resolver);
		return this;
	}

	@Override
	public TcpClient runOn(EventLoopGroup eventLoopGroup) {
		httpClient = httpClient.runOn(eventLoopGroup);
		return this;
	}

	@Override
	public TcpClient runOn(LoopResources channelResources) {
		httpClient = httpClient.runOn(channelResources);
		return this;
	}

	@Override
	public TcpClient runOn(LoopResources loopResources, boolean preferNative) {
		httpClient = httpClient.runOn(loopResources, preferNative);
		return this;
	}

	@Override
	public TcpClient secure() {
		httpClient = httpClient.secure();
		return this;
	}

	@Override
	public TcpClient secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		httpClient = httpClient.secure(sslProviderBuilder);
		return this;
	}

	@Override
	public TcpClient secure(SslProvider sslProvider) {
		httpClient = httpClient.secure(sslProvider);
		return this;
	}

	@Override
	public TcpClient wiretap(boolean enable) {
		httpClient = httpClient.wiretap(enable);
		return this;
	}

	@Override
	public TcpClient wiretap(String category) {
		httpClient = httpClient.wiretap(category);
		return this;
	}

	@Override
	public TcpClient wiretap(String category, LogLevel level) {
		httpClient = httpClient.wiretap(category, level);
		return this;
	}

	@Override
	protected TcpClient duplicate() {
		throw new UnsupportedOperationException();
	}
}
