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
package reactor.netty.udp;

import java.util.Objects;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import reactor.netty.resources.LoopResources;

/**
 * @author Stephane Maldini
 */
final class UdpServerRunOn extends UdpServerOperator {

	final LoopResources          loopResources;
	final boolean                preferNative;
	final InternetProtocolFamily family;

	UdpServerRunOn(UdpServer server,
			LoopResources loopResources,
			boolean preferNative,
			@Nullable InternetProtocolFamily family) {
		super(server);
		this.loopResources = Objects.requireNonNull(loopResources, "loopResources");
		this.preferNative = preferNative;
		this.family = family;
	}

	@Override
	protected Bootstrap configure() {
		Bootstrap b = source.configure();

		boolean useNative = family == null && preferNative;

		EventLoopGroup elg = loopResources.onClient(useNative);

		if (useNative) {
			b.channel(loopResources.onDatagramChannel(elg));
		}
		else {
			b.channelFactory(() -> new NioDatagramChannel(family));
		}

		return b.group(elg);
	}
}
