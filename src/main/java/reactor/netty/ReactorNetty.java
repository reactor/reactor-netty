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
package reactor.netty;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoop;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCounted;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CorePublisher;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

/**
 * Internal helpers for reactor-netty contracts
 *
 * @author Stephane Maldini
 */
public final class ReactorNetty {

	// System properties names


	/**
	 * Specifies whether the channel ID will be prepended to the log message when possible.
	 * By default it will be prepended.
	 */
	static final boolean LOG_CHANNEL_INFO =
			Boolean.parseBoolean(System.getProperty("reactor.netty.logChannelInfo", "true"));

	/**
	 * Default worker thread count, fallback to available processor
	 * (but with a minimum value of 4)
	 */
	public static final String IO_WORKER_COUNT = "reactor.netty.ioWorkerCount";
	/**
	 * Default selector thread count, fallback to -1 (no selector thread)
	 */
	public static final String IO_SELECT_COUNT = "reactor.netty.ioSelectCount";
	/**
	 * Default worker thread count for UDP, fallback to available processor
	 * (but with a minimum value of 4)
	 */
	public static final String UDP_IO_THREAD_COUNT = "reactor.netty.udp.ioThreadCount";
	/**
	 * Default quite period that guarantees that the disposal of the underlying LoopResources
	 * will not happen, fallback to 2 seconds.
	 */
	public static final String SHUTDOWN_QUIET_PERIOD = "reactor.netty.ioShutdownQuietPeriod";
	/**
	 * Default maximum amount of time to wait until the disposal of the underlying LoopResources
	 * regardless if a task was submitted during the quiet period, fallback to 15 seconds.
	 */
	public static final String SHUTDOWN_TIMEOUT = "reactor.netty.ioShutdownTimeout";

	/**
	 * Default value whether the native transport (epoll, kqueue) will be preferred,
	 * fallback it will be preferred when available
	 */
	public static final String NATIVE = "reactor.netty.native";


	/**
	 * Default max connections. Fallback to
	 * available number of processors (but with a minimum value of 16)
	 */
	public static final String POOL_MAX_CONNECTIONS = "reactor.netty.pool.maxConnections";
	/**
	 * Default acquisition timeout (milliseconds) before error. If -1 will never wait to
	 * acquire before opening a new
	 * connection in an unbounded fashion. Fallback 45 seconds
	 */
	public static final String POOL_ACQUIRE_TIMEOUT = "reactor.netty.pool.acquireTimeout";
	/**
	 * Default max idle time, fallback - max idle time is not specified.
	 */
	public static final String POOL_MAX_IDLE_TIME = "reactor.netty.pool.maxIdleTime";
	/**
	 * Default leasing strategy (fifo, lifo), fallback to fifo.
	 * <ul>
	 *     <li>fifo - The connection selection is first in, first out</li>
	 *     <li>lifo - The connection selection is last in, first out</li>
	 * </ul>
	 */
	public static final String POOL_LEASING_STRATEGY = "reactor.netty.pool.leasingStrategy";


	/**
	 * Default SSL handshake timeout (milliseconds), fallback to 10 seconds
	 */
	public static final String SSL_HANDSHAKE_TIMEOUT = "reactor.netty.tcp.sslHandshakeTimeout";
	/**
	 * Default value whether the SSL debugging on the client side will be enabled/disabled,
	 * fallback to SSL debugging disabled
	 */
	public static final String SSL_CLIENT_DEBUG = "reactor.netty.tcp.ssl.client.debug";
	/**
	 * Default value whether the SSL debugging on the server side will be enabled/disabled,
	 * fallback to SSL debugging disabled
	 */
	public static final String SSL_SERVER_DEBUG = "reactor.netty.tcp.ssl.server.debug";


	/**
	 * Specifies whether the Http Server access log will be enabled.
	 * By default it is disabled.
	 */
	public static final String ACCESS_LOG_ENABLED = "reactor.netty.http.server.accessLogEnabled";


	/**
	 * Try to call {@link ReferenceCounted#release()} if the specified message implements {@link ReferenceCounted}.
	 * If the specified message doesn't implement {@link ReferenceCounted} or it is already released,
	 * this method does nothing.
	 */
	public static void safeRelease(Object msg) {
		if (msg instanceof ReferenceCounted) {
			ReferenceCounted referenceCounted = (ReferenceCounted) msg;
			if (referenceCounted.refCnt() > 0) {
				referenceCounted.release();
			}
		}
	}

