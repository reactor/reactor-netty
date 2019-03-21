/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * @author Violeta Georgieva
 */
final class AccessLogHandler extends ChannelDuplexHandler {

	AccessLog accessLog = new AccessLog();

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof HttpRequest) {
			final HttpRequest request = (HttpRequest) msg;

			accessLog = new AccessLog()
			        .address(((SocketChannel) ctx.channel()).remoteAddress().getHostString())
			        .port(((SocketChannel) ctx.channel()).localAddress().getPort())
			        .method(request.method().name())
			        .uri(request.uri())
			        .protocol(request.protocolVersion().text());
		}
		super.channelRead(ctx, msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof HttpResponse) {
			final HttpResponse response = (HttpResponse) msg;
			HttpResponseStatus status = response.status();
			if (status.equals(HttpResponseStatus.CONTINUE)) {
				ctx.write(msg, promise);
				return;
			}
			boolean chunked = HttpUtil.isTransferEncodingChunked(response);
			accessLog.status(status.code())
			         .chunked(chunked);
			if (!chunked) {
				accessLog.contentLength(HttpUtil.getContentLength(response, -1));
			}
		}
		if (msg instanceof LastHttpContent) {
			accessLog.increaseContentLength(((LastHttpContent) msg).content().readableBytes());
			ctx.write(msg, promise)
			   .addListener(future -> {
			       if (future.isSuccess()) {
			           accessLog.log();
			       }
			   });
			return;
		}
		if (msg instanceof ByteBuf) {
			accessLog.increaseContentLength(((ByteBuf) msg).readableBytes());
		}
		if (msg instanceof ByteBufHolder) {
			accessLog.increaseContentLength(((ByteBufHolder) msg).content().readableBytes());
		}
		ctx.write(msg, promise);
	}

	static final class AccessLog {
		static final Logger log = Loggers.getLogger("reactor.netty.http.server.AccessLog");
		static final DateTimeFormatter DATE_TIME_FORMATTER =
				DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.US);
		static final String COMMON_LOG_FORMAT =
				"{} - {} [{}] \"{} {} {}\" {} {} {} {} ms";
		static final String MISSING = "-";

		final String zonedDateTime;

		String address;
		String method;
		String uri;
		String protocol;
		String user = MISSING;
		int status;
		long contentLength;
		boolean chunked;
		long startTime = System.currentTimeMillis();
		int port;

		AccessLog() {
			this.zonedDateTime = ZonedDateTime.now().format(DATE_TIME_FORMATTER);
		}

		AccessLog address(String address) {
			this.address = Objects.requireNonNull(address, "address");
			return this;
		}

		AccessLog port(int port) {
			this.port = port;
			return this;
		}

		AccessLog method(String method) {
			this.method = Objects.requireNonNull(method, "method");
			return this;
		}

		AccessLog uri(String uri) {
			this.uri = Objects.requireNonNull(uri, "uri");
			return this;
		}

		AccessLog protocol(String protocol) {
			this.protocol = Objects.requireNonNull(protocol, "protocol");
			return this;
		}

		AccessLog status(int status) {
			this.status = status;
			return this;
		}

		AccessLog contentLength(long contentLength) {
			this.contentLength = contentLength;
			return this;
		}

		AccessLog increaseContentLength(long contentLength) {
			if (chunked) {
				this.contentLength += contentLength;
			}
			return this;
		}

		AccessLog chunked(boolean chunked) {
			this.chunked = chunked;
			return this;
		}

		long duration() {
			return System.currentTimeMillis() - startTime;
		}

		void log() {
			if (log.isInfoEnabled()) {
				log.info(COMMON_LOG_FORMAT, address, user, zonedDateTime,
						method, uri, protocol, status, (contentLength > -1 ? contentLength : MISSING), port, duration());
			}
		}
	}
}
