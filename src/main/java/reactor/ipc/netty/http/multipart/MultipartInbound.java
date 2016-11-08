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
import io.netty.util.ReferenceCounted;
import reactor.core.publisher.Flux;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.http.client.HttpClientResponse;

/**
 * An Http Reactive Multipart read contract for incoming traffic.
 *
 * @author Stephane Maldini
 * @since 2.5
 */
public interface MultipartInbound extends NettyInbound {

	/**
	 *
	 * @param inbound
	 * @return
	 */
	static Flux<ByteBufFlux> from(HttpClientResponse inbound) {
		String boundary = MultipartCodec.extractBoundary(inbound.responseHeaders());
		return new MultipartDecoder(inbound.receive(), boundary, inbound.channel().alloc());
	}

	/**
	 * a {@link ByteBuf} inbound parts as {@link Flux}
	 *
	 * @return a {@link ByteBuf} partitioned inbound {@link Flux}
	 */
	Flux<ByteBufFlux> receiveParts();

	@Override
	default ByteBufFlux receive() {
		return ByteBufFlux.from(receiveParts().onBackpressureError()
		                                      .concatMap(parts -> parts.aggregate()
		                                                               .retain())
		                                      .concatMap(bb -> Flux.using(() -> bb,
				                                      Flux::just,
				                                      ReferenceCounted::release)),
				channel().alloc());
	}

	@Override
	default Flux<?> receiveObject() {
		return receive();
	}
}
