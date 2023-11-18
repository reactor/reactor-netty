/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.incubator.quic;

import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.TransportConnector;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;

public interface QuicServerConnectionProvider {

	default Mono<? extends Connection> getConnection(AbstractQuicServerConfig config) {
		Mono<? extends reactor.netty.Connection> mono = Mono.create(sink -> {
			SocketAddress local = Objects.requireNonNull(config.bindAddress().get(), "Bind Address supplier returned null");
			if (local instanceof InetSocketAddress) {
				InetSocketAddress localInet = (InetSocketAddress) local;

				if (localInet.isUnresolved()) {
					local = AddressUtils.createResolved(localInet.getHostName(), localInet.getPort());
				}
			}

			DisposableBind disposableBind = new DisposableBind(local, sink);
			TransportConnector.bind(config, config.parentChannelInitializer(), local, false)
					.subscribe(disposableBind);
		});

		if (config.doOnBind() != null) {
			mono = mono.doOnSubscribe(s -> config.doOnBind().accept(config));
		}
		return mono;
	}
}
