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

package reactor.netty.http.server;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Queue;
import java.util.function.BiPredicate;
import javax.annotation.Nullable;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.DecoderResultProvider;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import reactor.core.Exceptions;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.util.concurrent.Queues;

import static io.netty.handler.codec.http.HttpUtil.*;
import static reactor.netty.ReactorNetty.format;
import static reactor.netty.ReactorNetty.toPrettyHexDump;

/**
 * Replace {@link io.netty.handler.codec.http.HttpServerKeepAliveHandler} with extra
 * handler management.
 */
final class HttpTrafficHandler extends ChannelDuplexHandler
		implements Runnable, ChannelFutureListener {

	static final String MULTIPART_PREFIX = "multipart";

	final ConnectionObserver                                 listener;
	Boolean                                                  secure;
	InetSocketAddress                                        remoteAddress;
	final boolean                                            readForwardHeaders;
	final BiPredicate<HttpServerRequest, HttpServerResponse> compress;
	final ServerCookieEncoder                                cookieEncoder;
	final ServerCookieDecoder                                cookieDecoder;

	boolean persistentConnection = true;
	// Track pending responses to support client pipelining: https://tools.ietf.org/html/rfc7230#section-6.3.2
	int pendingResponses;

	Queue<Object> pipelined;

	ChannelHandlerContext ctx;

	boolean overflow;
	boolean nonInformationalResponse;

	HttpTrafficHandler(ConnectionObserver listener, boolean readForwardHeaders,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compress,
			ServerCookieEncoder encoder, ServerCookieDecoder decoder) {
		this.listener = listener;
		this.readForwardHeaders = readForwardHeaders;
		this.compress = compress;
		this.cookieEncoder = encoder;
		this.cookieDecoder = decoder;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		super.handlerAdded(ctx);
		this.ctx = ctx;
		if (HttpServerOperations.log.isDebugEnabled()) {
			HttpServerOperations.log.debug(format(ctx.channel(), "New http connection, requesting read"));
		}
		ctx.read();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (secure == null) {
			secure = ctx.channel().pipeline().get(SslHandler.class) != null;
		}
		if (remoteAddress == null) {
			remoteAddress =
					Optional.ofNullable(HAProxyMessageReader.resolveRemoteAddressFromProxyProtocol(ctx.channel()))
					        .orElse(((SocketChannel) ctx.channel()).remoteAddress());
		}
		// read message and track if it was keepAlive
		if (msg instanceof HttpRequest) {
			final HttpRequest request = (HttpRequest) msg;

			if (persistentConnection) {
				pendingResponses += 1;
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug(format(ctx.channel(), "Increasing pending responses, now {}"),
							pendingResponses);
				}
				persistentConnection = isKeepAlive(request);
			}
			else {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug(format(ctx.channel(), "Dropping pipelined HTTP request, " +
									"previous response requested connection close"));
				}
				ReferenceCountUtil.release(msg);
				return;
			}
			if (pendingResponses > 1) {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug(format(ctx.channel(), "Buffering pipelined HTTP request, " +
									"pending response count: {}, queue: {}"),
							pendingResponses,
							pipelined != null ? pipelined.size() : 0);
				}
				overflow = true;
				doPipeline(ctx, msg);
				return;
			}
			else {
				overflow = false;

				DecoderResult decoderResult = request.decoderResult();
				if (decoderResult.isFailure()) {
					sendDecodingFailures(decoderResult.cause(), msg);
					return;
				}

				HttpServerOperations ops;
				try {
					ops = new HttpServerOperations(Connection.from(ctx.channel()),
							listener,
							compress, request,
							ConnectionInfo.from(ctx.channel(),
							                    readForwardHeaders,
							                    request,
							                    secure,
							                    remoteAddress),
							cookieEncoder,
							cookieDecoder);
				}
				catch (RuntimeException e) {
					sendDecodingFailures(e, msg);
					return;
				}
				ops.bind();
				listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);

				ctx.fireChannelRead(msg);
				return;

			}
		}
		else if (persistentConnection && pendingResponses == 0) {
			if (msg instanceof LastHttpContent) {
				DecoderResult decoderResult = ((LastHttpContent) msg).decoderResult();
				if (decoderResult.isFailure()) {
					sendDecodingFailures(decoderResult.cause(), msg);
					return;
				}

				ctx.fireChannelRead(msg);
			}
			else {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug(format(ctx.channel(), "Dropped HTTP content, " +
							"since response has been sent already: {}"), msg);
				}
				ReferenceCountUtil.release(msg);
			}
			ctx.read();
			return;
		}
		else if (overflow) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(format(ctx.channel(), "Buffering pipelined HTTP content, " +
								"pending response count: {}, pending pipeline:{}"),
						pendingResponses,
						pipelined != null ? pipelined.size() : 0);
			}
			doPipeline(ctx, msg);
			return;
		}

		if (msg instanceof DecoderResultProvider) {
			DecoderResult decoderResult = ((DecoderResultProvider) msg).decoderResult();
			if (decoderResult.isFailure()) {
				sendDecodingFailures(decoderResult.cause(), msg);
				return;
			}
		}

		ctx.fireChannelRead(msg);
	}

	void sendDecodingFailures(Throwable t, Object msg) {
		persistentConnection = false;
		HttpServerOperations.sendDecodingFailures(ctx, t, msg);
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
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		// modify message on way out to add headers if needed
		if (msg instanceof HttpResponse) {
			final HttpResponse response = (HttpResponse) msg;
			nonInformationalResponse = !isInformational(response);
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
				//"FutureReturnValueIgnored" this is deliberate
				ctx.write(msg, promise);
				return;
			}
		}
		if (msg instanceof LastHttpContent) {
			if (!shouldKeepAlive()) {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug(format(ctx.channel(), "Detected non persistent http " +
									"connection, preparing to close"),
							pendingResponses);
				}
				ctx.write(msg, promise.unvoid())
				   .addListener(this)
				   .addListener(ChannelFutureListener.CLOSE);
				return;
			}

			ctx.write(msg, promise.unvoid())
			   .addListener(this);

			if (!persistentConnection) {
				return;
			}

			if (nonInformationalResponse) {
				nonInformationalResponse = false;
				pendingResponses -= 1;
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug(format(ctx.channel(), "Decreasing pending responses, now {}"),
							pendingResponses);
				}
			}

			if (pipelined != null && !pipelined.isEmpty()) {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug(format(ctx.channel(), "Draining next pipelined " +
									"request, pending response count: {}, queued: {}"),
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
		if (persistentConnection && pendingResponses == 0) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(format(ctx.channel(), "Dropped HTTP content, " +
						"since response has been sent already: {}"), toPrettyHexDump(msg));
			}
			ReferenceCountUtil.release(msg);
			promise.setSuccess();
			return;
		}
		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}

	@Override
	public void run() {
		Object next;
		HttpRequest nextRequest = null;
		while ((next = pipelined.peek()) != null) {
			if (next instanceof HttpRequest) {
				if (nextRequest != null) {
					return;
				}
				if (!persistentConnection) {
					discard();
					return;
				}

				nextRequest = (HttpRequest) next;

				DecoderResult decoderResult = nextRequest.decoderResult();
				if (decoderResult.isFailure()) {
					sendDecodingFailures(decoderResult.cause(), nextRequest);
					discard();
					return;
				}

				HttpServerOperations ops = new HttpServerOperations(Connection.from(ctx.channel()),
						listener,
						compress,
						nextRequest,
						ConnectionInfo.from(ctx.channel(),
						                    readForwardHeaders,
						                    nextRequest,
						                    secure,
						                    remoteAddress),
						cookieEncoder,
						cookieDecoder);
				ops.bind();
				listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);
			}
			ctx.fireChannelRead(pipelined.poll());
		}
		overflow = false;
	}

	@Override
	public void operationComplete(ChannelFuture future) {
		if (!future.isSuccess()) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(format(future.channel(),
				        "Sending last HTTP packet was not successful, terminating the channel"),
				        future.cause());
			}
		}
		else {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(format(future.channel(),
				        "Last HTTP packet was sent, terminating the channel"));
			}
		}
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
				response) || isInformational(response) || isNotModified(response);
	}

	static boolean isInformational(HttpResponse response) {
		return response.status()
		               .codeClass() == HttpStatusClass.INFORMATIONAL;
	}

	static boolean isNotModified(HttpResponse response) {
		return HttpResponseStatus.NOT_MODIFIED.equals(response.status());
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
