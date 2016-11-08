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

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSource;
import reactor.core.publisher.Mono;

/**
 * A decorating {@link Flux} {@link NettyInbound} with various {@link ByteBuf} related
 * operations.
 *
 * @author Stephane Maldini
 */
public final class ByteBufFlux extends FluxSource<ByteBuf, ByteBuf> {

	/**
	 * Decorate as {@link ByteBufFlux}
	 *
	 * @param source publisher to decorate
	 * @param allocator the channel {@link ByteBufAllocator}
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public final static ByteBufFlux from(Publisher<?> source,
			ByteBufAllocator allocator) {
		return new ByteBufFlux(Flux.from(source), allocator);
	}

	/**
	 * a {@link ByteBuffer} inbound {@link Flux}
	 *
	 * @return a {@link ByteBuffer} inbound {@link Flux}
	 */
	public final Flux<ByteBuffer> asByteBuffer() {
		return map(ByteBuf::nioBuffer);
	}

	/**
	 * a {@literal byte[]} inbound {@link Flux}
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
	 * a {@link InputStream} inbound {@link Flux}
	 *
	 * @return a {@link InputStream} inbound {@link Flux}
	 */
	public Flux<InputStream> asInputStream() {
		return map(ByteBufMono.ReleasingInputStream::new);
	}

	/**
	 * a {@link String} inbound {@link Flux}
	 *
	 * @return a {@link String} inbound {@link Flux}
	 */
	public final Flux<String> asString() {
		return asString(Charset.defaultCharset());
	}

	/**
	 * a {@link String} inbound {@link Flux}
	 *
	 * @param charset the decoding charset
	 *
	 * @return a {@link String} inbound {@link Flux}
	 */
	public final Flux<String> asString(Charset charset) {
		return map(s -> s.toString(charset));
	}

	/**
	 * Disable auto memory release on each signal published in order to prevent premature
	 * recycling when buffers are accumulated downsteams (async).
	 *
	 * @return {@link ByteBufMono} of retained {@link ByteBuf}
	 */
	public ByteBufMono aggregate() {
		return Mono.using(alloc::compositeBuffer,
				b -> this.reduce(b, (prev, next) -> prev.addComponent(next.retain()))
				         .doOnNext(cbb -> cbb.writerIndex(cbb.capacity()))
				         .filter(ByteBuf::isReadable),
				ByteBuf::release).as(ByteBufMono::new);
	}

	/**
	 * Disable auto memory release on each signal published in order to prevent premature
	 * recycling when buffers are accumulated downsteams (async).
	 *
	 * @return {@link ByteBufMono} of retained {@link ByteBuf}
	 */
	public ByteBufMono multicast() {
		throw new UnsupportedOperationException("Not yet implemented");
	}

	/**
	 * Disable auto memory release on each signal published in order to prevent premature
	 * recycling when buffers are accumulated downsteams (async).
	 *
	 * @return {@link ByteBufFlux} of retained {@link ByteBuf}
	 */
	public ByteBufFlux retain() {
		return new ByteBufFlux(doOnNext(ByteBuf::retain), alloc);
	}

	final ByteBufAllocator alloc;

	protected ByteBufFlux(Flux<?> source, ByteBufAllocator allocator) {
		super(source.map(objectMapper));
		this.alloc = allocator;
	}

	@Override
	public void subscribe(Subscriber<? super ByteBuf> s) {
		source.subscribe(s);
	}

	/**
	 * A channel object to bytebuf transformer
	 */
	final static Function<Object, ByteBuf> objectMapper = o -> {
		if (o instanceof ByteBuf) {
			return (ByteBuf) o;
		}
		if (o instanceof ByteBufHolder) {
			return ((ByteBufHolder) o).content();
		}
		throw new IllegalArgumentException("Object " + o + " of type " + o.getClass() + " " + "cannot be converted to ByteBuf");
	};

}
