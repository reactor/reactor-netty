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

package reactor.ipc.netty.http2.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.HttpConversionUtil;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.http.HttpOperations;
import reactor.ipc.netty.http.server.ConnectionInfo;
import reactor.ipc.netty.http2.Http2Operations;
import reactor.util.Logger;
import reactor.util.Loggers;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

/**
 * Conversion between Netty types  and Reactor types ({@link HttpOperations}.
 *
 * @author Stephane Maldini1
 */
public class Http2ServerOperations extends Http2Operations<Http2ServerRequest, Http2ServerResponse>
		implements Http2ServerRequest, Http2ServerResponse, BiConsumer<Void, Throwable> {

	final Http2Headers nettyResponse;
	final Http2Headers nettyRequest;
	final int streamId;
	final ConnectionInfo connectionInfo;

	Function<? super String, Map<String, String>> paramsResolver;

	public Http2ServerOperations(Connection c,
				ConnectionObserver listener,
				Http2Headers nettyRequest, boolean forwarded, int streamId) {
		super(c, listener);
		Objects.requireNonNull(nettyRequest, "nettyRequest");
		this.nettyRequest = nettyRequest;
		this.nettyResponse = new DefaultHttp2Headers().status(OK.codeAsText());
		this.streamId = streamId;
		try {
			if (forwarded) {
				Channel socketChannel;
				if (channel() instanceof Http2StreamChannel) {
					socketChannel = channel().parent();
				}
				else {
					socketChannel = channel();
				}
				this.connectionInfo =
						ConnectionInfo.newForwardedConnectionInfo(HttpConversionUtil.toHttpRequest(streamId, nettyRequest, false),
								(SocketChannel) socketChannel);
			}
			else {
				Channel socketChannel;
				if (channel() instanceof Http2StreamChannel) {
					socketChannel = channel().parent();
				}
				else {
					socketChannel = channel();
				}
				this.connectionInfo = ConnectionInfo.newConnectionInfo((SocketChannel) socketChannel);
			}
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public NettyOutbound sendHeaders() {
		if (hasSentHeaders()) {
			return this;
		}

		return then(Mono.empty());
	}

	@Override
	public Http2ServerOperations withConnection(Consumer<? super Connection> withConnection) {
		Objects.requireNonNull(withConnection, "withConnection");
		withConnection.accept(this);
		return this;
	}

	@Override
	public Http2ServerResponse addHeader(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.nettyResponse.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public Http2ServerResponse header(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.nettyResponse.set(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public Http2ServerResponse headers(Http2Headers headers) {
		if (!hasSentHeaders()) {
			this.nettyResponse.set(headers);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public CharSequence method() {
		return nettyRequest.method();
	}

	@Override
	protected CharSequence path() {
		return nettyRequest.path();
	}

	@Override
	@Nullable
	public String param(CharSequence key) {
		Objects.requireNonNull(key, "key");
		Map<String, String> params = null;
		if (paramsResolver != null) {
			params = this.paramsResolver.apply(path().toString());
		}
		return null != params ? params.get(key) : null;
	}

	@Override
	@Nullable
	public Map<String, String> params() {
		return null != paramsResolver ? paramsResolver.apply(path().toString()) : null;
	}

	@Override
	public Http2ServerRequest paramsResolver(Function<? super String, Map<String, String>> headerResolver) {
		this.paramsResolver = headerResolver;
		return this;
	}

	@Override
	public Flux<?> receiveObject() {
		// Handle the 'Expect: 100-continue' header if necessary.
		// TODO: Respond with 413 Request Entity Too Large
		//   and discard the traffic or close the connection.
		//       No need to notify the upstream handlers - just log.
		//       If decoding a response, just throw an error.
		if (HttpHeaderValues.CONTINUE.toString().equalsIgnoreCase(nettyRequest.get(HttpHeaderNames.EXPECT).toString())) {
			return FutureMono.deferFuture(() -> {
						if(!hasSentHeaders()) {
							return channel().writeAndFlush(new DefaultHttp2HeadersFrame(CONTINUE, true));
						}
						return channel().newSucceededFuture();
					})

			                 .thenMany(super.receiveObject());
		}
		else {
			return super.receiveObject();
		}
	}

	@Override
	public InetSocketAddress hostAddress() {
		return this.connectionInfo.getHostAddress();
	}

	@Override
	public InetSocketAddress remoteAddress() {
		return this.connectionInfo.getRemoteAddress();
	}

	@Override
	public Http2Headers requestHeaders() {
		if (nettyRequest != null) {
			return nettyRequest;
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public String scheme() {
		return this.connectionInfo.getScheme();
	}

	@Override
	public Http2Headers responseHeaders() {
		return nettyResponse;
	}

	@Override
	public Mono<Void> send() {
		if (markSentHeaderAndBody()) {
			return FutureMono.deferFuture(() -> channel().writeAndFlush(
					new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status(status()), true)));
		}
		else {
			return Mono.empty();
		}
	}

	@Override
	public NettyOutbound sendFile(Path file) {
		try {
			return sendFile(file, 0L, Files.size(file));
		}
		catch (IOException e) {
			if (log.isDebugEnabled()) {
				log.debug("Path not resolved", e);
			}
			return then(sendNotFound());
		}
	}

	@Override
	public Mono<Void> sendNotFound() {
		return this.status(HttpResponseStatus.NOT_FOUND.codeAsText())
		           .send();
	}

	@Override
	public CharSequence status() {
		return this.nettyResponse.status();
	}

	@Override
	public Http2ServerResponse status(CharSequence status) {
		if (!hasSentHeaders()) {
			this.nettyResponse.status(status);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		//TODO
		if (msg instanceof HttpRequest) {
			listener().onStateChange(this, ConnectionObserver.State.CONFIGURED);
			if (msg instanceof FullHttpRequest) {
				super.onInboundNext(ctx, msg);
			}
			return;
		}
		if (msg instanceof ByteBuf) {
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				super.onInboundNext(ctx, msg);
			}
			if (msg instanceof LastHttpContent) {
				onInboundComplete();
			}
		}
		else {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	protected void onOutboundComplete() {
		final ChannelFuture f;
		if (log.isDebugEnabled()) {
			log.debug("Last HTTP response frame");
		}
		if (markSentHeaderAndBody()) {
			if (log.isDebugEnabled()) {
				log.debug("No sendHeaders() called before complete, sending " + "zero-length header");
			}

			f = channel().writeAndFlush(new DefaultHttp2HeadersFrame(
					new DefaultHttp2Headers().status(status()), true));
		}
		else if (markSentBody()) {
			f = channel().writeAndFlush(new DefaultHttp2DataFrame(true));
		}
		else{
			discard();
			return;
		}
		f.addListener(s -> {
			discard();
			if (!s.isSuccess() && log.isDebugEnabled()) {
				log.error(channel()+" Failed flushing last frame", s.cause());
			}
		});

	}

	@Override
	protected void onOutboundError(Throwable err) {

		if (!channel().isActive()) {
			super.onOutboundError(err);
			return;
		}

		if (markSentHeaders()) {
			log.error("Error starting response. Replying error status", err);

			Http2Headers response = new DefaultHttp2Headers().status(HttpResponseStatus.INTERNAL_SERVER_ERROR.codeAsText());
			channel().writeAndFlush(new DefaultHttp2HeadersFrame(response, true))
			         .addListener(ChannelFutureListener.CLOSE);
			return;
		}

		markSentBody();
		log.error("Error finishing response. Closing connection", err);
		channel().writeAndFlush(new DefaultHttp2DataFrame(EMPTY_BUFFER))
		         .addListener(ChannelFutureListener.CLOSE);
	}

	@Override
	protected Http2Headers outboundHttpMessage() {
		return nettyResponse;
	}

	static final Logger log = Loggers.getLogger(Http2ServerOperations.class);

	final static Http2Headers CONTINUE     =
			new DefaultHttp2Headers().status(HttpResponseStatus.CONTINUE.codeAsText());

	@Override
	public int streamId() {
		return streamId;
	}

	@Override
	public void accept(Void aVoid, Throwable throwable) {
		if (throwable == null) {
			if (channel().isActive()) {
				onOutboundComplete();
			}
		}
		else {
			onOutboundError(throwable);
		}
	}
}
