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

package reactor.ipc.netty.http.multipart;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.FluxSource;
import reactor.ipc.netty.ByteBufFlux;

/**
 * @author Ben Hale
 */
final class MultipartDecoder extends FluxSource<ByteBuf, ByteBufFlux> {

	final String boundary;
	final ByteBufAllocator alloc;

	MultipartDecoder(Publisher<ByteBuf> source, String boundary, ByteBufAllocator alloc) {
		super(source);
		this.boundary = boundary;
		this.alloc = alloc;
	}

	@Override
	public void subscribe(Subscriber<? super ByteBufFlux> subscriber) {
		this.source.subscribe(new MultipartTokenizer(this.boundary,
				new MultipartParser(subscriber, alloc)));
	}
}
