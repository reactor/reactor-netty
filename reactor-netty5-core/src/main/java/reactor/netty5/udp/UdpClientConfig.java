/*
 * Copyright (c) 2020-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.udp;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.ServerChannel;
import io.netty5.channel.ServerChannelFactory;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.handler.logging.LogLevel;
import io.netty5.handler.logging.LoggingHandler;
import io.netty5.resolver.AddressResolverGroup;
import reactor.netty5.channel.ChannelMetricsRecorder;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.channel.MicrometerChannelMetricsRecorder;
import reactor.netty5.resources.ConnectionProvider;
import reactor.netty5.resources.LoopResources;
import reactor.netty5.transport.ClientTransportConfig;
import reactor.netty5.transport.logging.AdvancedBufferFormat;
import reactor.util.annotation.Nullable;

import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Encapsulate all necessary configuration for UDP client transport. The public API is read-only.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class UdpClientConfig extends ClientTransportConfig<UdpClientConfig> {

	@Override
	public final ChannelOperations.OnSetup channelOperationsProvider() {
		return DEFAULT_OPS;
	}


	// Protected/Package private write API

	UdpClientConfig(ConnectionProvider connectionProvider, Map<ChannelOption<?>, ?> options,
			Supplier<? extends SocketAddress> remoteAddress) {
		super(connectionProvider, options, remoteAddress);
	}

	UdpClientConfig(UdpClientConfig parent) {
		super(parent);
	}

	@Override
	protected Class<? extends Channel> channelType() {
		return DatagramChannel.class;
	}

	/**
	 * Provides a global {@link AddressResolverGroup} from {@link UdpResources}
	 * that is shared amongst all UDP clients. {@link AddressResolverGroup} uses the global
	 * {@link LoopResources} from {@link UdpResources}.
	 *
	 * @return the global {@link AddressResolverGroup}
	 */
	@Override
	protected AddressResolverGroup<?> defaultAddressResolverGroup() {
		return UdpResources.get().getOrCreateDefaultResolver();
	}

	@Override
	protected LoggingHandler defaultLoggingHandler() {
		return LOGGING_HANDLER;
	}

	@Override
	protected LoopResources defaultLoopResources() {
		return UdpResources.get();
	}

	@Override
	protected ChannelMetricsRecorder defaultMetricsRecorder() {
		return MicrometerUdpClientMetricsRecorder.INSTANCE;
	}

	@Override
	protected Class<? extends ServerChannel> serverChannelType() {
		throw new UnsupportedOperationException();
	}

	@Override
	protected ServerChannelFactory<? extends ServerChannel> serverConnectionFactory(@Nullable ProtocolFamily protocolFamily) {
		throw new UnsupportedOperationException();
	}

	static final ChannelOperations.OnSetup DEFAULT_OPS = (ch, c, msg) -> new UdpOperations(ch, c);

	static final LoggingHandler LOGGING_HANDLER =
			AdvancedBufferFormat.HEX_DUMP
					.toLoggingHandler(UdpClient.class.getName(), LogLevel.DEBUG, Charset.defaultCharset());

	static final class MicrometerUdpClientMetricsRecorder extends MicrometerChannelMetricsRecorder {

		static final MicrometerUdpClientMetricsRecorder INSTANCE = new MicrometerUdpClientMetricsRecorder();

		MicrometerUdpClientMetricsRecorder() {
			super(reactor.netty5.Metrics.UDP_CLIENT_PREFIX, "udp");
		}
	}
}
