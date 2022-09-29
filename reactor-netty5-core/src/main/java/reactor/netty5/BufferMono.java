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
package reactor.netty5;

import io.netty5.buffer.BufferInputStream;
import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.buffer.CompositeBuffer;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoOperator;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

import static io.netty5.buffer.DefaultBufferAllocators.preferredAllocator;
import static java.util.Objects.requireNonNull;
import static reactor.netty5.BufferFlux.bufferExtractorFunction;

/**
 * A decorating {@link Mono} {@link NettyInbound} with various {@link Buffer} related
 * operations.
 *
 * @author Violeta Georgieva
 * @since 2.0.0
 */
public class BufferMono  extends MonoOperator<Buffer, Buffer> {

	/**
	 * a {@link ByteBuffer} inbound {@link Mono}
	 *
	 * @return a {@link ByteBuffer} inbound {@link Mono}
	 */
	public final Mono<ByteBuffer> asByteBuffer() {
		return handle((b, sink) -> {
			int readableBytes = b.readableBytes();
			ByteBuffer copy = b.isDirect() ? ByteBuffer.allocateDirect(readableBytes) : ByteBuffer.allocate(readableBytes);
			b.copyInto(b.readerOffset(), copy, 0, readableBytes);
			sink.next(copy);
		});
	}

	/**
	 * a {@literal byte[]} inbound {@link Mono}
	 *
	 * @return a {@literal byte[]} inbound {@link Mono}
	 */
	public final Mono<byte[]> asByteArray() {
		return handle((b, sink) -> {
			int readableBytes = b.readableBytes();
			byte[] copy = new byte[readableBytes];
			b.copyInto(b.readerOffset(), copy, 0, readableBytes);
			sink.next(copy);
		});
	}

	/**
	 * a {@link String} inbound {@link Mono}
	 *
	 * @return a {@link String} inbound {@link Mono}
	 */
	public final Mono<String> asString() {
		return asString(Charset.defaultCharset());
	}

	/**
	 * a {@link String} inbound {@link Mono}
	 *
	 * @param charset the decoding charset
	 *
	 * @return a {@link String} inbound {@link Mono}
	 */
	public final Mono<String> asString(Charset charset) {
		Objects.requireNonNull(charset, "charset");
		return handle((b, sink) -> sink.next(b.readCharSequence(b.readableBytes(), charset).toString()));
	}

	/**
	 * Convert to an {@link InputStream} inbound {@link Mono}
	 * <p><strong>Note:</strong> The underlying {@link Buffer} will be released only when {@link InputStream#close()} is invoked.
	 * Ensure {@link InputStream#close()} is invoked for any terminal signal:
	 * {@code complete} | {@code error} | {@code cancel}.
	 * </p>
	 *
	 * @return a {@link InputStream} inbound {@link Mono}
	 */
	public final Mono<InputStream> asInputStream() {
		return handle((b, sink) -> sink.next(new BufferInputStream(b.send())));
	}

	/**
	 * Decorate as {@link BufferMono}
	 *
	 * @param source publisher to decorate
	 * @return a {@link BufferMono}
	 */
	public static BufferMono fromString(Publisher<? extends String> source) {
		return fromString(source, Charset.defaultCharset(), preferredAllocator());
	}

	/**
	 * Decorate as {@link BufferMono}
	 *
	 * @param source publisher to decorate
	 * @param charset the encoding charset
	 * @param allocator the {@link BufferAllocator}
	 * @return a {@link BufferMono}
	 */
	public static BufferMono fromString(Publisher<? extends String> source, Charset charset, BufferAllocator allocator) {
		requireNonNull(allocator, "allocator");
		requireNonNull(charset, "charset");
		return maybeFuse(
				Mono.from(ReactorNetty.publisherOrScalarMap(
						source,
						s -> allocator.copyOf(s.getBytes(charset)),
						l -> {
							CompositeBuffer buffer = CompositeBuffer.compose(allocator);
							for (String s : l) {
								buffer.extendWith(allocator.copyOf(s.getBytes(charset)).send());
							}
							return buffer;
						})));
	}

	/**
	 * Returns {@link Mono} of split {@link Buffer}s.
	 * This transfers the ownership to the recipient.
	 * The caller of this method should ensure the {@link Buffer}s are closed.
	 *
	 * @return {@link Mono} of split {@link Buffer}s
	 */
	public final Mono<Buffer> transferOwnership() {
		return map(Buffer::split);
	}

	@Override
	public void subscribe(CoreSubscriber<? super Buffer> actual) {
		source.subscribe(actual);
	}

	BufferMono(Mono<?> source) {
		super(source.map(bufferExtractorFunction));
	}

	static BufferMono maybeFuse(Mono<?> source) {
		if (source instanceof Fuseable) {
			return new BufferMonoFuseable(source);
		}
		return new BufferMono(source);
	}

	static final class BufferMonoFuseable extends BufferMono implements Fuseable {

		BufferMonoFuseable(Mono<?> source) {
			super(source);
		}
	}
}