	/**
	 * Append channel ID to a log message for correlated traces
	 * @param channel current channel associated with the msg
	 * @param msg the log msg
	 * @return a formatted msg
	 */
	public static String format(Channel channel, String msg) {
		if (LOG_CHANNEL_INFO) {
			String channelStr = channel.toString();
			return new StringBuilder(channelStr.length() + 1 + msg.length())
					.append(channel)
					.append(' ')
					.append(msg)
					.toString();
		}
		else {
			return msg;
		}
	}

	/**
	 * Pretty hex dump will be returned when the object is {@link ByteBuf} or {@link ByteBufHolder}
	 */
	public static String toPrettyHexDump(Object msg) {
		String result;
		if (msg instanceof ByteBufHolder &&
				!Objects.equals(Unpooled.EMPTY_BUFFER, ((ByteBufHolder) msg).content())) {
			ByteBuf buffer = ((ByteBufHolder) msg).content();
			result = "\n" + ByteBufUtil.prettyHexDump(buffer);
		}
		else if (msg instanceof ByteBuf) {
			result = "\n" + ByteBufUtil.prettyHexDump((ByteBuf) msg);
		}
		else {
			result = msg.toString();
		}
		return result;
	}

	/**
	 * Wrap possibly fatal or singleton exception into a new exception instance in order to propagate in reactor flows without side effect.
	 *
	 * @return a wrapped {@link RuntimeException}
	 */
	public static RuntimeException wrapException(Throwable throwable) {
		return new InternalNettyException(Objects.requireNonNull(throwable));
	}

	static void addChunkedWriter(Connection c){
		if (c.channel()
		     .pipeline()
		     .get(ChunkedWriteHandler.class) == null) {
			c.addHandlerLast(NettyPipeline.ChunkedWriter, new ChunkedWriteHandler());
		}
	}

