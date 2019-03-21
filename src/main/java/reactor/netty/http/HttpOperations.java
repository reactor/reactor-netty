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

package reactor.netty.http;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;

/**
 * An HTTP ready {@link ChannelOperations} with state management for status and headers
 * (first HTTP response packet).
 *
 * @author Stephane Maldini
 */
public abstract class HttpOperations<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
		extends ChannelOperations<INBOUND, OUTBOUND> implements HttpInfos {

	volatile int statusAndHeadersSent = 0;

	static final int READY        = 0;
	static final int HEADERS_SENT = 1;
	static final int BODY_SENT    = 2;

	protected HttpOperations(HttpOperations<INBOUND, OUTBOUND> replaced) {
		super(replaced);
		this.statusAndHeadersSent = replaced.statusAndHeadersSent;
	}

	protected HttpOperations(Connection connection, ConnectionObserver listener) {
		super(connection, listener);
		//reset channel to manual read if re-used
		connection.channel()
		          .config()
		          .setAutoRead(false);
	}

	/**
	 * Has headers been sent
	 *
	 * @return true if headers have been sent
	 */
	public final boolean hasSentHeaders() {
		return statusAndHeadersSent != READY;
	}

	@Override
	public boolean isWebsocket() {
		return false;
	}

	@Override
	public Mono<Void> then() {
		if (hasSentHeaders()) {
			return Mono.empty();
		}

		return FutureMono.deferFuture(() -> {
			if (markSentHeaders()) {
				HttpMessage msg;

				if (HttpUtil.isContentLengthSet(outboundHttpMessage())) {
					outboundHttpMessage().headers()
							.remove(HttpHeaderNames.TRANSFER_ENCODING);
					if (HttpUtil.getContentLength(outboundHttpMessage(), 0) == 0) {
						markSentBody();
						msg = newFullEmptyBodyMessage();
					}
					else {
						msg = outboundHttpMessage();
					}
				}
				else {
					msg = outboundHttpMessage();
				}

				preSendHeadersAndStatus();

				return channel().writeAndFlush(msg);
			}
			else {
				return channel().newSucceededFuture();
			}
		});
	}

	protected abstract void preSendHeadersAndStatus();

	protected abstract HttpMessage newFullEmptyBodyMessage();

	@Override
	public final NettyOutbound sendFile(Path file, long position, long count) {
		Objects.requireNonNull(file);

		if (hasSentHeaders()) {
			return super.sendFile(file, position, count);
		}

		if (!HttpUtil.isTransferEncodingChunked(outboundHttpMessage()) && !HttpUtil.isContentLengthSet(
				outboundHttpMessage()) && count < Integer.MAX_VALUE) {
			outboundHttpMessage().headers()
			                     .setInt(HttpHeaderNames.CONTENT_LENGTH, (int) count);
		}
		else if (!HttpUtil.isContentLengthSet(outboundHttpMessage())) {
			outboundHttpMessage().headers()
			                     .remove(HttpHeaderNames.CONTENT_LENGTH)
			                     .remove(HttpHeaderNames.TRANSFER_ENCODING);
			HttpUtil.setTransferEncodingChunked(outboundHttpMessage(), true);
		}

		return super.sendFile(file, position, count);
	}

	@Override
	public String toString() {
		if (isWebsocket()) {
			return "ws{uri=" + uri()+", connection="+connection()+"}";
		}

		return method().name() + "{uri=" + uri()+", connection="+connection()+"}";
	}

	@Override
	public HttpOperations<INBOUND, OUTBOUND> addHandler(String name, ChannelHandler handler) {
		super.addHandler(name, handler);

		if(channel().pipeline().context(handler) == null){
			return this;
		}

		autoAddHttpExtractor(this, name, handler);
		return this;
	}

	static void autoAddHttpExtractor(Connection c, String name, ChannelHandler handler){

		if (handler instanceof ByteToMessageDecoder
				|| handler instanceof ByteToMessageCodec
				|| handler instanceof CombinedChannelDuplexHandler) {
			String extractorName = name+"$extractor";

			if(c.channel().pipeline().context(extractorName) != null){
				return;
			}

			c.channel().pipeline().addBefore(name, extractorName, HTTP_EXTRACTOR);

			if(c.isPersistent()){
				c.onTerminate().subscribe(null, null, () -> c.removeHandler(extractorName));
			}

		}
	}

	/**
	 * Mark the headers sent
	 *
	 * @return true if marked for the first time
	 */
	protected final boolean markSentHeaders() {
		return HTTP_STATE.compareAndSet(this, READY, HEADERS_SENT);
	}

	/**
	 * Mark the body sent
	 *
	 * @return true if marked for the first time
	 */
	protected final boolean markSentBody() {
		return HTTP_STATE.compareAndSet(this, HEADERS_SENT, BODY_SENT);
	}

	/**
	 * Mark the headers and body sent
	 *
	 * @return true if marked for the first time
	 */
	protected final boolean markSentHeaderAndBody() {
		return HTTP_STATE.compareAndSet(this, READY, BODY_SENT);
	}

	protected boolean isKeepAlive(HttpMessage message) {
		boolean connectionClose = message.headers().containsValue(HttpHeaderNames.CONNECTION,
				HttpHeaderValues.CLOSE, true);
		if (connectionClose) {
			return false;
		}

		if (message.protocolVersion().isKeepAliveDefault()) {
			return true;
		} else {
			return message.headers().containsValue(HttpHeaderNames.CONNECTION,
					HttpHeaderValues.KEEP_ALIVE, true);
		}
	}

	/**
	 * Outbound Netty HttpMessage
	 *
	 * @return Outbound Netty HttpMessage
	 */
	protected abstract HttpMessage outboundHttpMessage();

	final static AtomicIntegerFieldUpdater<HttpOperations> HTTP_STATE =
			AtomicIntegerFieldUpdater.newUpdater(HttpOperations.class,
					"statusAndHeadersSent");

	final static ChannelInboundHandler HTTP_EXTRACTOR = NettyPipeline.inboundHandler(
			(ctx, msg) -> {
				if (msg instanceof ByteBufHolder) {
					if(msg instanceof FullHttpMessage){
						// TODO convert into 2 messages if FullHttpMessage
						ctx.fireChannelRead(msg);
					}
					else {
						ByteBuf bb = ((ByteBufHolder) msg).content();
						ctx.fireChannelRead(bb);
						if (msg instanceof LastHttpContent) {
							ctx.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
						}
					}
				}
				else {
					ctx.fireChannelRead(msg);
				}
			}
	);
}
