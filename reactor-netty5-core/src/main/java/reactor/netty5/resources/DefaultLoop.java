/*
 * Copyright (c) 2018-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.resources;

import java.net.ProtocolFamily;
import java.util.concurrent.ThreadFactory;

import io.netty5.channel.Channel;
import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.ServerChannel;
import reactor.util.annotation.Nullable;

/**
 * An {@link EventLoopGroup} with associated {@link io.netty5.channel.Channel} factory.
 *
 * @author Violeta Georgieva
 */
interface DefaultLoop {

	@Nullable
	<CHANNEL extends Channel> CHANNEL getChannel(Class<CHANNEL> channelClass, EventLoop eventLoop,
			@Nullable ProtocolFamily protocolFamily);

	String getName();

	@Nullable
	<SERVERCHANNEL extends ServerChannel> SERVERCHANNEL getServerChannel(Class<SERVERCHANNEL> channelClass, EventLoop eventLoop,
			EventLoopGroup childEventLoopGroup, @Nullable ProtocolFamily protocolFamily);

	EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory);
}
