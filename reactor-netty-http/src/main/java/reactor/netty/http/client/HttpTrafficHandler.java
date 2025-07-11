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
package reactor.netty.http.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;

import static io.netty.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_ISSUED;
import static io.netty.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_REJECTED;
import static io.netty.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_SUCCESSFUL;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTINGS_ENABLE_CONNECT_PROTOCOL;
import static reactor.netty.ReactorNetty.format;
import static reactor.netty.http.client.Http2ConnectionProvider.OWNER;
import static reactor.netty.http.client.Http2ConnectionProvider.http2PooledRef;
import static reactor.netty.http.client.Http2ConnectionProvider.invalidate;
import static reactor.netty.http.client.HttpClientConnect.ENABLE_CONNECT_PROTOCOL;

/**
 * {@link ChannelInboundHandlerAdapter} prior {@link reactor.netty.channel.ChannelOperationsHandler}
 * for handling H2/H2C use cases. HTTP/1.x use cases are delegated to
 * {@link reactor.netty.channel.ChannelOperationsHandler} without any interference.
 * <p>
 * Once the channel is activated, the upgrade is decided (H2C or HTTP/1.1) and in case H2/H2C the first SETTINGS frame
 * is received, this handler is not needed any more. Thus said {@link #channelInactive(ChannelHandlerContext)}
 * is invoked only in case there are issues with the connection itself.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
final class HttpTrafficHandler extends ChannelInboundHandlerAdapter {
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
		ConnectionObserver owner = ctx.channel().attr(OWNER).get();
		if (owner instanceof Http2ConnectionProvider.DisposableAcquire) {
			Http2Pool.Http2PooledRef http2PooledRef = http2PooledRef(((Http2ConnectionProvider.DisposableAcquire) owner).pooledRef);
			if (http2PooledRef.slot.h2cUpgradeHandlerCtx() != null &&
					http2PooledRef.slot.http2MultiplexHandlerCtx() == null) {
				// Connection close happened before H2C upgrade
				invalidate(owner);
			}
		}

		ctx.fireExceptionCaught(new PrematureCloseException("Connection prematurely closed BEFORE response"));
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
		Channel channel = ctx.channel();
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
			ctx.pipeline().remove(this);
		}
		else if (evt == UPGRADE_REJECTED) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "The upgrade to H2C protocol was rejected, continue using HTTP/1.x protocol."));
			}
			sendNewState(Connection.from(channel), HttpClientState.UPGRADE_REJECTED);
			ctx.pipeline().remove(this);
		}
		ctx.fireUserEventTriggered(evt);
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
}
