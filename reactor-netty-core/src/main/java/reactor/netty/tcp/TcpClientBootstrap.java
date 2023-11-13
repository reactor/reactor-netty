/*
 * Copyright (c) 2017-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.tcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AttributeKey;
import io.netty.util.internal.SocketUtils;
import reactor.netty.NettyPipeline;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Function;

/**
 * This class provides a migration for the {@link TcpClient#bootstrap(Function)} in 0.9.x.
 *
 * @author Violeta Georgieva
 * @deprecated Use {@link TcpClient} methods for configurations. This class will be removed in version 1.1.0.
 */
@Deprecated
final class TcpClientBootstrap extends Bootstrap {
	TcpClient tcpClient;

	TcpClientBootstrap(TcpClient tcpClient) {
		this.tcpClient = tcpClient;
	}

	@Override
	public <T> Bootstrap attr(AttributeKey<T> key, T value) {
		tcpClient = tcpClient.attr(key, value);
		return this;
	}

	@Override
	public ChannelFuture bind() {
		throw new UnsupportedOperationException();
	}

	@Override
	public ChannelFuture bind(InetAddress inetHost, int inetPort) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ChannelFuture bind(int inetPort) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ChannelFuture bind(SocketAddress localAddress) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ChannelFuture bind(String inetHost, int inetPort) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bootstrap channel(Class<? extends Channel> channelClass) {
		throw new UnsupportedOperationException();
	}

	@Override
	@SuppressWarnings("deprecation")
	public Bootstrap channelFactory(io.netty.bootstrap.ChannelFactory<? extends Channel> channelFactory) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bootstrap channelFactory(io.netty.channel.ChannelFactory<? extends Channel> channelFactory) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bootstrap clone() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bootstrap clone(EventLoopGroup group) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ChannelFuture connect() {
		throw new UnsupportedOperationException();
	}

	@Override
	public ChannelFuture connect(InetAddress inetHost, int inetPort) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ChannelFuture connect(SocketAddress remoteAddress) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ChannelFuture connect(String inetHost, int inetPort) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean equals(Object obj) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected void finalize() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bootstrap group(EventLoopGroup group) {
		tcpClient = tcpClient.runOn(group);
		return this;
	}

	@Override
	public Bootstrap handler(ChannelHandler handler) {
		tcpClient = tcpClient.doOnChannelInit((connectionObserver, channel, remoteAddress) ->
				channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, null, handler));
		return this;
	}

	@Override
	public int hashCode() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bootstrap localAddress(InetAddress inetHost, int inetPort) {
		tcpClient = tcpClient.bindAddress(() -> new InetSocketAddress(inetHost, inetPort));
		return this;
	}

	@Override
	public Bootstrap localAddress(int inetPort) {
		tcpClient = tcpClient.bindAddress(() -> new InetSocketAddress(inetPort));
		return this;
	}

	@Override
	public Bootstrap localAddress(SocketAddress localAddress) {
		tcpClient = tcpClient.bindAddress(() -> localAddress);
		return this;
	}

	@Override
	public Bootstrap localAddress(String inetHost, int inetPort) {
		tcpClient = tcpClient.bindAddress(() -> SocketUtils.socketAddress(inetHost, inetPort));
		return this;
	}

	@Override
	public <T> Bootstrap option(ChannelOption<T> option, T value) {
		tcpClient = tcpClient.option(option, value);
		return this;
	}

	@Override
	public ChannelFuture register() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bootstrap remoteAddress(InetAddress inetHost, int inetPort) {
		tcpClient = tcpClient.remoteAddress(() -> new InetSocketAddress(inetHost, inetPort));
		return this;
	}

	@Override
	public Bootstrap remoteAddress(SocketAddress remoteAddress) {
		tcpClient = tcpClient.remoteAddress(() -> remoteAddress);
		return this;
	}

	@Override
	public Bootstrap remoteAddress(String inetHost, int inetPort) {
		tcpClient = tcpClient.host(inetHost).port(inetPort);
		return this;
	}

	@Override
	public Bootstrap resolver(AddressResolverGroup<?> resolver) {
		tcpClient = tcpClient.resolver(resolver);
		return this;
	}

	@Override
	public String toString() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bootstrap validate() {
		throw new UnsupportedOperationException();
	}
}
