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
package reactor.netty5.http;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.handler.codec.http.HttpObject;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http.websocketx.WebSocketFrame;
import io.netty5.util.Resource;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.CombinedChannelDuplexHandler;
import io.netty5.handler.codec.ByteToMessageCodec;
import io.netty5.handler.codec.ByteToMessageDecoder;
import io.netty5.handler.codec.http.EmptyLastHttpContent;
import io.netty5.handler.codec.http.FullHttpMessage;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpMessage;
import io.netty5.handler.codec.http.HttpUtil;
import io.netty5.handler.codec.http.LastHttpContent;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.netty5.BufferFlux;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.FutureMono;
import reactor.netty5.NettyInbound;
import reactor.netty5.NettyOutbound;
import reactor.netty5.NettyPipeline;
import reactor.netty5.ReactorNetty;
import reactor.netty5.channel.AbortedException;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.http.logging.HttpMessageArgProviderFactory;
import reactor.netty5.http.logging.HttpMessageLogFactory;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static io.netty5.buffer.DefaultBufferAllocators.preferredAllocator;
import static reactor.netty5.ReactorNetty.format;

/**
 * An HTTP ready {@link ChannelOperations} with state management for status and headers
 * (first HTTP response packet).
 *
 * @author Stephane Maldini
 */
