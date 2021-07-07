/*
 * Copyright (c) 2018-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import java.util.concurrent.ThreadFactory;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;

/**
 * An {@link EventLoopGroup} with associated {@link io.netty.channel.Channel} factory.
 *
 * @author Violeta Georgieva
 */
interface DefaultLoop {

	<CHANNEL extends Channel> CHANNEL getChannel(Class<CHANNEL> channelClass);

	<CHANNEL extends Channel> Class<? extends CHANNEL> getChannelClass(Class<CHANNEL> channelClass);

	String getName();

	EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory);

	boolean supportGroup(EventLoopGroup group);
}
