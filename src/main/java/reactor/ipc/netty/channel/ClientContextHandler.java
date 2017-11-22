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

package reactor.ipc.netty.channel;

import java.net.SocketAddress;

import io.netty.channel.Channel;
import io.netty.handler.logging.LoggingHandler;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;

/**
 * @param <CHANNEL> the channel type
 *
 * @author Stephane Maldini
 */
final class ClientContextHandler<CHANNEL extends Channel>
		extends CloseableContextHandler<CHANNEL> {

	final boolean       secure;


	ClientContextHandler(ChannelOperations.OnSetup<CHANNEL> channelOpFactory,
			MonoSink<Connection> sink,
			LoggingHandler loggingHandler,
			boolean secure,
			SocketAddress providedAddress) {
		super(channelOpFactory, sink, loggingHandler, providedAddress);
		this.secure = secure;
	}

	@Override
	public final void fireContextActive(Connection context) {
		if(!fired) {
			fired = true;
			if(context != null) {
				sink.success(context);
			}
			else {
				sink.success();
			}
		}
	}

	@Override
	protected void doDropped(Channel channel) {
		channel.close();
		if(!fired) {
			fired = true;
			sink.error(new AbortedException("Channel has been dropped"));
		}
	}
}
