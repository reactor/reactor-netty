/*
 * Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.client;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http2.Http2SettingsFrame;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.NettyPipeline;
import reactor.netty5.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;

import static io.netty5.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_ISSUED;
import static io.netty5.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_REJECTED;
import static io.netty5.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_SUCCESSFUL;
import static reactor.netty5.ReactorNetty.format;
import static reactor.netty5.http.client.HttpClientConnect.ENABLE_CONNECT_PROTOCOL;

/**
 * {@link ChannelHandlerAdapter} prior {@link reactor.netty5.channel.ChannelOperationsHandler}
 * for handling H2/H2C use cases. HTTP/1.x use cases are delegated to
 * {@link reactor.netty5.channel.ChannelOperationsHandler} without any interference.
 * <p>
 * Once the channel is activated, the upgrade is decided (H2C or HTTP/1.1) and in case H2/H2C the first SETTINGS frame
 * is received, this handler is not needed any more. Thus said {@link #channelInactive(ChannelHandlerContext)}
 * is invoked only in case there are issues with the connection itself.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
final class HttpTrafficHandler extends ChannelHandlerAdapter {
	final ConnectionObserver listener;

	HttpTrafficHandler(ConnectionObserver listener) {
		this.listener = listener;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		Channel channel = ctx.channel();
		if (channel.isActive()) {
			if (ctx.pipeline().get(NettyPipeline.H2MultiplexHandler) == null) {
				// Proceed with HTTP/1.x as per configuration
				ctx.fireChannelActive();
			}
			else if (ctx.pipeline().get(NettyPipeline.SslHandler) == null) {
				// Proceed with H2C as per configuration
				sendNewState(Connection.from(channel), ConnectionObserver.State.CONNECTED);
				ctx.flush();
				ctx.read();
			}
			else {
				// Proceed with H2 as per configuration
				sendNewState(Connection.from(channel), ConnectionObserver.State.CONNECTED);
			}
		}
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Http2SettingsFrame) {
			ctx.channel().attr(ENABLE_CONNECT_PROTOCOL).set(((Http2SettingsFrame) msg).settings().get(SETTINGS_ENABLE_CONNECT_PROTOCOL));
			sendNewState(Connection.from(ctx.channel()), ConnectionObserver.State.CONFIGURED);
			ctx.pipeline().remove(NettyPipeline.ReactiveBridge);
			ctx.pipeline().remove(this);
			return;
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		ctx.fireChannelExceptionCaught(new PrematureCloseException("Connection prematurely closed BEFORE response"));
	}

	@Override
	public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
		Channel channel = ctx.channel();
		boolean removeThisHandler = false;
		if (evt == UPGRADE_ISSUED) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "An upgrade request was sent to the server."));
			}
		}
		else if (evt == UPGRADE_SUCCESSFUL) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "The upgrade to H2C protocol was successful."));
			}
			sendNewState(Connection.from(channel), HttpClientState.UPGRADE_SUCCESSFUL);
			removeThisHandler = true; // we have to remove ourselves from the pipeline after having fired the event below.
		}
		else if (evt == UPGRADE_REJECTED) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "The upgrade to H2C protocol was rejected, continue using HTTP/1.x protocol."));
			}
			sendNewState(Connection.from(channel), HttpClientState.UPGRADE_REJECTED);
			removeThisHandler = true; // we have to remove ourselves from the pipeline after having fired the event below.
		}
		ctx.fireChannelInboundEvent(evt);
		if (removeThisHandler) {
			ctx.pipeline().remove(this);
		}
	}

	void sendNewState(Connection connection, ConnectionObserver.State state) {
		ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
		if (ops != null) {
			listener.onStateChange(ops, state);
		}
		else {
			listener.onStateChange(connection, state);
		}
	}

	static final Logger log = Loggers.getLogger(HttpTrafficHandler.class);

	// https://datatracker.ietf.org/doc/html/rfc8441#section-9.1
	static final char SETTINGS_ENABLE_CONNECT_PROTOCOL = 8;
}
