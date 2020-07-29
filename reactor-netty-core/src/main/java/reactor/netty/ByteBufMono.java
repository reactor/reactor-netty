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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.util.IllegalReferenceCountException;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoOperator;

/**
 * A decorating {@link Mono} {@link NettyInbound} with various {@link ByteBuf} related
 * operations.
 *
 * @author Stephane Maldini
 */
public class ByteBufMono extends MonoOperator<ByteBuf, ByteBuf> {

	/**
	 * a {@link ByteBuffer} inbound {@link Mono}
	 *
	 * @return a {@link ByteBuffer} inbound {@link Mono}
	 */
	public final Mono<ByteBuffer> asByteBuffer() {
		return handle((bb, sink) -> {
			try {
				sink.next(bb.nioBuffer());
			}
			catch (IllegalReferenceCountException e) {
				sink.complete();
			}
		});
	}

	/**
	 * a {@literal byte[]} inbound {@link Mono}
	 *
	 * @return a {@literal byte[]} inbound {@link Mono}
	 */
	public final Mono<byte[]> asByteArray() {
		return handle((bb, sink) -> {
			try {
				byte[] bytes = new byte[bb.readableBytes()];
				bb.readBytes(bytes);
				sink.next(bytes);
			}
			catch (IllegalReferenceCountException e) {
				sink.complete();
			}
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
		return handle((bb, sink) -> {
			try {
				sink.next(bb.readCharSequence(bb.readableBytes(), charset).toString());
			}
			catch (IllegalReferenceCountException e) {
				sink.complete();
			}
		});
	}

	/**
	 * Convert to an {@link InputStream} inbound {@link Mono}
	 * <p>Note: Auto memory release is disabled. The underlying
	 * {@link ByteBuf} will be released only when {@link InputStream#close()}
	 * is invoked. Ensure {@link InputStream#close()} is invoked
	 * for any terminal signal: {@code complete} | {@code error} | {@code cancel}</p>
	 *
	 * @return a {@link InputStream} inbound {@link Mono}
	 */
	public final Mono<InputStream> asInputStream() {
		return handle((bb, sink) -> {
			try {
				sink.next(new ReleasingInputStream(bb));
			}
			catch (IllegalReferenceCountException e) {
				sink.complete();
			}
		});
	}

	/**
	 * Disable auto memory release on each signal published in order to prevent premature
	 * recycling when buffers are accumulated downstream (async).
	 *
	 * @return {@link ByteBufMono} of retained {@link ByteBuf}
	 */
	public final ByteBufMono retain() {
		return maybeFuse(doOnNext(ByteBuf::retain));
	}

	@Override
	public void subscribe(CoreSubscriber<? super ByteBuf> actual) {
		source.subscribe(actual);
	}

	ByteBufMono(Mono<?> source) {
		super(source.map(ByteBufFlux.bytebufExtractor));
	}

	static ByteBufMono maybeFuse(Mono<?> source) {
		if (source instanceof Fuseable) {
			return new ByteBufMonoFuseable(source);
		}
		return new ByteBufMono(source);
	}

	static final class ByteBufMonoFuseable extends ByteBufMono implements Fuseable {

		ByteBufMonoFuseable(Mono<?> source) {
			super(source);
		}
	}

	static final class ReleasingInputStream extends ByteBufInputStream {

		final ByteBuf bb;

		volatile int closed;

		static final AtomicIntegerFieldUpdater<ReleasingInputStream> CLOSE =
		AtomicIntegerFieldUpdater.newUpdater(ReleasingInputStream.class, "closed");

		ReleasingInputStream(ByteBuf bb) {
			super(bb.retain());
			this.bb = bb;
		}

		@Override
		public void close() throws IOException {
			if(CLOSE.compareAndSet(this, 0, 1)) {
				super.close();
				bb.release();
			}
		}
	}
}
