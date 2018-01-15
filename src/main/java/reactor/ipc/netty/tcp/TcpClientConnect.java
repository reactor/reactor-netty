/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.tcp;

import io.netty.bootstrap.Bootstrap;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.resources.LoopResources;

/**
 * @author Stephane Maldini
 */
final class TcpClientConnect extends TcpClient {

	static final TcpClientConnect INSTANCE = new TcpClientConnect();

	@Override
	public Mono<? extends Connection> connect(Bootstrap b) {

		if (b.config()
		     .group() == null) {

			TcpClientRunOn.configure(b,
					LoopResources.DEFAULT_NATIVE,
					TcpResources.get(),
					TcpUtils.findSslContext(b));
		}

		return Mono.create(sink -> {
			Bootstrap bootstrap = b.clone();
			ChannelOperations.OnSetup ops = BootstrapHandlers.channelOperationFactory(bootstrap);
			TcpUtils.fromHostPortAttrsToRemote(bootstrap);

			BootstrapHandlers.finalize(bootstrap, ops, sink)
			                 .accept(bootstrap.connect());
		});

	}
}
