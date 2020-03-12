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

/**
 * Netty <-> Reactive Streams bridge via channel handler.
 * <p>
 * {@link reactor.netty.channel.ChannelOperations} will be exposed as the user edge API
 * and extended by various protocols such as HTTP. It will convert incoming read into
 * {@link reactor.netty.NettyInbound#receive()} and apply read backpressure.
 * <p>
 * {@link reactor.netty.channel.ChannelOperationsHandler} will tail the
 * channel pipeline and convert {@link org.reactivestreams.Publisher} to outgoing write.
 */
@NonNullApi
package reactor.netty.channel;

import reactor.util.annotation.NonNullApi;