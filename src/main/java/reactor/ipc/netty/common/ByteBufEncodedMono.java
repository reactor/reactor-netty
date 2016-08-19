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

import java.io.InputStream;

import io.netty.buffer.ByteBuf;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSource;

/**
 * A decorating {@link Mono} {@link NettyInbound} with various {@link ByteBuf} related
 * operations.
 *
 * @author Stephane Maldini
 */
public final class ByteBufEncodedMono extends MonoSource<ByteBuf, ByteBuf> {

	/**
	 * Disable auto memory release on each signal published in order to prevent premature
	 * recycling when buffers are accumulated downsteams (async).
	 *
	 * @return {@link ByteBufEncodedMono} of retained {@link ByteBuf}
	 */
	public ByteBufEncodedMono retain() {
		return new ByteBufEncodedMono(doOnNext(ByteBuf::retain));
	}

	/**
	 * a {@link InputStream} inbound {@link Flux}
	 *
	 * @return a {@link InputStream} inbound {@link Flux}
	 */
	public Mono<InputStream> toInputStream() {
		return map(ReleasingBufferInputStream::new);
	}

	protected ByteBufEncodedMono(Publisher<? extends ByteBuf> source) {
		super(source);
	}

}
