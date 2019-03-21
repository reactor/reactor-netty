/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoOperator;

/**
 * A decorating {@link Mono} {@link NettyInbound} with various {@link ByteBuf} related
 * operations.
 *
 * @author Stephane Maldini
 */
public final class ByteBufMono extends MonoOperator<ByteBuf, ByteBuf> {

	/**
	 * a {@link ByteBuffer} inbound {@link Mono}
	 *
	 * @return a {@link ByteBuffer} inbound {@link Mono}
	 */
	public final Mono<ByteBuffer> asByteBuffer() {
		return map(ByteBuf::nioBuffer);
	}

	/**
	 * a {@literal byte[]} inbound {@link Mono}
	 *
	 * @return a {@literal byte[]} inbound {@link Mono}
	 */
	public final Mono<byte[]> asByteArray() {
		return map(bb -> {
			byte[] bytes = new byte[bb.readableBytes()];
			bb.readBytes(bytes);
			return bytes;
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
		return map(s -> s.toString(charset));
	}

	/**
	 * Convert to an {@link InputStream} inbound {@link Mono}
	 *
	 * @return a {@link InputStream} inbound {@link Mono}
	 */
	public Mono<InputStream> asInputStream() {
		return map(ReleasingInputStream::new);
	}

	/**
	 * Disable auto memory release on each signal published in order to prevent premature
	 * recycling when buffers are accumulated downstream (async).
	 *
	 * @return {@link ByteBufMono} of retained {@link ByteBuf}
	 */
	public ByteBufMono retain() {
		return new ByteBufMono(doOnNext(ByteBuf::retain));
	}

	@Override
	public void subscribe(CoreSubscriber<? super ByteBuf> actual) {
		source.subscribe(actual);
	}

	protected ByteBufMono(Mono<?> source) {
		super(source.map(ByteBufFlux.bytebufExtractor));
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