public abstract class HttpOperations<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
		extends ChannelOperations<INBOUND, OUTBOUND> implements HttpInfos {

	volatile int statusAndHeadersSent;

	static final int READY        = 0;
	static final int HEADERS_SENT = 1;
	static final int BODY_SENT    = 2;

	final HttpMessageLogFactory httpMessageLogFactory;

	protected HttpOperations(HttpOperations<INBOUND, OUTBOUND> replaced) {
		super(replaced);
		this.httpMessageLogFactory = replaced.httpMessageLogFactory;
		this.statusAndHeadersSent = replaced.statusAndHeadersSent;
	}

	protected HttpOperations(Connection connection, ConnectionObserver listener, HttpMessageLogFactory httpMessageLogFactory) {
		super(connection, listener);
		this.httpMessageLogFactory = httpMessageLogFactory;
	}

	/**
	 * Has headers been sent.
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
	public String requestId() {
		return asShortText();
	}

	@Override
	public BufferFlux receive() {
		return BufferFlux.fromInbound(receiveObject(), alloc(), bufferExtractorFunction);
	}

	@Override
	@SuppressWarnings("unchecked")
	public NettyOutbound send(Publisher<? extends Buffer> source) {
		if (!channel().isActive()) {
			return then(Mono.error(AbortedException.beforeSend()));
		}
		if (source instanceof Mono) {
			return new PostHeadersNettyOutbound(((Mono<Buffer>) source)
					.flatMap(b -> {
						if (markSentHeaderAndBody(b)) {
							HttpMessage msg = prepareHttpMessage(b);

							try {
								afterMarkSentHeaders();
							}
							catch (RuntimeException e) {
								b.close();
								return Mono.error(e);
							}

							return FutureMono.from(channel().writeAndFlush(msg));
						}
						return FutureMono.from(channel().writeAndFlush(b));
					})
					.doOnDiscard(Buffer.class, Buffer::close), this, null);
		}
		return super.send(source);
	}

	@Override
	public NettyOutbound sendObject(Object message) {
		if (!channel().isActive()) {
			ReactorNetty.safeRelease(message);
			return then(Mono.error(AbortedException.beforeSend()));
		}
		if (!(message instanceof Buffer b)) {
			return super.sendObject(message);
		}
		return new PostHeadersNettyOutbound(FutureMono.deferFuture(() -> {
			if (markSentHeaderAndBody(b)) {
				HttpMessage msg = prepareHttpMessage(b);

				// If afterMarkSentHeaders throws an exception there is no need to release the ByteBuf here.
				// It will be released by PostHeadersNettyOutbound as there are on error/cancel hooks
				afterMarkSentHeaders();

				return channel().writeAndFlush(msg);
			}
			return channel().writeAndFlush(b);
		}), this, b);
	}

	@Override
	public Mono<Void> then() {
		if (!channel().isActive()) {
			return Mono.error(AbortedException.beforeSend());
		}

		if (hasSentHeaders()) {
			return Mono.empty();
		}

		return FutureMono.deferFuture(() -> {
			if (markSentHeaders(outboundHttpMessage())) {
				HttpMessage msg;

				if (HttpUtil.isContentLengthSet(outboundHttpMessage())) {
					outboundHttpMessage().headers()
							.remove(HttpHeaderNames.TRANSFER_ENCODING);
					if (HttpUtil.getContentLength(outboundHttpMessage(), 0) == 0) {
						markSentBody();
						msg = newFullBodyMessage(channel().bufferAllocator().allocate(0));
					}
					else {
						msg = outboundHttpMessage();
					}
				}
				else if (isContentAlwaysEmpty()) {
					markSentBody();
					msg = newFullBodyMessage(channel().bufferAllocator().allocate(0));
				}
				else {
					msg = outboundHttpMessage();
				}

				try {
					afterMarkSentHeaders();
				}
				catch (RuntimeException e) {
					Resource.dispose(msg);
					throw e;
				}

				return channel().writeAndFlush(msg)
				                .addListener(f -> onHeadersSent());
			}
			else {
				return channel().newSucceededFuture();
			}
		});
	}

	@Override
	protected String asDebugLogMessage(Object o) {
		return o instanceof HttpObject ?
				httpMessageLogFactory.debug(HttpMessageArgProviderFactory.create(o)) :
				o.toString();
	}

	protected HttpMessageLogFactory httpMessageLogFactory() {
		return httpMessageLogFactory;
	}

	protected abstract void beforeMarkSentHeaders();

	protected abstract void afterMarkSentHeaders();

	protected abstract boolean isContentAlwaysEmpty();

	protected abstract void onHeadersSent();

	protected abstract HttpMessage newFullBodyMessage(Buffer body);

	@Override
	public final NettyOutbound sendFile(Path file, long position, long count) {
		Objects.requireNonNull(file);

		if (hasSentHeaders()) {
			return super.sendFile(file, position, count);
		}

		if (!HttpUtil.isTransferEncodingChunked(outboundHttpMessage()) && !HttpUtil.isContentLengthSet(
				outboundHttpMessage()) && count < Integer.MAX_VALUE) {
			outboundHttpMessage().headers()
			                     .set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(count));
		}
		else if (!HttpUtil.isContentLengthSet(outboundHttpMessage())) {
			HttpHeaders headers = outboundHttpMessage().headers();
			headers.remove(HttpHeaderNames.CONTENT_LENGTH);
			headers.remove(HttpHeaderNames.TRANSFER_ENCODING);
			HttpUtil.setTransferEncodingChunked(outboundHttpMessage(), true);
		}

		return super.sendFile(file, position, count);
	}

	@Override
	public String toString() {
		String path;
		try {
			path = fullPath();
		}
		catch (Exception e) {
			path = "/bad-request";
		}
		if (isWebsocket()) {
			return "ws{uri=" + path + ", connection=" + connection() + "}";
		}

		return method().name() + "{uri=" + path + ", connection=" + connection() + "}";
	}

	@Override
	public HttpOperations<INBOUND, OUTBOUND> addHandlerLast(String name, ChannelHandler handler) {
		super.addHandlerLast(name, handler);

		if (channel().pipeline().context(handler) == null) {
			return this;
		}

		autoAddHttpExtractor(this, name, handler);
		return this;
	}

	@Override
	public HttpOperations<INBOUND, OUTBOUND> addHandlerFirst(String name, ChannelHandler handler) {
		super.addHandlerFirst(name, handler);

		if (channel().pipeline().context(handler) == null) {
			return this;
		}

		autoAddHttpExtractor(this, name, handler);
		return this;
	}

	static void autoAddHttpExtractor(Connection c, String name, ChannelHandler handler) {

		if (handler instanceof ByteToMessageDecoder
				|| handler instanceof ByteToMessageCodec
				|| handler instanceof CombinedChannelDuplexHandler) {
			String extractorName = name + "$extractor";

			if (c.channel().pipeline().context(extractorName) != null) {
				return;
			}

			c.channel().pipeline().addBefore(name, extractorName, HTTP_EXTRACTOR);

			if (c.isPersistent()) {
				c.onTerminate().subscribe(null, null, () -> c.removeHandler(extractorName));
			}

		}
	}

	/**
	 * Mark the headers sent.
	 *
	 * @return true if marked for the first time
	 */
	protected final boolean markSentHeaders(Object... objectsToRelease) {
		try {
			if (!hasSentHeaders()) {
				beforeMarkSentHeaders();
			}
		}
		catch (RuntimeException e) {
			for (Object o : objectsToRelease) {
				try {
					Resource.dispose(o);
				}
				catch (Throwable e2) {
					// keep going
				}
			}
			throw e;
		}
		return HTTP_STATE.compareAndSet(this, READY, HEADERS_SENT);
	}

	/**
	 * Mark the body sent.
	 *
	 * @return true if marked for the first time
	 */
	protected final boolean markSentBody() {
		return HTTP_STATE.compareAndSet(this, HEADERS_SENT, BODY_SENT);
	}

	/**
	 * Has Body been sent.
	 *
	 * @return true if body has been sent
	 * @since 1.0.37
	 */
	protected final boolean hasSentBody() {
		return statusAndHeadersSent == BODY_SENT;
	}

	/**
	 * Mark the headers and body sent.
	 *
	 * @return true if marked for the first time
	 */
	protected final boolean markSentHeaderAndBody(Object... objectsToRelease) {
		try {
			if (!hasSentHeaders()) {
				beforeMarkSentHeaders();
			}
		}
		catch (RuntimeException e) {
			for (Object o : objectsToRelease) {
				try {
					Resource.dispose(o);
				}
				catch (Throwable e2) {
					// keep going
				}
			}
			throw e;
		}
		return HTTP_STATE.compareAndSet(this, READY, BODY_SENT);
	}

	@Override
	protected final String initShortId() {
		Connection connection = connection();
		if (connection instanceof AtomicLong) {
			return connection.channel().id().asShortText() + '-' + ((AtomicLong) connection).incrementAndGet();
		}
		return super.initShortId();
	}

	/**
	 * Returns the decoded path portion from the provided {@code uri}.
	 *
	 * @param uri an HTTP URL that may contain a path with query/fragment
	 * @return the decoded path portion from the provided {@code uri}
	 */
	public static String resolvePath(String uri) {
		Objects.requireNonNull(uri, "uri");

		String tempUri = uri;

		int index = tempUri.indexOf('?');
		if (index > -1) {
			tempUri = tempUri.substring(0, index);
		}

		index = tempUri.indexOf('#');
		if (index > -1) {
			tempUri = tempUri.substring(0, index);
		}

		if (tempUri.isEmpty()) {
			return tempUri;
		}

		if (tempUri.charAt(0) == '/') {
			if (tempUri.length() == 1) {
				return tempUri;
			}
			tempUri = "http://localhost:8080" + tempUri;
		}
		else if (!SCHEME_PATTERN.matcher(tempUri).matches()) {
			tempUri = "http://" + tempUri;
		}

		return URI.create(tempUri)
		          .getPath();
	}

	/**
	 * Outbound Netty HttpMessage.
	 *
	 * @return Outbound Netty HttpMessage
	 */
	protected abstract HttpMessage outboundHttpMessage();

	protected HttpMessage prepareHttpMessage(Buffer buffer) {
		HttpMessage msg;
		if (HttpUtil.getContentLength(outboundHttpMessage(), -1) == 0 ||
				isContentAlwaysEmpty()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Dropped HTTP content, since response has " +
						"1. [Content-Length: 0] or 2. there must be no content: {}"), buffer);
			}
			buffer.close();
			msg = newFullBodyMessage(channel().bufferAllocator().allocate(0));
		}
		else {
			msg = newFullBodyMessage(buffer);
		}

		return msg;
	}

	@SuppressWarnings("rawtypes")
	static final AtomicIntegerFieldUpdater<HttpOperations> HTTP_STATE =
			AtomicIntegerFieldUpdater.newUpdater(HttpOperations.class,
					"statusAndHeadersSent");

	static final ChannelHandler HTTP_EXTRACTOR = NettyPipeline.inboundHandler(
			(ctx, msg) -> {
				if (msg instanceof HttpContent<?> httpContent) {
					if (msg instanceof FullHttpMessage) {
						// TODO convert into 2 messages if FullHttpMessage
						ctx.fireChannelRead(msg);
					}
					else {
						Buffer bb = httpContent.payload();
						ctx.fireChannelRead(bb);
						if (msg instanceof LastHttpContent) {
							ctx.fireChannelRead(new EmptyLastHttpContent(ctx.bufferAllocator()));
						}
					}
				}
				else {
					ctx.fireChannelRead(msg);
				}
			}
	);

	/**
	 * A channel object to {@link Buffer} transformer.
	 */
	public static final Function<Object, Buffer> bufferExtractorFunction = o -> {
		if (o instanceof Buffer buffer) {
			return buffer;
		}
		if (o instanceof HttpContent<?> httpContent) {
			return httpContent.payload();
		}
		if (o instanceof WebSocketFrame frame) {
			return frame.binaryData();
		}
		if (o instanceof byte[] bytes) {
			return preferredAllocator().copyOf(bytes);
		}
		throw new IllegalArgumentException("Object " + o + " of type " + o.getClass() + " " + "cannot be converted to Buffer");
	};

	static final Logger log = Loggers.getLogger(HttpOperations.class);

	static final Pattern SCHEME_PATTERN = Pattern.compile("^(https?|wss?)://.*$");

	protected static final class PostHeadersNettyOutbound extends AtomicBoolean implements NettyOutbound {

		final Mono<Void> source;
		final HttpOperations<?, ?> parent;
		final Buffer msg;

		public PostHeadersNettyOutbound(Mono<Void> source, HttpOperations<?, ?> parent, @Nullable Buffer msg) {
			this.msg = msg;
			if (msg != null) {
				this.source = source.doFinally(signalType -> {
					if (signalType == SignalType.CANCEL || signalType == SignalType.ON_ERROR) {
						if (msg.isAccessible() && compareAndSet(false, true)) {
							msg.close();
						}
					}
				});
			}
			else {
				this.source = source;
			}
			this.parent = parent;
		}

		@Override
		public Mono<Void> then() {
			return source;
		}

		@Override
		public BufferAllocator alloc() {
			return parent.alloc();
		}

		@Override
		public NettyOutbound send(Publisher<? extends Buffer> publisher, Predicate<Buffer> predicate) {
			return parent.send(publisher, predicate);
		}

		@Override
		public NettyOutbound sendObject(Publisher<?> dataStream, Predicate<Object> predicate) {
			return parent.sendObject(dataStream, predicate);
		}

		@Override
		public NettyOutbound sendObject(Object message) {
			return parent.sendObject(message);
		}

		@Override
		public <S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
				BiFunction<? super Connection, ? super S, ?> mappedInput,
				Consumer<? super S> sourceCleanup) {
			return parent.sendUsing(sourceInput, mappedInput, sourceCleanup);
		}

		@Override
		public NettyOutbound withConnection(Consumer<? super Connection> withConnection) {
			return parent.withConnection(withConnection);
		}
	}
}

