/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http.server;

import java.util.Queue;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import reactor.core.Exceptions;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.util.concurrent.Queues;

import static io.netty.handler.codec.http.HttpUtil.*;

/**
 * Replace {@link io.netty.handler.codec.http.HttpServerKeepAliveHandler} with extra
 * handler management.
 */
final class HttpRequestPipeliningHandler extends ChannelDuplexHandler
		implements Runnable, ChannelFutureListener {

	static final String MULTIPART_PREFIX = "multipart";

	final ChannelOperations.OnSetup opsFactory;
	final ConnectionObserver listener;

	boolean persistentConnection = true;
	// Track pending responses to support client pipelining: https://tools.ietf.org/html/rfc7230#section-6.3.2
	int pendingResponses;

	Queue<Object> pipelined;

	ChannelHandlerContext ctx;

	boolean overflow;
	boolean mustRecycleEncoder;

	HttpRequestPipeliningHandler(ChannelOperations.OnSetup opsFactory, ConnectionObserver listener) {
		this.opsFactory = opsFactory;
		this.listener = listener;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		super.handlerAdded(ctx);
		this.ctx = ctx;
		if (HttpServerOperations.log.isDebugEnabled()) {
			HttpServerOperations.log.debug("New http connection, requesting read");
		}
		ctx.read();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		// read message and track if it was keepAlive
		if (msg instanceof HttpRequest) {
			final HttpRequest request = (HttpRequest) msg;

			DecoderResult decoderResult = request.decoderResult();
			if (decoderResult.isFailure()) {
				Throwable cause = decoderResult.cause();
				HttpServerOperations.log.debug("Decoding failed: " + msg + " : ", cause);

				HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_0,
				        cause instanceof TooLongFrameException ? HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE:
				                                                 HttpResponseStatus.BAD_REQUEST);
				response.headers()
				        .setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
				        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
				ctx.writeAndFlush(response)
				   .addListener(ChannelFutureListener.CLOSE);
				return;
			}

			if (persistentConnection) {
				pendingResponses += 1;
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug("Increasing pending responses, now " +
							"{}", pendingResponses);
				}
				persistentConnection = isKeepAlive(request);
			}
			else {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug("dropping pipelined HTTP request, " +
									"previous response requested connection close");
				}
				ReferenceCountUtil.release(msg);
				return;
			}
			if (pendingResponses > 1) {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug("buffering pipelined HTTP request, " +
									"pending response count: {}, queue: {}",
							pendingResponses,
							pipelined != null ? pipelined.size() : 0);
				}
				overflow = true;
				doPipeline(ctx, msg);
				return;
			}
			else {
				overflow = false;
				ChannelOperations<?, ?> ops = opsFactory.create(Connection.from(ctx.channel()), listener, msg);
				if (ops != null) {
					ops.bind();
					ctx.fireChannelRead(msg);
					return;
				}

			}
		}
		else if (persistentConnection && pendingResponses == 0) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug("Dropped HTTP content, " +
								"Since response has been sent already:{}", msg);
			}
			if (msg instanceof LastHttpContent) {
				ctx.fireChannelRead(msg);
			}
			else {
				ReferenceCountUtil.release(msg);
			}
			ctx.read();
			return;
		}
		else if (overflow) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug("buffering pipelined HTTP content, " +
								"pending response count: {}, pending pipeline:{}",
						pendingResponses,
						pipelined != null ? pipelined.size() : 0);
			}
			doPipeline(ctx, msg);
			return;
		}
		ctx.fireChannelRead(msg);
	}

	void doPipeline(ChannelHandlerContext ctx, Object msg) {
		if (pipelined == null) {
			pipelined = Queues.unbounded()
			                  .get();
		}
		if (!pipelined.offer(msg)) {
			ctx.fireExceptionCaught(Exceptions.failWithOverflow());
		}
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		// modify message on way out to add headers if needed
		if (msg instanceof HttpResponse) {
			final HttpResponse response = (HttpResponse) msg;
			trackResponse(response);
			// Assume the response writer knows if they can persist or not and sets isKeepAlive on the response
			if (!isKeepAlive(response) || !isSelfDefinedMessageLength(response)) {
				// No longer keep alive as the client can't tell when the message is done unless we close connection
				pendingResponses = 0;
				persistentConnection = false;
			}
			// Server might think it can keep connection alive, but we should fix response header if we know better
			if (!shouldKeepAlive()) {
				setKeepAlive(response, false);
			}

			if (response.status().equals(HttpResponseStatus.CONTINUE)) {
				ctx.write(msg, promise);
				return;
			}
		}
		if (msg instanceof LastHttpContent) {
			if (!shouldKeepAlive()) {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug("Detected non persistent http " + "connection," + " " + "preparing to close",
							pendingResponses);
				}
				promise.addListener(ChannelFutureListener.CLOSE);
				ctx.write(msg, promise);
				return;
			}

			ctx.write(msg, promise)
			   .addListener(this);

			if (!persistentConnection) {
				return;
			}

			if (mustRecycleEncoder) {
				mustRecycleEncoder = false;
				pendingResponses -= 1;
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug("Decreasing pending responses, now " +
							"{}", pendingResponses);
				}
			}

			if (pipelined != null && !pipelined.isEmpty()) {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug("draining next pipelined " +
									"request," + " pending response count: {}, queued: " +
									"{}",
							pendingResponses, pipelined.size());
				}
				ctx.executor()
				   .execute(this);
			}
			else {
				ctx.read();
			}
			return;
		}
		ctx.write(msg, promise);
	}

	void trackResponse(HttpResponse response) {
		mustRecycleEncoder = !isInformational(response);
	}

	@Override
	public void run() {
		Object next;
		boolean nextRequest = false;
		while ((next = pipelined.peek()) != null) {
			if (next instanceof HttpRequest) {
				if (nextRequest || !persistentConnection) {
					return;
				}
				nextRequest = true;
				ChannelOperations<?, ?> ops = opsFactory.create(Connection.from(ctx.channel()), listener, next);
				if (ops != null) {
					ops.bind();
					ctx.fireChannelRead(pipelined.poll());
					continue;
				}
			}
			ctx.fireChannelRead(pipelined.poll());
		}
		overflow = false;
	}

	@Override
	public void operationComplete(ChannelFuture future) {
		HttpServerOperations.cleanHandlerTerminate(future.channel());
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		discard();
	}

	final void discard() {
		if(pipelined != null && !pipelined.isEmpty()){
			Object o;
			while((o = pipelined.poll()) != null){
				ReferenceCountUtil.release(o);
			}

		}
	}

	boolean shouldKeepAlive() {
		return pendingResponses != 0 && persistentConnection;
	}

	/**
	 * Keep-alive only works if the client can detect when the message has ended without
	 * relying on the connection being closed.
	 * <p>
	 * <ul> <li>See <a href="https://tools.ietf.org/html/rfc7230#section-6.3"/></li>
	 * <li>See <a href="https://tools.ietf.org/html/rfc7230#section-3.3.3"/></li> </ul>
	 *
	 * @param response The HttpResponse to check
	 *
	 * @return true if the response has a self defined message length.
	 */
	static boolean isSelfDefinedMessageLength(HttpResponse response) {
		return isContentLengthSet(response) || isTransferEncodingChunked(response) || isMultipart(
				response) || isInformational(response);
	}

	static boolean isInformational(HttpResponse response) {
		return response.status()
		               .codeClass() == HttpStatusClass.INFORMATIONAL;
	}

	static boolean isMultipart(HttpResponse response) {
		String contentType = response.headers()
		                             .get(HttpHeaderNames.CONTENT_TYPE);
		return contentType != null && contentType.regionMatches(true,
				0,
				MULTIPART_PREFIX,
				0,
				MULTIPART_PREFIX.length());
	}
}