	/**
	 * A common implementation for the {@link Connection#addHandlerLast(String, ChannelHandler)}
	 * method that can be reused by other implementors.
	 * <p>
	 * This implementation will look for reactor added handlers on the right hand side of
	 * the pipeline, provided they are identified with the {@link NettyPipeline#RIGHT}
	 * prefix, and add the handler just before the first of these.
	 *
	 * @param context the {@link Connection} on which to add the decoder.
	 * @param name the name of the decoder.
	 * @param handler the decoder to add before the final reactor-specific handlers.
	 * @see Connection#addHandlerLast(String, ChannelHandler).
	 */
	static void addHandlerBeforeReactorEndHandlers(Connection context, String
			name,	ChannelHandler handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		Channel channel = context.channel();
		boolean exists = channel.pipeline().get(name) != null;

		if (exists) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Handler [{}] already exists in the pipeline, decoder has been skipped"),
						name);
			}
			return;
		}

		//we need to find the correct position
		String before = null;
		for (String s : channel.pipeline().names()) {
			if (s.startsWith(NettyPipeline.RIGHT)) {
				before = s;
				break;
			}
		}

		if (before == null) {
			channel.pipeline().addLast(name, handler);
		}
		else {
			channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, name, handler);
		}

		registerForClose(context.isPersistent(),  name, context);

		if (log.isDebugEnabled()) {
			log.debug(format(channel, "Added decoder [{}] at the end of the user pipeline, full pipeline: {}"),
					name,
					channel.pipeline().names());
		}
	}

	/**
	 * A common implementation for the {@link Connection#addHandlerFirst(String, ChannelHandler)}
	 * method that can be reused by other implementors.
	 * <p>
	 * This implementation will look for reactor added handlers on the left hand side of
	 * the pipeline, provided they are identified with the {@link NettyPipeline#LEFT}
	 * prefix, and add the handler just after the last of these.
	 *
	 * @param context the {@link Connection} on which to add the decoder.
	 * @param name the name of the encoder.
	 * @param handler the encoder to add after the initial reactor-specific handlers.
	 * @see Connection#addHandlerFirst(String, ChannelHandler)
	 */
	static void addHandlerAfterReactorCodecs(Connection context, String
			name,
			ChannelHandler handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		Channel channel = context.channel();
		boolean exists = channel.pipeline().get(name) != null;

		if (exists) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Handler [{}] already exists in the pipeline, encoder has been skipped"),
						name);
			}
			return;
		}

		//we need to find the correct position
		String after = null;
		for (String s : channel.pipeline().names()) {
			if (s.startsWith(NettyPipeline.LEFT)) {
				after = s;
			}
		}

		if (after == null) {
			channel.pipeline().addFirst(name, handler);
		}
		else {
			channel.pipeline().addAfter(after, name, handler);
		}

		registerForClose(context.isPersistent(), name, context);

		if (log.isDebugEnabled()) {
			log.debug(format(channel, "Added encoder [{}] at the beginning of the user pipeline, full pipeline: {}"),
					name,
					channel.pipeline().names());
		}
	}

	static boolean mustChunkFileTransfer(Connection c, Path file) {
		// if channel multiplexing a parent channel as an http2 stream
		if (c.channel().parent() != null && c.channel().parent().pipeline().get(Http2ConnectionHandler.class) != null) {
			return true;
		}
		ChannelPipeline p = c.channel().pipeline();
		return p.get(SslHandler.class) != null  ||
				p.get(NettyPipeline.CompressionHandler) != null ||
				(!(c.channel().eventLoop() instanceof NioEventLoop) &&
						!"file".equals(file.toUri().getScheme()));
	}

	static void registerForClose(boolean shouldCleanupOnClose,
			String name,
			Connection context) {
		if (!shouldCleanupOnClose) return;

		context.onTerminate().subscribe(null, null, () -> context.removeHandler(name));
	}

	static void removeHandler(Channel channel, String name){
		if (channel.isActive() && channel.pipeline()
		                                 .context(name) != null) {
			channel.pipeline()
			       .remove(name);
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Removed handler: {}, pipeline: {}"),
						name,
						channel.pipeline());
			}
		}
		else if (log.isDebugEnabled()) {
			log.debug(format(channel, "Non Removed handler: {}, context: {}, pipeline: {}"),
					name,
					channel.pipeline()
					       .context(name),
					channel.pipeline());
		}
	}

	static void replaceHandler(Channel channel, String name, ChannelHandler handler){
		if (channel.isActive() && channel.pipeline()
		                                 .context(name) != null) {
			channel.pipeline()
			       .replace(name, name, handler);
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Replaced handler: {}, pipeline: {}"),
						name,
						channel.pipeline());
			}
		}
		else if (log.isDebugEnabled()) {
			log.debug(format(channel, "Non Replaced handler: {}, context: {}, pipeline: {}"),
					name,
					channel.pipeline()
					       .context(name),
					channel.pipeline());
		}
	}

	static ConnectionObserver compositeConnectionObserver(ConnectionObserver observer,
			ConnectionObserver other) {

		if (observer == ConnectionObserver.emptyListener()) {
			return other;
		}

		if (other == ConnectionObserver.emptyListener()) {
			return observer;
		}

		final ConnectionObserver[] newObservers;
		final ConnectionObserver[] thizObservers;
		final ConnectionObserver[] otherObservers;
		int length = 2;

		if (observer instanceof CompositeConnectionObserver) {
			thizObservers = ((CompositeConnectionObserver)observer).observers;
			length += thizObservers.length - 1;
		}
		else {
			thizObservers = null;
		}

		if (other instanceof CompositeConnectionObserver) {
			otherObservers = ((CompositeConnectionObserver)other).observers;
			length += otherObservers.length - 1;
		}
		else {
			otherObservers = null;
		}

		newObservers = new ConnectionObserver[length];

		int pos;
		if (thizObservers != null) {
			pos = thizObservers.length;
			System.arraycopy(thizObservers, 0,
					newObservers, 0,
					pos);
		}
		else {
			pos = 1;
			newObservers[0] = observer;
		}

		if (otherObservers != null) {
			System.arraycopy(otherObservers, 0,
					newObservers, pos,
					otherObservers.length);
		}
		else {
			newObservers[pos] = other;
		}

		return new CompositeConnectionObserver(newObservers);
	}


	static <T, V> CorePublisher<V> publisherOrScalarMap(Publisher<T> publisher,
			Function<? super T, ? extends V> mapper) {

		if (publisher instanceof Callable) {
			return Mono.fromCallable(new ScalarMap<>(publisher, mapper));
		}
		else if (publisher instanceof Mono) {
			return ((Mono<T>)publisher).map(mapper);
		}

		return Flux.from(publisher)
		           .map(mapper);
	}

	ReactorNetty(){
	}

	static final class ScalarMap<T, V> implements Callable<V> {

		final Callable<T>                      source;
		final Function<? super T, ? extends V> mapper;

		@SuppressWarnings("unchecked")
		ScalarMap(Publisher<T> source, Function<? super T, ? extends V> mapper) {
			this.source = (Callable<T>) source;
			this.mapper = mapper;
		}

		@Override
		public V call() throws Exception {
			T called = source.call();
			if (called == null) {
				return null;
			}
			return mapper.apply(called);
		}
	}

	static final class CompositeConnectionObserver implements ConnectionObserver {

		final ConnectionObserver[] observers;

		CompositeConnectionObserver(ConnectionObserver[] observers) {
			this.observers = observers;
		}

		@Override
		public Context currentContext() {
			return observers[observers.length - 1].currentContext();
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			for (ConnectionObserver observer : observers) {
				observer.onUncaughtException(connection, error);
			}
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			for (ConnectionObserver observer : observers) {
				observer.onStateChange(connection, newState);
			}
		}
	}

	/**
	 * An appending write that delegates to its origin context and append the passed
	 * publisher after the origin success if any.
	 */
	static final class OutboundThen implements NettyOutbound {

		final NettyOutbound source;
		final Mono<Void> thenMono;

		static final Runnable EMPTY_CLEANUP = () -> {};


		OutboundThen(NettyOutbound source, Publisher<Void> thenPublisher) {
			this(source, thenPublisher, EMPTY_CLEANUP);
		}

		OutboundThen(NettyOutbound source, Publisher<Void> thenPublisher, Runnable onCleanup) {
			this.source = source;
			Objects.requireNonNull(onCleanup, "onCleanup");

			Mono<Void> parentMono = source.then();

			if (parentMono == Mono.<Void>empty()) {
				if (onCleanup == EMPTY_CLEANUP) {
					this.thenMono = Mono.from(thenPublisher);
				}
				else {
					this.thenMono = Mono.from(thenPublisher)
					                    .doOnCancel(onCleanup)
					                    .doOnError(t -> onCleanup.run());
				}
			}
			else {
				if (onCleanup == EMPTY_CLEANUP) {
					this.thenMono = parentMono.thenEmpty(thenPublisher);
				}
				else {
					this.thenMono = parentMono.thenEmpty(thenPublisher)
					                          .doOnCancel(onCleanup)
					                          .doOnError(t -> onCleanup.run());
				}
			}
		}

		@Override
		public <S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
				BiFunction<? super Connection, ? super S, ?> mappedInput,
				Consumer<? super S> sourceCleanup) {
			return then(source.sendUsing(sourceInput, mappedInput, sourceCleanup));
		}

		@Override
		public ByteBufAllocator alloc() {
			return source.alloc();
		}

		@Override
		public NettyOutbound withConnection(Consumer<? super Connection> withConnection) {
			return source.withConnection(withConnection);
		}

		@Override
		public NettyOutbound send(Publisher<? extends ByteBuf> dataStream, Predicate<ByteBuf> predicate) {
			return then(source.send(dataStream, predicate));
		}

		@Override
		public NettyOutbound sendObject(Publisher<?> dataStream, Predicate<Object> predicate) {
			return then(source.sendObject(dataStream, predicate));
		}

		@Override
		public NettyOutbound sendObject(Object message) {
			return then(source.sendObject(message),
					() -> ReactorNetty.safeRelease(message));
		}

		@Override
		public Mono<Void> then() {
			return thenMono;
		}
	}

	final static class OutboundIdleStateHandler extends IdleStateHandler {

		final Runnable onWriteIdle;

		OutboundIdleStateHandler(long idleTimeout, Runnable onWriteIdle) {
			super(0, idleTimeout, 0, TimeUnit.MILLISECONDS);
			this.onWriteIdle = onWriteIdle;
		}

		@Override
		protected void channelIdle(ChannelHandlerContext ctx,
				IdleStateEvent evt) throws Exception {
			if (evt.state() == IdleState.WRITER_IDLE) {
				onWriteIdle.run();
			}
			super.channelIdle(ctx, evt);
		}
	}

	final static class InboundIdleStateHandler extends IdleStateHandler {

		final Runnable onReadIdle;

		InboundIdleStateHandler(long idleTimeout, Runnable onReadIdle) {
			super(idleTimeout, 0, 0, TimeUnit.MILLISECONDS);
			this.onReadIdle = onReadIdle;
		}

		@Override
		protected void channelIdle(ChannelHandlerContext ctx,
				IdleStateEvent evt) throws Exception {
			if (evt.state() == IdleState.READER_IDLE) {
				onReadIdle.run();
			}
			super.channelIdle(ctx, evt);
		}
	}

	final static ConnectionObserver.State CONNECTED = new ConnectionObserver.State() {
		@Override
		public String toString() {
			return "[connected]";
		}
	};

	final static ConnectionObserver.State ACQUIRED = new ConnectionObserver.State() {
		@Override
		public String toString() {
			return "[acquired]";
		}
	};

	final static ConnectionObserver.State CONFIGURED = new ConnectionObserver.State() {
		@Override
		public String toString() {
			return "[configured]";
		}
	};

	final static ConnectionObserver.State RELEASED = new ConnectionObserver.State() {
		@Override
		public String toString() {
			return "[released]";
		}
	};

	final static ConnectionObserver.State DISCONNECTING = new ConnectionObserver.State() {
		@Override
		public String toString() {
			return "[disconnecting]";
		}
	};

	/**
	 * A handler that can be used to extract {@link ByteBuf} out of {@link ByteBufHolder},
	 * optionally also outputting additional messages
	 *
	 * @author Stephane Maldini
	 * @author Simon Baslé
	 */
	@ChannelHandler.Sharable
	static final class ExtractorHandler extends ChannelInboundHandlerAdapter {


		final BiConsumer<? super ChannelHandlerContext, Object> extractor;
		ExtractorHandler(BiConsumer<? super ChannelHandlerContext, Object> extractor) {
			this.extractor = Objects.requireNonNull(extractor, "extractor");
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			extractor.accept(ctx, msg);
		}

	}

	static final class ChannelDisposer extends BaseSubscriber<Void> {

		final DisposableChannel channelDisposable;
		ChannelDisposer(DisposableChannel channelDisposable) {
			this.channelDisposable = channelDisposable;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {
			request(Long.MAX_VALUE);
			channelDisposable.onDispose(this);
		}

		@Override
		protected void hookFinally(SignalType type) {
			if (type != SignalType.CANCEL) {
				channelDisposable.dispose();
			}
		}
	}

	static final class SimpleConnection implements Connection {

		final Channel channel;

		SimpleConnection(Channel channel) {
			this.channel = Objects.requireNonNull(channel, "channel");
		}

		@Override
		public Channel channel() {
			return channel;
		}

		@Override
		public String toString() {
			return "SimpleConnection{" + "channel=" + channel + '}';
		}
	}

	static NettyInbound unavailableInbound(Connection c) {
		return new NettyInbound() {
			@Override
			public ByteBufFlux receive() {
				return ByteBufFlux.fromInbound(Mono.error(
						new IllegalStateException("Receiver Unavailable. The Connection")));
			}

			@Override
			public Flux<?> receiveObject() {
				return Flux.error(new IllegalStateException("Receiver Unavailable"));
			}

			@Override
			public NettyInbound withConnection(Consumer<? super Connection> withConnection) {
				withConnection.accept(c);
				return this;
			}
		};
	}

	static NettyOutbound unavailableOutbound(Connection c) {
		return new NettyOutbound() {
			@Override
			public ByteBufAllocator alloc() {
				return c.channel().alloc();
			}

			@Override
			public NettyOutbound send(Publisher<? extends ByteBuf> dataStream, Predicate<ByteBuf> predicate) {
				return this;
			}

			@Override
			public NettyOutbound sendObject(Publisher<?> dataStream, Predicate<Object> predicate) {
				return this;
			}

			@Override
			public NettyOutbound sendObject(Object message) {
				return this;
			}

			@Override
			public <S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
					BiFunction<? super Connection, ? super S, ?> mappedInput,
					Consumer<? super S> sourceCleanup) {
				return this;
			}

			@Override
			public NettyOutbound withConnection(Consumer<? super Connection> withConnection) {
				withConnection.accept(c);
				return this;
			}

			@Override
			public Mono<Void> then() {
				return Mono.error(new IllegalStateException("Sender Unavailable"));
			}
		};
	}

	static final class InternalNettyException extends RuntimeException {

		InternalNettyException(Throwable cause) {
			super(cause);
		}

		@Override
		public synchronized Throwable fillInStackTrace() {
			return this;
		}
	}

	static final ConnectionObserver NOOP_LISTENER = (connection, newState) -> {};

	static final Logger log                               = Loggers.getLogger(ReactorNetty.class);
	static final AttributeKey<Boolean> PERSISTENT_CHANNEL = AttributeKey.newInstance("$PERSISTENT_CHANNEL");

	static final AttributeKey<Connection> CONNECTION = AttributeKey.newInstance("$CONNECTION");

	static final Consumer<? super FileChannel> fileCloser = fc -> {
		try {
			fc.close();
		}
		catch (Throwable e) {
			if (log.isTraceEnabled()) {
				log.trace("", e);
			}
		}
	};


	static final Predicate<ByteBuf>        PREDICATE_BB_FLUSH    = b -> false;

	static final Predicate<Object>         PREDICATE_FLUSH       = o -> false;

	static final ByteBuf                   BOUNDARY              = Unpooled.EMPTY_BUFFER;

	@SuppressWarnings("ReferenceEquality")
	//Design to use reference comparison here
	public static final Predicate<ByteBuf> PREDICATE_GROUP_FLUSH = b -> b == BOUNDARY;

}
