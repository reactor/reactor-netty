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
package reactor.ipc.netty.http;

import java.net.InetSocketAddress;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Flux;
import reactor.ipc.netty.common.ByteBufEncodedFlux;
import reactor.ipc.netty.common.NettyChannel;
import reactor.ipc.netty.common.NettyInbound;
import reactor.ipc.netty.http.multipart.MultipartCodec;

/**
 * An Http Reactive read contract for incoming response. It inherits several accessor related to HTTP
 * flow : headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 2.5
 */
public interface HttpInbound extends HttpConnection, NettyInbound {

	/**
	 * @return the resolved response HTTP headers
	 */
	HttpHeaders responseHeaders();

	/**
	 * @return the resolved HTTP Response Status
	 */
	HttpResponseStatus status();

	/**
	 * a {@literal byte[]} inbound {@link Flux}
	 *
	 * @return a {@literal byte[]} inbound {@link Flux}
	 */
	default MultipartInbound receiveMultipart() {
		HttpInbound thiz = this;
		return new MultipartInbound() {
			@Override
			public Channel delegate() {
				return thiz.delegate();
			}

			@Override
			public Flux<ByteBufEncodedFlux> receiveParts() {
				return MultipartCodec.decode(thiz);
			}

			@Override
			public NettyChannel.Lifecycle on() {
				return thiz.on();
			}

			@Override
			public InetSocketAddress remoteAddress() {
				return thiz.remoteAddress();
			}
		};
	}
}
