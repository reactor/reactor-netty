/*
 * Copyright (c) 2011-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.server;

import java.net.SocketAddress;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Queue;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelFutureListeners;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.DecoderResult;
import io.netty5.handler.codec.DecoderResultProvider;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpObject;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.HttpStatusClass;
import io.netty5.handler.codec.http.HttpVersion;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.handler.ssl.SslHandler;
import io.netty5.util.Resource;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.FutureContextListener;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.ReactorNetty;
import reactor.netty5.http.logging.HttpMessageArgProviderFactory;
import reactor.netty5.http.logging.HttpMessageLogFactory;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;

import static io.netty5.handler.codec.http.HttpUtil.isContentLengthSet;
import static io.netty5.handler.codec.http.HttpUtil.isKeepAlive;
import static io.netty5.handler.codec.http.HttpUtil.isTransferEncodingChunked;
import static io.netty5.handler.codec.http.HttpUtil.setKeepAlive;
import static reactor.netty5.ReactorNetty.format;
import static reactor.netty5.channel.ChannelOperations.get;

/**
 * Replace {@link io.netty5.handler.codec.http.HttpServerKeepAliveHandler} with extra
 * handler management.
 */
final class HttpTrafficHandler extends ChannelHandlerAdapter implements Runnable, FutureContextListener<Channel, Void> {

	static final String MULTIPART_PREFIX = "multipart";

	static final HttpVersion H2 = HttpVersion.valueOf("HTTP/2.0");

	final BiPredicate<HttpServerRequest, HttpServerResponse>      compress;
	final HttpServerFormDecoderProvider                           formDecoderProvider;
	final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
	final HttpMessageLogFactory                                   httpMessageLogFactory;
	final Duration                                                idleTimeout;
	final ConnectionObserver                                      listener;
	final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
	                                                              mapHandle;
	final int                                                     maxKeepAliveRequests;
	final Duration                                                readTimeout;
	final Duration                                                requestTimeout;

	ChannelHandlerContext ctx;

	boolean nonInformationalResponse;
	boolean overflow;

	// Track pending responses to support client pipelining: https://tools.ietf.org/html/rfc7230#section-6.3.2
	int pendingResponses;
	boolean persistentConnection = true;

	Queue<Object> pipelined;

	SocketAddress remoteAddress;

	Boolean secure;

