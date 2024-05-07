/*
 * Copyright (c) 2020-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.resolver.AddressResolverGroup;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.ConnectionObserver;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.ClientTransportConfig;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Encapsulate all necessary configuration for TCP client transport. The public API is read-only.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class TcpClientConfig extends ClientTransportConfig<TcpClientConfig> {

	@Override
	public int channelHash() {
		int result = super.channelHash();
		result = 31 * result + Objects.hashCode(sslProvider);
		return result;
	}

	@Override
	public ChannelOperations.OnSetup channelOperationsProvider() {
		return DEFAULT_OPS;
	}

	/**
	 * Return true if that {@link TcpClient} secured via SSL transport.
	 *
	 * @return true if that {@link TcpClient} secured via SSL transport
	 */
	public final boolean isSecure() {
		return sslProvider != null;
	}

	/**
	 * Return the current {@link SslProvider} if that {@link TcpClient} secured via SSL
	 * transport or null.
	 *
	 * @return the current {@link SslProvider} if that {@link TcpClient} secured via SSL
	 * transport or null
	 */
	@Nullable
	public SslProvider sslProvider() {
		return sslProvider;
	}


	// Protected/Package private write API

	SslProvider sslProvider;

	TcpClientConfig(ConnectionProvider connectionProvider, Map<ChannelOption<?>, ?> options,
	                Supplier<? extends SocketAddress> remoteAddress) {
		super(connectionProvider, options, remoteAddress);
	}

	TcpClientConfig(TcpClientConfig parent) {
		super(parent);
		this.sslProvider = parent.sslProvider;
	}

	/**
	 * Provides a global {@link AddressResolverGroup} from {@link TcpResources}
	 * that is shared amongst all TCP clients. {@link AddressResolverGroup} uses the global
	 * {@link LoopResources} from {@link TcpResources}.
	 *
	 * @return the global {@link AddressResolverGroup}
	 */
	@Override
	protected AddressResolverGroup<?> defaultAddressResolverGroup() {
		return TcpResources.get().getOrCreateDefaultResolver();
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
		return MicrometerTcpClientMetricsRecorder.INSTANCE;
	}

	@Override
	protected ChannelPipelineConfigurer defaultOnChannelInit() {
		ChannelPipelineConfigurer _default = super.defaultOnChannelInit();
		if (sslProvider != null) {
			return _default.then(new TcpClientChannelInitializer(sslProvider));
		}
		else {
			return _default;
		}
	}

	static final ChannelOperations.OnSetup DEFAULT_OPS = (ch, c, msg) -> new ChannelOperations<>(ch, c);

	static final LoggingHandler LOGGING_HANDLER =
			AdvancedByteBufFormat.HEX_DUMP
					.toLoggingHandler(TcpClient.class.getName(), LogLevel.DEBUG, Charset.defaultCharset());

	/**
	 * Default value whether the SSL debugging on the client side will be enabled/disabled,
	 * fallback to SSL debugging disabled.
	 */
	static final boolean SSL_DEBUG = Boolean.parseBoolean(System.getProperty(ReactorNetty.SSL_CLIENT_DEBUG, "false"));

	static final class MicrometerTcpClientMetricsRecorder extends MicrometerChannelMetricsRecorder {

		static final MicrometerTcpClientMetricsRecorder INSTANCE = new MicrometerTcpClientMetricsRecorder();

		MicrometerTcpClientMetricsRecorder() {
			super(reactor.netty.Metrics.TCP_CLIENT_PREFIX, "tcp", false);
		}
	}

	static final class TcpClientChannelInitializer implements ChannelPipelineConfigurer {

		final SslProvider sslProvider;

		TcpClientChannelInitializer(SslProvider sslProvider) {
			this.sslProvider = sslProvider;
		}

		@Override
		public void onChannelInit(ConnectionObserver connectionObserver, Channel channel, @Nullable SocketAddress remoteAddress) {
			sslProvider.addSslHandler(channel, remoteAddress, SSL_DEBUG);
		}
	}
}
