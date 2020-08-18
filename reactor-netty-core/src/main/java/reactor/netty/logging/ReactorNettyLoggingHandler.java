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
package reactor.netty.logging;

import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * Extends {@link LoggingHandler} and logs all events in a
 * textual representation so it's human readable and less verbose.
 *
 * Hint: Logger escapes newlines as "\n" to reduce output.
 *
 * @author Maximilian Goeke
 * @since 1.0.0
 */
public final class ReactorNettyLoggingHandler extends LoggingHandler {

	private final Charset charset;

	/**
	 * Creates a new instance with the specified logger name and charset.
	 *
	 * @param name    the name of the class to use for the logger
	 * @param level   the log level
	 * @param charset the charset used to decode the ByteBuf
	 */
	ReactorNettyLoggingHandler(final String name, final LogLevel level, final Charset charset) {
		super(name, level);
		this.charset = charset;
	}

	@Override
	protected String format(ChannelHandlerContext ctx, String eventName, Object arg) {
		if (arg instanceof ByteBuf) {
			return formatByteBuf(ctx, eventName, (ByteBuf) arg);
		}
		else if (arg instanceof ByteBufHolder) {
			return formatByteBuf(ctx, eventName, ((ByteBufHolder) arg).content());
		}
		else {
			return super.format(ctx, eventName, arg);
		}
	}

	/* Format of message is:
	 * <CHANNEL> <EVENT_NAME>: <MESSAGE_LENGTH_IN_BYTES>B <CONTENT_OF_BYTE_BUF>
	 *
	 * == Example ==
	 * <Channel>: [id: 0x329c6ffd, L:/127.0.0.1:52640 - R:/127.0.0.1:8080]
	 * <EVENT_NAME>: WRITE
	 * <MESSAGE_LENGTH_IN_BYTES>: 4
	 * <CONTENT_OF_BYTE_BUF>: echo
	 *
	 * result: [id: 0x329c6ffd, L:/127.0.0.1:52640 - R:/127.0.0.1:8080] WRITE: 4B echo
	 */
	private String formatByteBuf(ChannelHandlerContext ctx, String eventName, ByteBuf msg) {
		final String chStr = ctx.channel().toString();
		final int messageLength = msg.readableBytes();
		final String message = msg.toString(charset);

		return new StringBuilder()
			.append(chStr)
			.append(' ')
			.append(eventName)
			.append(": ")
			.append(messageLength)
			.append("B ")
			.append(message)
			.toString();
	}

	/*
	 * The UnsupportedOperationException is thrown to reduce confusion. ReactorNettyLoggingHandler is using
	 * the AdvancedByteBufFormat and not ByteBufFormat.
	 */
	@Override
	public ByteBufFormat byteBufFormat() {
		throw new UnsupportedOperationException("ReactorNettyLoggingHandler isn't using the classic ByteBufFormat.");
	}

}
