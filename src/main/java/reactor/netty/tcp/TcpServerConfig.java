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
package reactor.netty.tcp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.handler.logging.LoggingHandler;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.ConnectionObserver;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.ServerTransportConfig;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Encapsulate all necessary configuration for TCP server transport. The public API is read-only.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class TcpServerConfig extends ServerTransportConfig<TcpServerConfig> {

	@Override
	public ChannelOperations.OnSetup channelOperationsProvider() {
		return DEFAULT_OPS;
	}

	/**
	 * Returns true if that {@link TcpServer} secured via SSL transport
	 *
	 * @return true if that {@link TcpServer} secured via SSL transport
	 */
	public final boolean isSecure(){
		return sslProvider != null;
	}

	/**
	 * Returns the current {@link SslProvider} if that {@link TcpServer} secured via SSL
	 * transport or null
	 *
	 * @return the current {@link SslProvider} if that {@link TcpServer} secured via SSL
	 * transport or null
	 */
	@Nullable
	public SslProvider sslProvider() {
		return sslProvider;
	}


	// Protected/Package private write API

	SslProvider sslProvider;

	TcpServerConfig(Map<ChannelOption<?>, ?> options, Map<ChannelOption<?>, ?> childOptions,
	                Supplier<? extends SocketAddress> localAddress) {
		super(options, childOptions, localAddress);
	}

	TcpServerConfig(TcpServerConfig parent) {
		super(parent);
		this.sslProvider = parent.sslProvider;
	}

	@Override
	protected ChannelFactory<? extends Channel> connectionFactory(EventLoopGroup elg) {
		return new ReflectiveChannelFactory<>(loopResources().onServerChannel(elg));
	}

	@Override
	protected LoggingHandler defaultLoggingHandler() {
		return LOGGING_HANDLER;
	}

	@Override
	protected LoopResources defaultLoopResources() {
		return TcpResources.get();
	}

	@Override
	protected ChannelMetricsRecorder defaultMetricsRecorder() {
		return MicrometerTcpServerMetricsRecorder.INSTANCE;
	}

	@Override
	protected ChannelPipelineConfigurer defaultOnChannelInit() {
		ChannelPipelineConfigurer _default = super.defaultOnChannelInit();
		if (sslProvider != null) {
			return _default.then(new TcpServerChannelInitializer(sslProvider));
		}
		else {
			return _default;
		}
	}

	static final ChannelOperations.OnSetup DEFAULT_OPS = (ch, c, msg) -> new ChannelOperations<>(ch, c);

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(TcpServer.class);

	/**
	 * Default value whether the SSL debugging on the server side will be enabled/disabled,
	 * fallback to SSL debugging disabled
	 */
	static final boolean SSL_DEBUG = Boolean.parseBoolean(System.getProperty(ReactorNetty.SSL_SERVER_DEBUG, "false"));

	static final class MicrometerTcpServerMetricsRecorder extends MicrometerChannelMetricsRecorder {

		static final MicrometerTcpServerMetricsRecorder INSTANCE =
				new MicrometerTcpServerMetricsRecorder(reactor.netty.Metrics.TCP_SERVER_PREFIX, "tcp");

		MicrometerTcpServerMetricsRecorder(String name, String protocol) {
			super(name, protocol);
		}
	}

	static final class TcpServerChannelInitializer implements ChannelPipelineConfigurer {

		final SslProvider sslProvider;

		TcpServerChannelInitializer(SslProvider sslProvider) {
			this.sslProvider = sslProvider;
		}

		@Override
		public void onChannelInit(ConnectionObserver connectionObserver, Channel channel, @Nullable SocketAddress remoteAddress) {
			sslProvider.addSslHandler(channel, remoteAddress, SSL_DEBUG);
		}
	}
}
