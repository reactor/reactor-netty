/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

import io.netty5.buffer.BufferInputStream;
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.BufferAllocator;
import io.netty5.buffer.api.CompositeBuffer;
import io.netty5.util.Send;
import io.netty5.channel.socket.DatagramPacket;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.websocketx.WebSocketFrame;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

import static io.netty5.buffer.api.DefaultBufferAllocators.preferredAllocator;

/**
 * A decorating {@link Flux} {@link NettyInbound} with various {@link Buffer} related
 * operations.
 *
 * @author Violeta Georgieva
 * @since 2.0.0
 */
public class BufferFlux extends FluxOperator<Buffer, Buffer> {

	/**
	 * Decorate as {@link BufferFlux}
	 *
	 * @param source publisher to decorate
	 * @return a {@link BufferFlux}
	 */
	public static BufferFlux fromInbound(Publisher<?> source) {
		return fromInbound(source, preferredAllocator());
	}

	/**
	 * Decorate as {@link BufferFlux}
	 *
	 * @param source publisher to decorate
	 * @param allocator the channel {@link BufferAllocator}
	 * @return a {@link BufferFlux}
	 */
	public static BufferFlux fromInbound(Publisher<?> source, BufferAllocator allocator) {
		Objects.requireNonNull(allocator, "allocator");
		return maybeFuse(Flux.from(ReactorNetty.publisherOrScalarMap(source, bufferExtractor)), allocator);
	}

	/**
	 * Decorate as {@link BufferFlux}
	 *
	 * @param source publisher to decorate
	 * @return a {@link BufferFlux}
	 */
	public static BufferFlux fromString(Publisher<? extends String> source) {
		return fromString(source, Charset.defaultCharset(), preferredAllocator());
	}

	/**
	 * Decorate as {@link BufferFlux}
	 *
	 * @param source publisher to decorate
	 * @param charset the encoding charset
	 * @param allocator the {@link BufferAllocator}
	 * @return a {@link BufferFlux}
	 */
	public static BufferFlux fromString(Publisher<? extends String> source, Charset charset, BufferAllocator allocator) {
		Objects.requireNonNull(allocator, "allocator");
		Objects.requireNonNull(charset, "charset");
		return maybeFuse(Flux.from(ReactorNetty.publisherOrScalarMap(source, s -> allocator.copyOf(s.getBytes(charset)))),
				allocator);
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link Buffer} chunks with a default maximum size of 500K into
	 * the returned {@link BufferFlux}
	 *
	 * @param path the path to the resource to stream
	 * @return a {@link BufferFlux}
	 */
	public static BufferFlux fromPath(Path path) {
		return fromPath(path, MAX_CHUNK_SIZE);
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link Buffer} chunks with a given maximum size into the returned {@link BufferFlux}
	 *
	 * @param path the path to the resource to stream
	 * @param maxChunkSize the maximum per-item Buffer size
	 * @return a {@link BufferFlux}
	 */
	public static BufferFlux fromPath(Path path, int maxChunkSize) {
		return fromPath(path, maxChunkSize, preferredAllocator());
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link Buffer} chunks with a default maximum size of 500K into the returned
	 * {@link BufferFlux}, using the provided {@link BufferAllocator}.
	 *
	 * @param path the path to the resource to stream
	 * @param allocator the channel {@link BufferAllocator}
	 *
	 * @return a {@link BufferFlux}
	 */
	public static BufferFlux fromPath(Path path, BufferAllocator allocator) {
		return fromPath(path, MAX_CHUNK_SIZE, allocator);
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link Buffer} chunks with a given maximum size into the returned
	 * {@link BufferFlux}, using the provided {@link BufferAllocator}.
	 *
	 * @param path the path to the resource to stream
	 * @param maxChunkSize the maximum per-item Buffer size
	 * @param allocator the channel {@link BufferAllocator}
	 *
	 * @return a {@link BufferFlux}
	 */
	public static BufferFlux fromPath(Path path, int maxChunkSize, BufferAllocator allocator) {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(allocator, "allocator");
		if (maxChunkSize < 1) {
			throw new IllegalArgumentException("chunk size must be strictly positive, " + "was: " + maxChunkSize);
		}
		return maybeFuse(
				Flux.generate(() -> FileChannel.open(path),
						(fc, sink) -> {
							Buffer buf = allocator.allocate(maxChunkSize);
							try {
								if (buf.transferFrom(fc, maxChunkSize) < 0) {
									buf.close();
									sink.complete();
								}
								else {
									sink.next(buf);
								}
							}
							catch (IOException e) {
								buf.close();
								sink.error(e);
							}
							return fc;
						},
						ReactorNetty.fileCloser),
				allocator);
	}

