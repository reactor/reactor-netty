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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import reactor.netty.resources.LoopResources;

/**
 * @author Stephane Maldini
 */
final class TcpClientRunOn extends TcpClientOperator {

	final LoopResources loopResources;
	final boolean       preferNative;

	TcpClientRunOn(TcpClient client, LoopResources loopResources, boolean preferNative) {
		super(client);
		this.loopResources = Objects.requireNonNull(loopResources, "loopResources");
		this.preferNative = preferNative;
	}

	@Override
	public Bootstrap configure() {
		Bootstrap b = source.configure();

		configure(b, preferNative, loopResources);

		return b;
	}

	static void configure(Bootstrap b,
			boolean preferNative,
			LoopResources resources) {
		EventLoopGroup elg = resources.onClient(preferNative);

		b.group(elg).channel(resources.onChannel(elg));
	}
}
