/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.JdkSslContext;
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
		Integer maxConnections = (Integer) b.config().attrs().get(TcpClientConnect.MAX_CONNECTIONS);

		configure(b, preferNative, loopResources, maxConnections != null && maxConnections != -1);

		return b;
	}

	static void configure(Bootstrap b,
			boolean preferNative,
			LoopResources resources,
			boolean useDelegate) {
		EventLoopGroup elg = resources.onClient(preferNative);

		if (useDelegate && elg instanceof Supplier) {
			EventLoopGroup delegate = (EventLoopGroup) ((Supplier) elg).get();
			b.group(delegate)
			 .channel(resources.onChannel(delegate));
		}
		else {
			b.group(elg)
			 .channel(resources.onChannel(elg));
		}
	}
}
