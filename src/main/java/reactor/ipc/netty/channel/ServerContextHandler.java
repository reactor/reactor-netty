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

import java.util.function.BiConsumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ServerChannel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import reactor.core.Cancellation;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.options.ServerOptions;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 *
 * @author Stephane Maldini
 */
final class ServerContextHandler
		extends CloseableContextHandler<Channel> {

	static final Logger log = Loggers.getLogger(ServerContextHandler.class);

	final ServerOptions serverOptions;

	ServerContextHandler(BiConsumer<? super Channel, ? super Cancellation> doWithPipeline,
			ServerOptions options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler) {
		super(doWithPipeline, options, sink, loggingHandler);
		this.serverOptions = options;
	}

	@Override
	protected void doStarted(Channel channel) {
		sink.success(new ChannelCancellation(channel, this));
	}

	@Override
	protected Cancellation doChannelTerminated(Channel channel) {
		return EMPTY;
	}

	@Override
	protected void doPipeline(ChannelPipeline pipeline) {
		addSslAndLogHandlers(pipeline);
	}

	void addSslAndLogHandlers(ChannelPipeline pipeline) {
		SslHandler sslHandler = options.getSslHandler(pipeline.channel().alloc());
		if (sslHandler != null) {
			pipeline.addFirst(NettyHandlerNames.SslHandler, sslHandler);
		}

		if (log.isDebugEnabled()) {
			pipeline.addLast(NettyHandlerNames.LoggingHandler, loggingHandler);
		}

	}

	static final Cancellation EMPTY = () -> {};
}
