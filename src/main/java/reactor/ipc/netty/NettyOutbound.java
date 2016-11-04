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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultFileRegion;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.connector.Outbound;

import static reactor.core.publisher.Flux.just;

/**
 * @author Stephane Maldini
 */
public interface NettyOutbound extends Outbound<ByteBuf> {

	/**
	 * Return the underlying {@link Channel}
	 * @return the underlying {@link Channel}
	 */
	Channel channel();

	/**
	 * Enable flush on each packet sent via {@link #send}, {@link #sendString},
	 * {@link #sendObject},{@link #sendByteArray}, {@link #sendByteBuffer}.
	 *
	 * @return {@code this} instance
	 */
	NettyOutbound flushEach();

	/**
	 * Return true  if underlying channel is closed or inbound bridge is detached
	 * @return true if underlying channel is closed or inbound bridge is detached
	 */
	boolean isDisposed();

	/**
	 * Assign a {@link Runnable} to be invoked when writes have become idle for the given timeout.
	 *
	 * @param idleTimeout the idle timeout
	 * @param onWriteIdle the idle timeout handler
	 * @return {@literal this}
	 */
	NettyOutbound onWriteIdle(long idleTimeout, Runnable onWriteIdle);

	/**
	 * Get the address of the remote peer.
	 *
	 * @return the peer's address
	 */
	InetSocketAddress remoteAddress();

	@Override
	default Mono<Void> send(Publisher<? extends ByteBuf> dataStream) {
		return sendObject(dataStream);
	}

	/**
	 * Send data to the peer, listen for any error on write and close on terminal signal
	 * (complete|error).
	 *
	 * @param dataStream the dataStream publishing OUT items to write on this channel
	 *
	 * @return A {@link Mono} to signal successful sequence write (e.g. after "flush") or
	 * any error during write
	 */
	default Mono<Void> sendOne(ByteBuf dataStream) {
		return send(just(dataStream));
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
	 * Send bytes to the peer, listen for any error on write and close on terminal signal
	 * (complete|error). If more than one publisher is attached (multiple calls to send())
	 * completion occurs after all publishers complete.
	 *
	 * @param dataStream the dataStream publishing Buffer items to write on this channel
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default Mono<Void> sendByteBuffer(Publisher<? extends ByteBuffer> dataStream) {
		return send(Flux.from(dataStream)
		                .map(Unpooled::wrappedBuffer));
	}

	/**
	 * Send File with zero-byte copy to the peer, listen for any error on write and close
	 * on terminal signal (complete|error). If more than one publisher is attached
	 * (multiple calls to send()) completion occurs after all publishers complete.
	 *
	 * @param file the dataStream publishing Buffer items to write on this channel
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default Mono<Void> sendFile(File file) {
		return sendFile(file, 0L, file.length());
	}

	/**
	 * Send File with zero-byte copy to the peer, listen for any error on write and close
	 * on terminal signal (complete|error). If more than one publisher is attached
	 * (multiple calls to send()) completion occurs after all publishers complete.
	 *
	 * @param file the dataStream publishing Buffer items to write on this channel
	 *
	 * @return A Publisher to signal successful sequence write (e.g. after "flush") or any
	 * error during write
	 */
	default Mono<Void> sendFile(File file, long position, long count) {
		return ChannelFutureMono.from(channel().writeAndFlush(new DefaultFileRegion(file,
				position,
				count)));
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
	default Mono<Void> sendGroups(Publisher<? extends Publisher<? extends ByteBuf>>
			dataStreams) {
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
	Mono<Void> sendObject(Publisher<?> dataStream);

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
		                .map(s -> channel().alloc()
		                                   .buffer()
		                                   .writeBytes(s.getBytes(charset))));
	}
}
