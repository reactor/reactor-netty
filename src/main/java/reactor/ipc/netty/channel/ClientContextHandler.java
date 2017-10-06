/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.ProxyHandler;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.options.ClientOptions;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

/**
 * @param <CHANNEL> the channel type
 *
 * @author Stephane Maldini
 */
final class ClientContextHandler<CHANNEL extends Channel>
		extends CloseableContextHandler<CHANNEL> {

	final ClientOptions clientOptions;
	final boolean       secure;


	ClientContextHandler(ChannelOperations.OnNew<CHANNEL> channelOpFactory,
			ClientOptions options,
			MonoSink<Connection> sink,
			LoggingHandler loggingHandler,
			boolean secure,
			SocketAddress providedAddress) {
		super(channelOpFactory, options, sink, loggingHandler, providedAddress);
		this.clientOptions = options;
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

	@Override
	protected Tuple2<String, Integer> getSNI() {
		if (providedAddress instanceof InetSocketAddress) {
			InetSocketAddress ipa = (InetSocketAddress) providedAddress;
			return Tuples.of(ipa.getHostString(), ipa.getPort());
		}
		return null;
	}

	@Override
	protected void doPipeline(Channel ch) {
		addSslAndLogHandlers(clientOptions, this, loggingHandler, secure, getSNI(), ch.pipeline());
		addProxyHandler(clientOptions, ch.pipeline(), providedAddress);
	}

	static void addProxyHandler(ClientOptions clientOptions, ChannelPipeline pipeline, SocketAddress providedAddress) {
		ProxyHandler proxy = clientOptions.useProxy(providedAddress) ? clientOptions.getProxyOptions().newProxyHandler() : null;
		if (proxy != null) {
			pipeline.addFirst(NettyPipeline.ProxyHandler, proxy);
			if(log.isDebugEnabled()){
				pipeline.addFirst(new LoggingHandler("reactor.ipc.netty.proxy"));
			}
		}
	}
}
