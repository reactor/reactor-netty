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

package reactor.ipc.netty.common;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSource;

/**
 * A decorating {@link Flux} {@link NettyInbound} with various {@link ByteBuf} related
 * operations.
 *
 * @author Stephane Maldini
 */
public final class ByteBufEncodedFlux extends FluxSource<ByteBuf, ByteBuf> {

	/**
	 * Decorate as {@link ByteBufEncodedFlux}
	 *
	 * @param source publisher to decorate
	 * @param allocator the channel {@link ByteBufAllocator}
	 *
	 * @return a {@link ByteBufEncodedFlux}
	 */
	public final static ByteBufEncodedFlux encoded(Publisher<? extends ByteBuf> source,
			ByteBufAllocator allocator) {
		return new ByteBufEncodedFlux(source, allocator);
	}

	/**
	 * Disable auto memory release on each signal published in order to prevent premature
	 * recycling when buffers are accumulated downsteams (async).
	 *
	 * @return {@link ByteBufEncodedMono} of retained {@link ByteBuf}
	 */
	public ByteBufEncodedMono aggregate() {
		return using(alloc::compositeBuffer,
				b -> this.reduce(b, (prev, next) -> prev.addComponent(next.retain()))
				         .doOnNext(cbb -> cbb.writerIndex(cbb.capacity()))
				         .filter(ByteBuf::isReadable),
				ByteBuf::release).as(ByteBufEncodedMono::new);
	}

	/**
	 * Disable auto memory release on each signal published in order to prevent premature
	 * recycling when buffers are accumulated downsteams (async).
	 *
	 * @return {@link ByteBufEncodedMono} of retained {@link ByteBuf}
	 */
	public ByteBufEncodedMono multicast() {
		throw new UnsupportedOperationException("Not yet implemented");
	}

	/**
	 * Disable auto memory release on each signal published in order to prevent premature
	 * recycling when buffers are accumulated downsteams (async).
	 *
	 * @return {@link ByteBufEncodedFlux} of retained {@link ByteBuf}
	 */
	public ByteBufEncodedFlux retain() {
		return new ByteBufEncodedFlux(doOnNext(ByteBuf::retain), alloc);
	}

	final ByteBufAllocator alloc;

	protected ByteBufEncodedFlux(Publisher<? extends ByteBuf> source,
			ByteBufAllocator allocator) {
		super(source);
		this.alloc = allocator;
	}
}
