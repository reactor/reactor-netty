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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.incubator.codec.http3.Http3DataFrame;
import io.netty.incubator.codec.http3.Http3GoAwayFrame;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import io.netty.incubator.codec.http3.Http3SettingsFrame;
import io.netty.incubator.codec.http3.Http3UnknownFrame;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty.ReactorNetty.format;

final class Http3FrameLoggingHandler extends ChannelDuplexHandler {

	enum Direction {
		INBOUND,
		OUTBOUND
	}

	static final int BUFFER_LENGTH_THRESHOLD = 64;

	static final Logger logger = Loggers.getLogger("reactor.netty.http.server.h3");

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Http3DataFrame) {
			logDataFrame(Direction.INBOUND, ctx, (Http3DataFrame) msg);
		}
		else if (msg instanceof Http3HeadersFrame) {
			logHeadersFrame(Direction.INBOUND, ctx, (Http3HeadersFrame) msg);
		}
		else if (msg instanceof Http3GoAwayFrame) {
			logGoAwayFrame(Direction.INBOUND, ctx, (Http3GoAwayFrame) msg);
		}
		else if (msg instanceof Http3SettingsFrame) {
			logSettingsFrame(Direction.INBOUND, ctx, (Http3SettingsFrame) msg);
		}
		else if (msg instanceof Http3UnknownFrame) {
			logUnknownFrame(Direction.INBOUND, ctx, (Http3UnknownFrame) msg);
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public boolean isSharable() {
		return true;
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof Http3DataFrame) {
			logDataFrame(Direction.OUTBOUND, ctx, (Http3DataFrame) msg);
		}
		else if (msg instanceof Http3HeadersFrame) {
			logHeadersFrame(Direction.OUTBOUND, ctx, (Http3HeadersFrame) msg);
		}
		else if (msg instanceof Http3GoAwayFrame) {
			logGoAwayFrame(Direction.OUTBOUND, ctx, (Http3GoAwayFrame) msg);
		}
		else if (msg instanceof Http3SettingsFrame) {
			logSettingsFrame(Direction.OUTBOUND, ctx, (Http3SettingsFrame) msg);
		}
		else if (msg instanceof Http3UnknownFrame) {
			logUnknownFrame(Direction.OUTBOUND, ctx, (Http3UnknownFrame) msg);
		}

		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}

	boolean isEnabled() {
		return logger.isDebugEnabled();
	}

	void logDataFrame(Direction direction, ChannelHandlerContext ctx, Http3DataFrame msg) {
		if (isEnabled()) {
			logger.debug(format(ctx.channel(), "{} DATA: streamId={} length={} bytes={}"),
					direction.name(), streamId(ctx.channel()), msg.content().readableBytes(), toString(msg.content()));
		}
	}

	void logHeadersFrame(Direction direction, ChannelHandlerContext ctx, Http3HeadersFrame msg) {
		if (isEnabled()) {
			logger.debug(format(ctx.channel(), "{} HEADERS: streamId={} headers={}"),
					direction.name(), streamId(ctx.channel()), msg.headers());
		}
	}

	void logGoAwayFrame(Direction direction, ChannelHandlerContext ctx, Http3GoAwayFrame msg) {
		if (isEnabled()) {
			logger.debug(format(ctx.channel(), "{} GO_AWAY: length={}"),
					direction.name(), msg.id());
		}
	}

	void logSettingsFrame(Direction direction, ChannelHandlerContext ctx, Http3SettingsFrame msg) {
		if (isEnabled()) {
			logger.debug(format(ctx.channel(), "{} SETTINGS: settings={}"),
					direction.name(), msg);
		}
	}

	void logUnknownFrame(Direction direction, ChannelHandlerContext ctx, Http3UnknownFrame msg) {
		if (isEnabled()) {
			logger.debug(format(ctx.channel(), "{} UNKNOWN: frameType={} streamId={} length={} bytes={}"),
					direction.name(), msg.type() & 0xFF, streamId(ctx.channel()), msg.length(), toString(msg.content()));
		}
	}

	static long streamId(Channel channel) {
		return channel instanceof QuicStreamChannel ? ((QuicStreamChannel) channel).streamId() : -1;
	}

	static String toString(ByteBuf buffer) {
		return buffer.readableBytes() <= BUFFER_LENGTH_THRESHOLD ?
				ByteBufUtil.hexDump(buffer, buffer.readerIndex(), buffer.readableBytes()) :
				ByteBufUtil.hexDump(buffer, buffer.readerIndex(), BUFFER_LENGTH_THRESHOLD) + "...";
	}
}
