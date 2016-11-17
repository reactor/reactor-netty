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
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.ssl.SslHandler;
import reactor.core.Cancellation;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.options.ClientOptions;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @param <CHANNEL> the channel type
 *
 * @author Stephane Maldini
 */
final class ClientContextHandler<CHANNEL extends Channel>
		extends CloseableContextHandler<CHANNEL> {

	static final Logger log = Loggers.getLogger(ClientContextHandler.class);

	final ClientOptions clientOptions;
	final boolean       secure;

	ChannelFuture f;

	ClientContextHandler(BiFunction<? super CHANNEL, ? super Cancellation, ? extends ChannelOperations<?, ?>> channelOpSelector,
			ClientOptions options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler,
			boolean secure) {
		super(channelOpSelector, options, sink, loggingHandler);
		this.clientOptions = options;
		this.secure = secure;
	}

	@Override
	protected void doDropped(Channel channel) {
		channel.close();
		sink.success();
	}

	@Override
	protected void doPipeline(ChannelPipeline pipeline) {
		addSslAndLogHandlers(clientOptions, sink, loggingHandler, secure, pipeline);
		addProxyHandler(clientOptions, pipeline);
	}

	static void addSslAndLogHandlers(ClientOptions options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler,
			boolean secure,
			ChannelPipeline pipeline) {
		SslHandler sslHandler = secure ? options.getSslHandler(pipeline.channel()
		                                                               .alloc()) : null;
		if (sslHandler != null) {
			if (log.isDebugEnabled()) {
				log.debug("SSL enabled using engine {}",
						sslHandler.engine()
						          .getClass()
						          .getSimpleName());
			}
			if (log.isTraceEnabled()) {
				pipeline.addFirst(NettyHandlerNames.SslLoggingHandler, loggingHandler);
				pipeline.addAfter(NettyHandlerNames.SslLoggingHandler,
						NettyHandlerNames.SslHandler,
						sslHandler);
			}
			else {
				pipeline.addFirst(NettyHandlerNames.SslHandler, sslHandler);
			}
			if (log.isDebugEnabled()) {
				pipeline.addAfter(NettyHandlerNames.SslHandler,
						NettyHandlerNames.LoggingHandler,
						loggingHandler);
				pipeline.addAfter(NettyHandlerNames.LoggingHandler,
						NettyHandlerNames.SslReader,
						new SslReadHandler(sink));
			}
			else {
				pipeline.addAfter(NettyHandlerNames.SslHandler,
						NettyHandlerNames.SslReader,
						new SslReadHandler(sink));
			}
		}
		else if (log.isDebugEnabled()) {
			pipeline.addFirst(NettyHandlerNames.LoggingHandler, loggingHandler);
		}
	}

	static void addProxyHandler(ClientOptions clientOptions, ChannelPipeline pipeline) {
		ProxyHandler proxy = clientOptions.getProxyHandler();
		if (proxy != null) {
			pipeline.addFirst(NettyHandlerNames.ProxyHandler, proxy);
		}
	}
}
