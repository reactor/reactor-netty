/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.options.ServerOptions;

/**
 *
 * @author Stephane Maldini
 */
final class ServerContextHandler
		extends CloseableContextHandler<Channel> {

	final ServerOptions serverOptions;

	ServerContextHandler(BiFunction<? super Channel, ? super ContextHandler<Channel>, ? extends ChannelOperations<?, ?>> channelOpSelector,
			ServerOptions options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler) {
		super(channelOpSelector, options, sink, loggingHandler);
		this.serverOptions = options;
	}

	@Override
	protected void doStarted(Channel channel) {
		sink.success(this);
	}

	@Override
	public final void fireContextActive(NettyContext context) {
		//Ignore, child channels cannot trigger context active
	}

	@Override
	public void fireContextError(Throwable t) {
		//Ignore, child channels cannot trigger context error
	}

	@Override
	public void terminateChannel(Channel channel) {
		if(channel.eventLoop().inEventLoop()){
			cleanChannel(channel);
		}
		else{
			channel.eventLoop().execute(() -> cleanChannel(channel));
		}
	}

	final void cleanChannel(Channel channel){
		cleanHandlers(channel);

		ChannelOperations<?, ?> op = channelOpSelector.apply(channel, this);

		channel.attr(ChannelOperations.OPERATIONS_ATTRIBUTE_KEY)
		       .set(op);

		op.onChannelActive(channel.pipeline()
		                          .context(NettyHandlerNames.ReactiveBridge));
	}

	@Override
	protected void doPipeline(ChannelPipeline pipeline) {
		addSslAndLogHandlers(options, sink, loggingHandler, true, pipeline);
	}
}
