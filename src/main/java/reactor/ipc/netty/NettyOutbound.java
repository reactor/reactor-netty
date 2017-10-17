/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedNioFile;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.channel.data.AbstractFileChunkedStrategy;
import reactor.ipc.netty.channel.data.FileChunkedStrategy;

/**
 * @author Stephane Maldini
 */
public interface NettyOutbound extends Publisher<Void> {

	FileChunkedStrategy<ByteBuf> FILE_CHUNKED_STRATEGY_BUFFER = new AbstractFileChunkedStrategy<ByteBuf>() {

		@Override
		public ChunkedInput<ByteBuf> chunkFile(FileChannel fileChannel) {
			try {
				//TODO tune the chunk size
				return new ChunkedNioFile(fileChannel, 1024);
			}
			catch (IOException e) {
				throw Exceptions.propagate(e);
			}
		}
	};

	/**
	 * Return the assigned {@link ByteBufAllocator}.
	 *
	 * @return the {@link ByteBufAllocator}
	 */
	default ByteBufAllocator alloc() {
		return context().channel()
		                .alloc();
	}

	/**
	 * Return a {@link Connection} to operate on the underlying
	 * {@link Channel} state.
	 *
	 * @return the {@link Connection}
	 */
	Connection context();

	default FileChunkedStrategy getFileChunkedStrategy() {
		return FILE_CHUNKED_STRATEGY_BUFFER;
	}

	/**
	 * Return a never completing {@link Mono} after this {@link NettyOutbound#then()} has
	 * completed.
	 *
	 * @return a never completing {@link Mono} after this {@link NettyOutbound#then()} has
	 * completed.
	 */
	default Mono<Void> neverComplete() {
		return then(Mono.never()).then();
	}

	/**
	 * Assign a {@link Runnable} to be invoked when writes have become idle for the given
	 * timeout. This replaces any previously set idle callback.
	 *
	 * @param idleTimeout the idle timeout
	 * @param onWriteIdle the idle timeout handler
	 *
	 * @return {@literal this}
	 */
	default NettyOutbound onWriteIdle(long idleTimeout, Runnable onWriteIdle) {
		context().removeHandler(NettyPipeline.OnChannelWriteIdle);
		context().addHandlerFirst(NettyPipeline.OnChannelWriteIdle,
				new ReactorNetty.OutboundIdleStateHandler(idleTimeout, onWriteIdle));
		return this;
	}

	/**
	 * Provide a new {@link NettyOutbound} scoped configuration for sending. The
	 * {@link NettyPipeline.SendOptions} changes will apply to the next written object or
	 * {@link Publisher}.
	 *
	 * @param configurator the callback invoked to retrieve send configuration
	 *
	 * @return {@code this} instance
	 */
	default NettyOutbound options(Consumer<? super NettyPipeline.SendOptions> configurator) {
		context().channel()
		         .pipeline()
		         .fireUserEventTriggered(new NettyPipeline.SendOptionsChangeEvent(configurator, null));
		return this;
	}

	/**
	 * Send data to the peer, listen for any error on write and close on terminal signal
	 * (complete|error). <p>A new {@link NettyOutbound} type (or the same) for typed send
	 * sequences. An implementor can therefore specialize the Outbound after a first after
	 * a prepending data publisher.
	 *
	 * @param dataStream the dataStream publishing OUT items to write on this channel
	 *
	 * @return A new {@link NettyOutbound} to append further send. It will emit a complete
	 * signal successful sequence write (e.g. after "flush") or  any error during write.
	 */
	default NettyOutbound send(Publisher<? extends ByteBuf> dataStream) {
		return sendObject(dataStream);
	}

