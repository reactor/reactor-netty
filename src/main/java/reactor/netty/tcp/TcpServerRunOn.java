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

package reactor.netty.tcp;

import java.util.Objects;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import reactor.netty.resources.LoopResources;

/**
 * @author Stephane Maldini
 */
final class TcpServerRunOn extends TcpServerOperator {

	final LoopResources loopResources;
	final boolean       preferNative;

	TcpServerRunOn(TcpServer server, LoopResources loopResources, boolean preferNative) {
		super(server);
		this.loopResources = Objects.requireNonNull(loopResources, "loopResources");
		this.preferNative = preferNative;
	}

	@Override
	public ServerBootstrap configure() {
		ServerBootstrap b = source.configure();

		configure(b, preferNative, loopResources);

		return b;
	}

	static void configure(ServerBootstrap b,
			boolean preferNative,
			LoopResources resources) {

		EventLoopGroup selectorGroup = resources.onServerSelect(preferNative);
		EventLoopGroup elg = resources.onServer(preferNative);

		b.group(selectorGroup, elg)
		 .channel(resources.onServerChannel(elg));
	}

}
