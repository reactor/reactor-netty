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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.util.AttributeKey;
import org.jspecify.annotations.Nullable;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.http.Http3SettingsSpec;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http3.Http3.newQuicServerCodecBuilder;
import static reactor.netty.ReactorNetty.format;

final class Http3ChannelInitializer extends ChannelInitializer<Channel> {

	static final Logger log = Loggers.getLogger(Http3ChannelInitializer.class);

	final Map<AttributeKey<?>, ?>     attributes;
	final Map<AttributeKey<?>, ?>     childAttributes;
	final Map<ChannelOption<?>, ?>    childOptions;
	final ConnectionObserver          connectionObserver;
	final @Nullable Http3SettingsSpec http3Settings;
	final @Nullable ChannelHandler    loggingHandler;
	final Map<ChannelOption<?>, ?>    options;
	final ChannelInitializer<Channel> quicChannelInitializer;
	final QuicSslContext              quicSslContext;

	Http3ChannelInitializer(HttpServerConfig config, ChannelInitializer<Channel> quicChannelInitializer,
			ConnectionObserver connectionObserver) {
		this.attributes = config.attributes();
		this.childAttributes = config.childAttributes();
		this.childOptions = config.childOptions();
		this.connectionObserver = connectionObserver;
		this.http3Settings = config.http3SettingsSpec();
		this.loggingHandler = config.loggingHandler();
		this.options = config.options();
		this.quicChannelInitializer = quicChannelInitializer;
		if (config.sslProvider != null && config.sslProvider.getSslContext() instanceof QuicSslContext) {
			this.quicSslContext = (QuicSslContext) config.sslProvider.getSslContext();
		}
		else {
			throw new IllegalArgumentException("The configured SslContext is not QuicSslContext");
		}
	}

	@Override
	protected void initChannel(Channel channel) {
		ChannelInitializer<Channel> wrappedInitializer = new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel ch) {
				if (ch instanceof QuicChannel) {
					ch.pipeline().addLast(new Http3ConnectionObserverBridge(connectionObserver));
				}
				ch.pipeline().addLast(quicChannelInitializer);
			}
		};

		QuicServerCodecBuilder quicServerCodecBuilder =
				newQuicServerCodecBuilder().sslContext(quicSslContext)
				                           .handler(wrappedInitializer);

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

	static final class Http3ConnectionObserverBridge extends io.netty.channel.ChannelInboundHandlerAdapter {

		final ConnectionObserver connectionObserver;

		Http3ConnectionObserverBridge(ConnectionObserver connectionObserver) {
			this.connectionObserver = connectionObserver;
		}

		@Override
		public void channelActive(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
			if (ctx.channel() instanceof QuicChannel) {
				if (log.isDebugEnabled()) {
					log.debug(format(ctx.channel(), "HTTP/3: Firing State.CONNECTED for QuicChannel"));
				}
				Connection connection = Connection.from(ctx.channel());
				connectionObserver.onStateChange(connection, ConnectionObserver.State.CONNECTED);
			}
			super.channelActive(ctx);
		}
	}
}