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

package reactor.netty.http;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.ReactorNetty.toPrettyHexDump;

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
	@SuppressWarnings("unchecked")
	public NettyOutbound send(Publisher<? extends ByteBuf> source) {
		if (!channel().isActive()) {
			return then(Mono.error(new AbortedException("Connection has been closed BEFORE send operation")));
		}
		if (source instanceof Mono) {
			return new PostHeadersNettyOutbound(((Mono<ByteBuf>)source)
					.flatMap(msg -> {
						if (markSentHeaderAndBody(msg)) {
							try {
								afterMarkSentHeaders();
							}
							catch (RuntimeException e) {
								ReferenceCountUtil.release(msg);
								return Mono.error(e);
							}
							if (HttpUtil.getContentLength(outboundHttpMessage(), -1) == 0) {
								log.debug(format(channel(), "Dropped HTTP content, " +
										"since response has Content-Length: 0 {}"), toPrettyHexDump(msg));
								msg.release();
								return FutureMono.from(channel().writeAndFlush(newFullBodyMessage(Unpooled.EMPTY_BUFFER)));
							}
							return FutureMono.from(channel().writeAndFlush(newFullBodyMessage(msg)));
						}
						return FutureMono.from(channel().writeAndFlush(msg));
					})
					.doOnDiscard(ByteBuf.class, ByteBuf::release), this, null);
		}
		return super.send(source);
	}

	@Override
	public NettyOutbound sendObject(Object message) {
		if (!channel().isActive()) {
			ReactorNetty.safeRelease(message);
			return then(Mono.error(new AbortedException("Connection has been closed BEFORE send operation")));
		}
		if (!(message instanceof ByteBuf)) {
			return super.sendObject(message);
		}
		ByteBuf b = (ByteBuf) message;
		return new PostHeadersNettyOutbound(FutureMono.deferFuture(() -> {
			if (markSentHeaderAndBody(b)) {
				try {
					afterMarkSentHeaders();
				}
				catch (RuntimeException e) {
					b.release();
					throw e;
				}
				if (HttpUtil.getContentLength(outboundHttpMessage(), -1) == 0) {
					log.debug(format(channel(), "Dropped HTTP content, " +
							"since response has Content-Length: 0 {}"), toPrettyHexDump(b));
					b.release();
					return channel().writeAndFlush(newFullBodyMessage(Unpooled.EMPTY_BUFFER));
				}
				return channel().writeAndFlush(newFullBodyMessage(b));
			}
			return channel().writeAndFlush(b);
		}), this, b);
	}

	@Override
	public Mono<Void> then() {
		if (!channel().isActive()) {
			return Mono.error(new AbortedException("Connection has been closed BEFORE send operation"));
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
						msg = newFullBodyMessage(Unpooled.EMPTY_BUFFER);
					}
					else {
						msg = outboundHttpMessage();
					}
				}
				else {
					msg = outboundHttpMessage();
				}

				try {
					afterMarkSentHeaders();
				}
				catch (RuntimeException e) {
					ReferenceCountUtil.release(msg);
					throw e;
				}

				return channel().writeAndFlush(msg);
			}
			else {
				return channel().newSucceededFuture();
			}
		});
	}

	protected abstract void beforeMarkSentHeaders();

	protected abstract void afterMarkSentHeaders();

	protected abstract HttpMessage newFullBodyMessage(ByteBuf body);

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
	protected final boolean markSentHeaders(Object... objectsToRelease) {
		try {
			if (!hasSentHeaders()) {
				beforeMarkSentHeaders();
			}
		}
		catch (RuntimeException e) {
			for (Object o : objectsToRelease) {
				try {
					ReferenceCountUtil.release(o);
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
	protected final boolean markSentHeaderAndBody(Object... objectsToRelease) {
		try {
			if (!hasSentHeaders()) {
				beforeMarkSentHeaders();
			}
		}
		catch (RuntimeException e) {
			for (Object o : objectsToRelease) {
				try {
					ReferenceCountUtil.release(o);
				}
				catch (Throwable e2) {
					// keep going
				}
			}
			throw e;
		}
		return HTTP_STATE.compareAndSet(this, READY, BODY_SENT);
	}

	/**
	 * Returns the decoded path portion from the provided {@code uri}
	 *
	 * @param uri an HTTP URL that may contain a path with query/fragment
	 * @return the decoded path portion from the provided {@code uri}
	 */
	public static String resolvePath(String uri) {
		if (uri.isEmpty()) {
			return uri;
		}

		String tempUri = uri;
		if (tempUri.charAt(0) == '/') {
			tempUri = "http://localhost:8080" + tempUri;
		}
		else if (!SCHEME_PATTERN.matcher(tempUri).matches()) {
			tempUri = "http://" + tempUri;
		}

		return URI.create(tempUri)
		          .getPath();
	}

	/**
	 * Outbound Netty HttpMessage
	 *
	 * @return Outbound Netty HttpMessage
	 */
	protected abstract HttpMessage outboundHttpMessage();

	@SuppressWarnings("rawtypes")
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

	static final Logger log = Loggers.getLogger(HttpOperations.class);

	static final Pattern SCHEME_PATTERN = Pattern.compile("^(https?|wss?)://.*$");

	protected static final class PostHeadersNettyOutbound implements NettyOutbound, Consumer<Throwable>, Runnable {

		final Mono<Void> source;
		final HttpOperations<?, ?> parent;
		final ByteBuf msg;

		public PostHeadersNettyOutbound(Mono<Void> source, HttpOperations<?, ?> parent, @Nullable ByteBuf msg) {
			this.msg = msg;
			if (msg != null) {
				this.source = source.doOnError(this)
				                    .doOnCancel(this);
			}
			else {
				this.source = source;
			}
			this.parent = parent;
		}

		@Override
		public void run() {
			if (msg != null && msg.refCnt() > 0) {
				msg.release();
			}
		}

		@Override
		public void accept(Throwable throwable) {
			if (msg != null && msg.refCnt() > 0) {
				msg.release();
			}
		}

		@Override
		public Mono<Void> then() {
			return source;
		}

		@Override
		public ByteBufAllocator alloc() {
			return parent.alloc();
		}

		@Override
		public NettyOutbound send(Publisher<? extends ByteBuf> dataStream, Predicate<ByteBuf> predicate) {
			return parent.send(dataStream, predicate);
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

