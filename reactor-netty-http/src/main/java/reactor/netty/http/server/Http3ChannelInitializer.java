/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.util.AttributeKey;
import reactor.netty.NettyPipeline;
import reactor.netty.http.Http3SettingsSpec;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.netty.incubator.codec.http3.Http3.newQuicServerCodecBuilder;

final class Http3ChannelInitializer extends ChannelInitializer<Channel> {

	final Map<AttributeKey<?>, ?>     attributes;
	final Map<AttributeKey<?>, ?>     childAttributes;
	final Map<ChannelOption<?>, ?>    childOptions;
	final Http3SettingsSpec           http3Settings;
	final ChannelHandler              loggingHandler;
	final Map<ChannelOption<?>, ?>    options;
	final ChannelInitializer<Channel> quicChannelInitializer;
	final QuicSslContext              quicSslContext;

	Http3ChannelInitializer(HttpServerConfig config, ChannelInitializer<Channel> quicChannelInitializer) {
		this.attributes = config.attributes();
		this.childAttributes = config.childAttributes();
		this.childOptions = config.childOptions();
		this.http3Settings = config.http3SettingsSpec();
		this.loggingHandler = config.loggingHandler();
		this.options = config.options();
		this.quicChannelInitializer = quicChannelInitializer;
		if (config.sslProvider.getSslContext() instanceof QuicSslContext) {
			this.quicSslContext = (QuicSslContext) config.sslProvider.getSslContext();
		}
		else {
			throw new IllegalArgumentException("The configured SslContext is not QuicSslContext");
		}
	}

	@Override
	protected void initChannel(Channel channel) {
		QuicServerCodecBuilder quicServerCodecBuilder =
				newQuicServerCodecBuilder().sslContext(quicSslContext)
				                           .handler(quicChannelInitializer);

		if (http3Settings != null) {
			quicServerCodecBuilder.initialMaxData(http3Settings.maxData())
			                      .initialMaxStreamDataBidirectionalLocal(http3Settings.maxStreamDataBidirectionalLocal())
			                      .initialMaxStreamDataBidirectionalRemote(http3Settings.maxStreamDataBidirectionalRemote())
			                      .initialMaxStreamsBidirectional(http3Settings.maxStreamsBidirectional())
			                      .tokenHandler(http3Settings.tokenHandler());

			if (http3Settings.idleTimeout() != null) {
				quicServerCodecBuilder.maxIdleTimeout(http3Settings.idleTimeout().toMillis(), TimeUnit.MILLISECONDS);
			}
		}

		attributes(quicServerCodecBuilder, attributes);
		channelOptions(quicServerCodecBuilder, options);
		streamAttributes(quicServerCodecBuilder, childAttributes);
		streamChannelOptions(quicServerCodecBuilder, childOptions);

		if (loggingHandler != null) {
			channel.pipeline().addLast(NettyPipeline.LoggingHandler, loggingHandler);
			quicServerCodecBuilder.streamHandler(loggingHandler);
		}
		channel.pipeline().addLast(quicServerCodecBuilder.build());

		channel.pipeline().remove(this);
	}

	@SuppressWarnings("unchecked")
	static void attributes(QuicServerCodecBuilder quicServerCodecBuilder, Map<AttributeKey<?>, ?> attrs) {
		for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
			quicServerCodecBuilder.attr((AttributeKey<Object>) e.getKey(), e.getValue());
		}
	}

	@SuppressWarnings({"unchecked", "ReferenceEquality"})
	static void channelOptions(QuicServerCodecBuilder quicServerCodecBuilder, Map<ChannelOption<?>, ?> options) {
		for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
			// ReferenceEquality is deliberate
			if (e.getKey() != ChannelOption.SO_REUSEADDR) {
				quicServerCodecBuilder.option((ChannelOption<Object>) e.getKey(), e.getValue());
			}
		}
	}

	@SuppressWarnings("unchecked")
	static void streamAttributes(QuicServerCodecBuilder quicServerCodecBuilder, Map<AttributeKey<?>, ?> attrs) {
		for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
			quicServerCodecBuilder.streamAttr((AttributeKey<Object>) e.getKey(), e.getValue());
		}
	}

	@SuppressWarnings({"unchecked", "ReferenceEquality"})
	static void streamChannelOptions(QuicServerCodecBuilder quicServerCodecBuilder, Map<ChannelOption<?>, ?> options) {
		for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
			// ReferenceEquality is deliberate
			if (e.getKey() != ChannelOption.TCP_NODELAY) {
				quicServerCodecBuilder.streamOption((ChannelOption<Object>) e.getKey(), e.getValue());
			}
		}
	}
}