	/**
	 * Convert to a {@link ByteBuffer} inbound {@link Flux}
	 *
	 * @return a {@link ByteBuffer} inbound {@link Flux}
	 */
	public final Flux<ByteBuffer> asByteBuffer() {
		return handle((b, sink) -> {
			int readableBytes = b.readableBytes();
			ByteBuffer copy = b.isDirect() ? ByteBuffer.allocateDirect(readableBytes) : ByteBuffer.allocate(readableBytes);
			b.copyInto(b.readerOffset(), copy, 0, readableBytes);
			sink.next(copy);
		});
	}

	/**
	 * Convert to a {@literal byte[]} inbound {@link Flux}
	 *
	 * @return a {@literal byte[]} inbound {@link Flux}
	 */
	public final Flux<byte[]> asByteArray() {
		return handle((b, sink) -> {
			int readableBytes = b.readableBytes();
			byte[] copy = new byte[readableBytes];
			b.copyInto(b.readerOffset(), copy, 0, readableBytes);
			sink.next(copy);
		});
	}

	/**
	 * Convert to an {@link InputStream} inbound {@link Flux}
	 * <p><strong>Note:</strong> The underlying {@link Buffer} will be released only when {@link InputStream#close()} is invoked.
	 * Ensure {@link InputStream#close()} is invoked for any terminal signal:
	 * {@code complete} | {@code error} | {@code cancel}.
	 * </p>
	 *
	 * @return a {@link InputStream} inbound {@link Flux}
	 */
	public final Flux<InputStream> asInputStream() {
		return handle((b, sink) -> sink.next(new BufferInputStream(b.send())));
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
		Objects.requireNonNull(charset, "charset");
		return handle((b, sink) -> sink.next(b.readCharSequence(b.readableBytes(), charset).toString()));
	}

	/**
	 * Aggregate subsequent byte buffers into a single buffer.
	 *
	 * @return {@link BufferMono} of aggregated {@link Buffer}
	 */
	@SuppressWarnings("rawtypes")
	public final BufferMono aggregate() {
		return Mono.defer(() -> {
					CompositeBuffer output = CompositeBuffer.compose(alloc);
					return map(Buffer::send)
							.collectList()
							.doOnDiscard(Send.class, Send::close)
							.handle((list, sink) -> {
								if (!list.isEmpty()) {
									for (Send<Buffer> send : list) {
										output.extendWith(send);
									}
								}
								if (output.readableBytes() > 0) {
									sink.next(output);
								}
								else {
									sink.complete();
								}
							})
							.doFinally(signalType -> output.close());
				})
				.as(BufferMono::maybeFuse);
	}

	/**
	 * Returns {@link Flux} of split {@link Buffer}s.
	 * This transfers the ownership to the recipient.
	 * The caller of this method should ensure the {@link Buffer}s are closed.
	 *
	 * @return {@link Flux} of split {@link Buffer}s
	 */
	public final Flux<Buffer> transferOwnership() {
		return map(Buffer::split);
	}

	final BufferAllocator alloc;

	BufferFlux(Flux<Buffer> source, BufferAllocator allocator) {
		super(source);
		this.alloc = allocator;
	}

	static final class BufferFluxFuseable extends BufferFlux implements Fuseable {

		BufferFluxFuseable(Flux<Buffer> source, BufferAllocator allocator) {
			super(source, allocator);
		}
	}

	@Override
	public void subscribe(CoreSubscriber<? super Buffer> s) {
		source.subscribe(s);
	}

	static BufferFlux maybeFuse(Flux<Buffer> source, BufferAllocator allocator) {
		if (source instanceof Fuseable) {
			return new BufferFlux.BufferFluxFuseable(source, allocator);
		}
		return new BufferFlux(source, allocator);
	}

	/**
	 * A channel object to {@link Buffer} transformer
	 */
	static final Function<Object, Buffer> bufferExtractor = o -> {
		if (o instanceof Buffer buffer) {
			return buffer;
		}
		if (o instanceof DatagramPacket envelope) {
			return envelope.content();
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

	static final int MAX_CHUNK_SIZE = 1024 * 512; //500k
}