	HttpTrafficHandler(
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compress,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			@Nullable Duration idleTimeout,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			int maxKeepAliveRequests,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout) {
		this.listener = listener;
		this.formDecoderProvider = formDecoderProvider;
		this.forwardedHeaderHandler = forwardedHeaderHandler;
		this.compress = compress;
		this.httpMessageLogFactory = httpMessageLogFactory;
		this.idleTimeout = idleTimeout;
		this.mapHandle = mapHandle;
		this.maxKeepAliveRequests = maxKeepAliveRequests;
		this.readTimeout = readTimeout;
		this.requestTimeout = requestTimeout;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		super.handlerAdded(ctx);
		this.ctx = ctx;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		IdleTimeoutHandler.addIdleTimeoutHandler(ctx.pipeline(), idleTimeout);

		ctx.fireChannelActive();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (secure == null) {
			secure = ctx.channel().pipeline().get(SslHandler.class) != null;
		}
		if (remoteAddress == null) {
			remoteAddress =
					Optional.ofNullable(HAProxyMessageReader.resolveRemoteAddressFromProxyProtocol(ctx.channel()))
					        .orElse(ctx.channel().remoteAddress());
		}
		// read message and track if it was keepAlive
		if (msg instanceof HttpRequest request) {
			if (idleTimeout != null) {
				IdleTimeoutHandler.removeIdleTimeoutHandler(ctx.pipeline());
			}

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
				Resource.dispose(msg);
				return;
			}

			if (H2.equals(request.protocolVersion())) {
				IllegalStateException e = new IllegalStateException(
						"Unexpected request [" + request.method() + " " + request.uri() + " HTTP/2.0]");
				request.setDecoderResult(DecoderResult.failure(e.getCause() != null ? e.getCause() : e));
				sendDecodingFailures(e, msg);
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
				doPipeline(ctx, new HttpRequestHolder(request));
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
				ZonedDateTime timestamp = ZonedDateTime.now(ReactorNetty.ZONE_ID_SYSTEM);
				ConnectionInfo connectionInfo = null;
				try {
					connectionInfo = ConnectionInfo.from(ctx.channel(),
							request,
							secure,
							remoteAddress,
							forwardedHeaderHandler);
					ops = new HttpServerOperations(Connection.from(ctx.channel()),
							listener,
							request,
							compress,
							connectionInfo,
							formDecoderProvider,
							httpMessageLogFactory,
							false,
							mapHandle,
							readTimeout,
							requestTimeout,
							secure,
							timestamp);
				}
				catch (RuntimeException e) {
					request.setDecoderResult(DecoderResult.failure(e.getCause() != null ? e.getCause() : e));
					sendDecodingFailures(e, msg, timestamp, connectionInfo);
					return;
				}
				ops.bind();
				listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);

				ctx.fireChannelRead(msg);
				return;

			}
		}
		else if (persistentConnection && pendingResponses == 0) {
			if (msg instanceof LastHttpContent<?> lastHttpContent) {
				DecoderResult decoderResult = lastHttpContent.decoderResult();
				if (decoderResult.isFailure()) {
					sendDecodingFailures(decoderResult.cause(), msg);
					return;
				}

				ctx.fireChannelRead(msg);
			}
			else {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug(
							format(ctx.channel(), "Dropped HTTP content, since response has been sent already: {}"),
							msg instanceof HttpObject ?
									httpMessageLogFactory.debug(HttpMessageArgProviderFactory.create(msg)) : msg);
				}
				Resource.dispose(msg);
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

		if (msg instanceof DecoderResultProvider decoderResultProvider) {
			DecoderResult decoderResult = decoderResultProvider.decoderResult();
			if (decoderResult.isFailure()) {
				sendDecodingFailures(decoderResult.cause(), msg);
				return;
			}
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) {
		ctx.fireChannelRegistered();
		// Need to move read operation from handlerAdded to channelRegistered
		if (HttpServerOperations.log.isDebugEnabled()) {
			HttpServerOperations.log.debug(format(ctx.channel(), "New http connection, requesting read"));
		}
		ctx.read();
	}

	void sendDecodingFailures(Throwable t, Object msg) {
		sendDecodingFailures(t, msg, null, null);
	}

	void sendDecodingFailures(Throwable t, Object msg, @Nullable ZonedDateTime timestamp, @Nullable ConnectionInfo connectionInfo) {
		persistentConnection = false;
		HttpServerOperations.sendDecodingFailures(ctx, listener, secure, t, msg, httpMessageLogFactory, timestamp, connectionInfo,
				remoteAddress);
	}

	void doPipeline(ChannelHandlerContext ctx, Object msg) {
		if (pipelined == null) {
			pipelined = Queues.unbounded()
			                  .get();
		}
		if (!pipelined.offer(msg)) {
			ctx.fireChannelExceptionCaught(Exceptions.failWithOverflow());
		}
	}

	@Override
	public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
		// modify message on way out to add headers if needed
		if (msg instanceof HttpResponse response) {
			if (pendingResponses == 0) {
				return responseAlreadySent(msg);
			}
			nonInformationalResponse = !isInformational(response);
			// Assume the response writer knows if they can persist or not and sets isKeepAlive on the response
			boolean maxKeepAliveRequestsReached = maxKeepAliveRequests != -1 && HttpServerOperations.requestsCounter(ctx.channel()) == maxKeepAliveRequests;
			if (maxKeepAliveRequestsReached || !isKeepAlive(response) || !isSelfDefinedMessageLength(response)) {
				// No longer keep alive as the client can't tell when the message is done unless we close connection
				pendingResponses = 0;
				persistentConnection = false;
			}
			// Server might think it can keep connection alive, but we should fix response header if we know better
			if (!shouldKeepAlive()) {
				setKeepAlive(response, false);
			}

			if (response.status().equals(HttpResponseStatus.CONTINUE)) {
				return ctx.write(msg);
			}
		}
		if (msg instanceof LastHttpContent) {
			if (!shouldKeepAlive()) {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug(format(ctx.channel(), "Detected non persistent http " +
									"connection, preparing to close"),
							pendingResponses);
				}
				return ctx.write(msg)
				          .addListener(ctx.channel(), this)
				          .addListener(ctx, ChannelFutureListeners.CLOSE);
			}

			Future<Void> future = ctx.write(msg)
			                         .addListener(ctx.channel(), this);

			if (!persistentConnection) {
				return future;
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
				IdleTimeoutHandler.addIdleTimeoutHandler(ctx.pipeline(), idleTimeout);

				ctx.read();
			}
			return future;
		}
		if (persistentConnection && pendingResponses == 0) {
			return responseAlreadySent(msg);
		}
		return ctx.write(msg);
	}

	Future<Void> responseAlreadySent(Object msg) {
		if (HttpServerOperations.log.isDebugEnabled()) {
			HttpServerOperations.log.debug(
					format(ctx.channel(), "Dropped HTTP content, since response has been sent already: {}"),
					msg instanceof HttpObject ?
							httpMessageLogFactory.debug(HttpMessageArgProviderFactory.create(msg)) : msg);
		}
		Resource.dispose(msg);
		return ctx.newSucceededFuture();
	}

	@Override
	public void run() {
		Object next;
		HttpRequest nextRequest = null;
		if (get(ctx.channel()) != null) {
			ctx.channel().executor().execute(this);
			return;
		}

		while ((next = pipelined.peek()) != null) {
			if (next instanceof HttpRequestHolder) {
				if (nextRequest != null) {
					return;
				}
				if (!persistentConnection) {
					discard();
					return;
				}

				HttpRequestHolder holder = (HttpRequestHolder) next;
				nextRequest = holder.request;

				DecoderResult decoderResult = nextRequest.decoderResult();
				if (decoderResult.isFailure()) {
					sendDecodingFailures(decoderResult.cause(), nextRequest, holder.timestamp, null);
					discard();
					return;
				}

				HttpServerOperations ops;
				ConnectionInfo connectionInfo = null;
				try {
					connectionInfo = ConnectionInfo.from(ctx.channel(),
							nextRequest,
							secure,
							remoteAddress,
							forwardedHeaderHandler);
					ops = new HttpServerOperations(Connection.from(ctx.channel()),
							listener,
							nextRequest,
							compress,
							connectionInfo,
							formDecoderProvider,
							httpMessageLogFactory,
							false,
							mapHandle,
							readTimeout,
							requestTimeout,
							secure,
							holder.timestamp);
				}
				catch (RuntimeException e) {
					holder.request.setDecoderResult(DecoderResult.failure(e.getCause() != null ? e.getCause() : e));
					sendDecodingFailures(e, holder.request, holder.timestamp, connectionInfo);
					return;
				}
				ops.bind();
				listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);

				pipelined.poll();
				ctx.fireChannelRead(holder.request);
			}
			else {
				ctx.fireChannelRead(pipelined.poll());
			}
		}
		overflow = false;
	}

	@Override
	public void operationComplete(Channel channel, Future<? extends Void> future) {
		if (!future.isSuccess()) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(format(channel,
				        "Sending last HTTP packet was not successful, terminating the channel"),
				        future.cause());
			}
		}
		else {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(format(channel,
				        "Last HTTP packet was sent, terminating the channel"));
			}
		}

		HttpServerOperations.cleanHandlerTerminate(channel);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		discard();
	}

	final void discard() {
		if (pipelined != null && !pipelined.isEmpty()) {
			Object o;
			while ((o = pipelined.poll()) != null) {
				Resource.dispose(o);
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
				response) || isInformational(response) || isNotModified(response) || isNoContent(response);
	}

	static boolean isInformational(HttpResponse response) {
		return response.status()
		               .codeClass() == HttpStatusClass.INFORMATIONAL;
	}

	static boolean isNoContent(HttpResponse response) {
		return HttpResponseStatus.NO_CONTENT.code() == response.status().code();
	}

	static boolean isNotModified(HttpResponse response) {
		return HttpResponseStatus.NOT_MODIFIED.code() == response.status().code();
	}

	static boolean isMultipart(HttpResponse response) {
		CharSequence contentType = response.headers()
		                             .get(HttpHeaderNames.CONTENT_TYPE);
		return contentType != null && contentType.toString().regionMatches(true,
				0,
				MULTIPART_PREFIX,
				0,
				MULTIPART_PREFIX.length());
	}

	static final class HttpRequestHolder {
		final HttpRequest request;
		final ZonedDateTime timestamp;

		HttpRequestHolder(HttpRequest request) {
			this.request = request;
			this.timestamp = ZonedDateTime.now(ReactorNetty.ZONE_ID_SYSTEM);
		}
	}
}