	/**
	 * Send bytes to the peer, listen for any error on write and close on terminal
	 * signal (complete|error). If more than one publisher is attached (multiple calls to
	 * send()) completion occurs after all publishers complete.
	 *
	 * @param dataStream the dataStream publishing Buffer items to write on this channel
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendByteArray(Publisher<? extends byte[]> dataStream) {
		return send(Flux.from(dataStream)
		                .map(Unpooled::wrappedBuffer));
	}

	/**
	 * Send content from given {@link Path} using
	 * {@link java.nio.channels.FileChannel#transferTo(long, long, WritableByteChannel)}
	 * support. If the system supports it and the path resolves to a local file
	 * system {@link File} then transfer will use zero-byte copy
	 * to the peer.
	 * <p>It will
	 * listen for any error on
	 * write and close
	 * on terminal signal (complete|error). If more than one publisher is attached
	 * (multiple calls to send()) completion occurs after all publishers complete.
	 * <p>
	 * Note: this will emit {@link io.netty.channel.FileRegion} in the outbound
	 * {@link io.netty.channel.ChannelPipeline}
	 *
	 * @param file the file Path
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendFile(Path file) {
		try {
			return sendFile(file, 0L, Files.size(file));
		}
		catch (IOException e) {
			return then(Mono.error(e));
		}
	}

	/**
	 * Send content from given {@link Path} using
	 * {@link java.nio.channels.FileChannel#transferTo(long, long, WritableByteChannel)}
	 * support. If the system supports it and the path resolves to a local file
	 * system {@link File} then transfer will use zero-byte copy
	 * to the peer.
	 * <p>It will
	 * listen for any error on
	 * write and close
	 * on terminal signal (complete|error). If more than one publisher is attached
	 * (multiple calls to send()) completion occurs after all publishers complete.
	 * <p>
	 * Note: this will emit {@link io.netty.channel.FileRegion} in the outbound
	 * {@link io.netty.channel.ChannelPipeline}
	 *
	 * @param file the file Path
	 * @param position where to start
	 * @param count how much to transfer
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendFile(Path file, long position, long count) {
		Objects.requireNonNull(file);
		if (context().channel().pipeline().get(SslHandler.class) != null) {
			return sendFileChunked(file, position, count);
		}

		return then(Mono.using(() -> FileChannel.open(file, StandardOpenOption.READ),
				fc -> FutureMono.from(context().channel().writeAndFlush(new DefaultFileRegion(fc, position, count))),
				fc -> {
					try {
						fc.close();
					}
					catch (IOException ioe) {/*IGNORE*/}
				}));
	}

	default NettyOutbound sendFileChunked(Path file, long position, long count) {
		Objects.requireNonNull(file);
		final FileChunkedStrategy strategy = getFileChunkedStrategy();
		final boolean needChunkedWriteHandler = context().channel().pipeline().get(NettyPipeline.ChunkedWriter) == null;
		if (needChunkedWriteHandler) {
			strategy.preparePipeline(context());
		}

		return then(Mono.using(() -> FileChannel.open(file, StandardOpenOption.READ),
				fc -> {
						try {
							ChunkedInput<?> message = strategy.chunkFile(fc);
							return FutureMono.from(context().channel().writeAndFlush(message));
						}
						catch (Exception e) {
							return Mono.error(e);
						}
				},
				fc -> {
					try {
						fc.close();
					}
					catch (IOException ioe) {/*IGNORE*/}
					finally {
						strategy.cleanupPipeline(context());
					}
				}));
	}

	/**
	 * Send data to the peer, listen for any error on write and close on terminal signal
	 * (complete|error).Each individual {@link Publisher} completion will flush
	 * the underlying IO runtime.
	 *
	 * @param dataStreams the dataStream publishing OUT items to write on this channel
	 *
	 * @return A {@link Mono} to signal successful sequence write (e.g. after "flush") or
	 * any error during write
	 */
	default NettyOutbound sendGroups(Publisher<? extends Publisher<? extends ByteBuf>> dataStreams) {
		return then(Flux.from(dataStreams)
		           .concatMapDelayError(this::send, false, 32)
		           .then());
	}

	/**
	 * Send Object to the peer, listen for any error on write and close on terminal signal
	 * (complete|error). If more than one publisher is attached (multiple calls to send())
	 * completion occurs after all publishers complete.
	 *
	 * @param dataStream the dataStream publishing Buffer items to write on this channel
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendObject(Publisher<?> dataStream) {
		return then(FutureMono.deferFuture(() -> context().channel()
		                                                  .writeAndFlush(dataStream)));
	}

	/**
	 * Send data to the peer, listen for any error on write and close on terminal signal
	 * (complete|error).
	 *
	 * @param msg the object to publish
	 *
	 * @return A {@link Mono} to signal successful sequence write (e.g. after "flush") or
	 * any error during write
	 */
	default NettyOutbound sendObject(Object msg) {
		return then(FutureMono.deferFuture(() -> context().channel()
		                                                  .writeAndFlush(msg)));
	}

	/**
	 * Send String to the peer, listen for any error on write and close on terminal signal
	 * (complete|error). If more than one publisher is attached (multiple calls to send())
	 * completion occurs after all publishers complete.
	 *
	 * @param dataStream the dataStream publishing Buffer items to write on this channel
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendString(Publisher<? extends String> dataStream) {
		return sendString(dataStream, Charset.defaultCharset());
	}

	/**
	 * Send String to the peer, listen for any error on write and close on terminal signal
	 * (complete|error). If more than one publisher is attached (multiple calls to send())
	 * completion occurs after all publishers complete.
	 *
	 * @param dataStream the dataStream publishing Buffer items to write on this channel
	 * @param charset the encoding charset
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default NettyOutbound sendString(Publisher<? extends String> dataStream,
			Charset charset) {
		return sendObject(Flux.from(dataStream)
		                      .map(s -> alloc()
		                                   .buffer()
		                                   .writeBytes(s.getBytes(charset))));
	}

	/**
	 * Subscribe a {@code Void} subscriber to this outbound and trigger all eventual
	 * parent outbound send.
	 *
	 * @param s the {@link Subscriber} to listen for send sequence completion/failure
	 */
	@Override
	default void subscribe(Subscriber<? super Void> s) {
		then().subscribe(s);
	}

	/**
	 * Obtain a {@link Mono} of pending outbound(s) write completion.
	 *
	 * @return a {@link Mono} of pending outbound(s) write completion
	 */
	default Mono<Void> then() {
		return Mono.empty();
	}

	/**
	 * Append a {@link Publisher} task such as a Mono and return a new
	 * {@link NettyOutbound} to sequence further send.
	 *
	 * @param other the {@link Publisher} to subscribe to when this pending outbound
	 * {@link #then()} is complete;
	 *
	 * @return a new {@link NettyOutbound} that
	 */
	default NettyOutbound then(Publisher<Void> other) {
		return new ReactorNetty.OutboundThen(this, other);
	}


	/**
	 * Immediately call the passed callback with a {@link Connection} to operate on the
	 * underlying
	 * {@link Channel} state. This allows for chaining outbound API.
	 *
	 * @param withConnection context callback
	 *
	 * @return the {@link Connection}
	 */
	NettyOutbound withConnection(Consumer<? super Connection> withConnection);
}
