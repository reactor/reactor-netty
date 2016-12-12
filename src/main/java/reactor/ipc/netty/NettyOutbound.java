/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultFileRegion;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.connector.Outbound;

/**
 * @author Stephane Maldini
 */
public interface NettyOutbound extends Outbound<ByteBuf> {

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
	 * Return a {@link NettyContext} to operate on the underlying
	 * {@link Channel} state.
	 *
	 * @return the {@link NettyContext}
	 */
	NettyContext context();

	/**
	 * Enable flush on each packet sent via {@link #send}, {@link #sendString},
	 * {@link #sendObject},{@link #sendByteArray}.
	 *
	 * @return {@code this} instance
	 */
	NettyOutbound flushEach();

	/**
	 * Assign a {@link Runnable} to be invoked when writes have become idle for the given
	 * timeout.
	 *
	 * @param idleTimeout the idle timeout
	 * @param onWriteIdle the idle timeout handler
	 *
	 * @return {@literal this}
	 */
	default NettyOutbound onWriteIdle(long idleTimeout, Runnable onWriteIdle) {
		context().addHandler(NettyHandlerNames.OnChannelWriteIdle,
				new ByteBufFlux.OutboundIdleStateHandler(idleTimeout, onWriteIdle));
		return this;
	}

	@Override
	default Mono<Void> send(Publisher<? extends ByteBuf> dataStream) {
		return sendObject(dataStream);
	}

	/**
	 * /** Send bytes to the peer, listen for any error on write and close on terminal
	 * signal (complete|error). If more than one publisher is attached (multiple calls to
	 * send()) completion occurs after all publishers complete.
	 *
	 * @param dataStream the dataStream publishing Buffer items to write on this channel
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default Mono<Void> sendByteArray(Publisher<? extends byte[]> dataStream) {
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
	default Mono<Void> sendFile(Path file) {
		try {
			return sendFile(file, 0L, Files.size(file));
		}
		catch (IOException e) {
			return Mono.error(e);
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
	default Mono<Void> sendFile(Path file, long position, long count) {
		Objects.requireNonNull(file);
		return Mono.using(() -> FileChannel.open(file, StandardOpenOption.READ),
				fc -> FutureMono.from(context().channel()
				                               .writeAndFlush(new DefaultFileRegion(fc,
						                               position,
						                               count))),
				fc -> {
					try {
						fc.close();
					}
					catch (IOException ioe) {/*IGNORE*/}
				});
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
	default Mono<Void> sendGroups(Publisher<? extends Publisher<? extends ByteBuf>> dataStreams) {
		return Flux.from(dataStreams)
		           .concatMapDelayError(this::send, false, 32)
		           .then();
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
	default Mono<Void> sendObject(Publisher<?> dataStream) {
		return FutureMono.deferFuture(() -> context().channel()
		                                             .writeAndFlush(dataStream));
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
	default Mono<Void> sendObject(Object msg) {
		return FutureMono.deferFuture(() -> context().channel()
		                                             .write(msg));
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
	default Mono<Void> sendString(Publisher<? extends String> dataStream) {
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
	default Mono<Void> sendString(Publisher<? extends String> dataStream,
			Charset charset) {
		return send(Flux.from(dataStream)
		                .map(s -> context().channel()
		                                   .alloc()
		                                   .buffer()
		                                   .writeBytes(s.getBytes(charset))));
	}

}
