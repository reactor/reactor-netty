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

package reactor.ipc.netty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;

/**
 * A decorating {@link Flux} {@link NettyInbound} with various {@link ByteBuf} related
 * operations.
 *
 * @author Stephane Maldini
 */
public final class ByteBufFlux extends FluxOperator<ByteBuf, ByteBuf> {

	/**
	 * Decorate as {@link ByteBufFlux}
	 *
	 * @param source publisher to decorate
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromInbound(Publisher<?> source) {
		return fromInbound(source, ByteBufAllocator.DEFAULT);
	}

	/**
	 * Decorate as {@link ByteBufFlux}
	 *
	 * @param source publisher to decorate
	 * @param allocator the channel {@link ByteBufAllocator}
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromInbound(Publisher<?> source,
			ByteBufAllocator allocator) {
		Objects.requireNonNull(allocator, "allocator");
		return new ByteBufFlux(Flux.from(source)
		                           .map(bytebufExtractor), allocator);
	}

	public static ByteBufFlux fromString(Publisher<? extends String> source) {
		return fromString(source, Charset.defaultCharset(), ByteBufAllocator.DEFAULT);
	}

	public static ByteBufFlux fromString(Publisher<? extends String> source, Charset charset, ByteBufAllocator allocator) {
		Objects.requireNonNull(allocator, "allocator");
		return new ByteBufFlux(Flux.from(source)
		                           .map(s -> allocator.buffer()
		                                              .writeBytes(s.getBytes(charset))),
		                       allocator);
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link ByteBuf} chunks with a default maximum size of 500K into
	 * the returned {@link ByteBufFlux}
	 *
	 * @param path the path to the resource to stream
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromPath(Path path) {
		return fromPath(path, MAX_CHUNK_SIZE);
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link ByteBuf} chunks with a given maximum size into the returned {@link ByteBufFlux}
	 *
	 * @param path the path to the resource to stream
	 * @param maxChunkSize the maximum per-item ByteBuf size
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromPath(Path path, int maxChunkSize) {
		return fromPath(path, maxChunkSize, ByteBufAllocator.DEFAULT);
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link ByteBuf} chunks with a default maximum size of 500K into the returned
	 * {@link ByteBufFlux}, using the provided {@link ByteBufAllocator}.
	 *
	 * @param path the path to the resource to stream
	 * @param allocator the channel {@link ByteBufAllocator}
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromPath(Path path, ByteBufAllocator allocator) {
		return fromPath(path, MAX_CHUNK_SIZE, allocator);
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link ByteBuf} chunks with a given maximum size into the returned
	 * {@link ByteBufFlux}, using the provided {@link ByteBufAllocator}.
	 *
	 * @param path the path to the resource to stream
	 * @param maxChunkSize the maximum per-item ByteBuf size
	 * @param allocator the channel {@link ByteBufAllocator}
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromPath(Path path,
			int maxChunkSize,
			ByteBufAllocator allocator) {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(allocator, "allocator");
		if (maxChunkSize < 1) {
			throw new IllegalArgumentException("chunk size must be strictly positive, " + "was: " + maxChunkSize);
		}
		return new ByteBufFlux(Flux.generate(() -> FileChannel.open(path), (fc, sink) -> {
			ByteBuf buf = allocator.buffer();
			try {
				if (buf.writeBytes(fc, maxChunkSize) < 0) {
					buf.release();
					sink.complete();
				}
				else {
					sink.next(buf);
				}
			}
			catch (IOException e) {
				buf.release();
				sink.error(e);
			}
			return fc;
		}), allocator);
	}

	/**
	 * Convert to a {@link ByteBuffer} inbound {@link Flux}
	 *
	 * @return a {@link ByteBuffer} inbound {@link Flux}
	 */
	public final Flux<ByteBuffer> asByteBuffer() {
		return map(ByteBuf::nioBuffer);
	}

	/**
	 * Convert to a {@literal byte[]} inbound {@link Flux}
	 *
	 * @return a {@literal byte[]} inbound {@link Flux}
	 */
	public final Flux<byte[]> asByteArray() {
		return map(bb -> {
			byte[] bytes = new byte[bb.readableBytes()];
			bb.readBytes(bytes);
			return bytes;
		});
	}

	/**
	 * Convert to a {@link InputStream} inbound {@link Flux}
	 *
	 * @return a {@link InputStream} inbound {@link Flux}
	 */
	public Flux<InputStream> asInputStream() {
		return map(ByteBufMono.ReleasingInputStream::new);
	}

	/**
	 * Convert to a {@link String} inbound {@link Flux} using the default {@link Charset}.
	 *
	 * @return a {@link String} inbound {@link Flux}
	 */
	public final Flux<String> asString() {
		return asString(Charset.defaultCharset());
	}

	/**
	 * Convert to a {@link String} inbound {@link Flux} using the provided {@link Charset}.
	 *
	 * @param charset the decoding charset
	 *
	 * @return a {@link String} inbound {@link Flux}
	 */
	public final Flux<String> asString(Charset charset) {
		return map(bb -> bb.toString(charset));
	}

	/**
	 * Aggregate subsequent byte buffers into a single buffer.
	 *
	 * @return {@link ByteBufMono} of aggregated {@link ByteBuf}
	 */
	public ByteBufMono aggregate() {
		return Mono.using(alloc::compositeBuffer,
				b -> this.reduce(b, (prev, next) -> prev.addComponent(next.retain()))
				         .doOnNext(cbb -> cbb.writerIndex(cbb.capacity()))
				         .filter(ByteBuf::isReadable),
				ByteBuf::release).as(ByteBufMono::new);
	}

	/**
	 * Allow multiple consumers downstream of the flux while also disabling auto memory
	 * release on each buffer published (retaining in order to prevent premature recycling).
	 *
	 * @return {@link ByteBufMono} of retained {@link ByteBuf}
	 */
	public ByteBufMono multicast() {
		throw new UnsupportedOperationException("Not yet implemented");
	}

	/**
	 * Disable auto memory release on each buffer published, retaining in order to prevent
	 * premature recycling when buffers are accumulated downstream (async).
	 *
	 * @return {@link ByteBufFlux} of retained {@link ByteBuf}
	 */
	public ByteBufFlux retain() {
		return new ByteBufFlux(doOnNext(ByteBuf::retain), alloc);
	}

	final ByteBufAllocator alloc;

	ByteBufFlux(Flux<ByteBuf> source, ByteBufAllocator allocator) {
		super(source);
		this.alloc = allocator;
	}

	@Override
	public void subscribe(CoreSubscriber<? super ByteBuf> s) {
		source.subscribe(s);
	}

	/**
	 * A channel object to {@link ByteBuf} transformer
	 */
	final static Function<Object, ByteBuf> bytebufExtractor = o -> {
		if (o instanceof ByteBuf) {
			return (ByteBuf) o;
		}
		if (o instanceof ByteBufHolder) {
			return ((ByteBufHolder) o).content();
		}
		throw new IllegalArgumentException("Object " + o + " of type " + o.getClass() + " " + "cannot be converted to ByteBuf");
	};

	final static int MAX_CHUNK_SIZE = 1024 * 512; //500k
}
