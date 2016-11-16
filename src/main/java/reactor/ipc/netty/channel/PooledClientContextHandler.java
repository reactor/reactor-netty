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

import java.io.IOException;
import java.util.Objects;
import java.util.function.BiConsumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import reactor.core.Cancellation;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.options.ClientOptions;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @param <CHANNEL> the channel type
 *
 * @author Stephane Maldini
 */
final class PooledClientContextHandler<CHANNEL extends Channel>
		extends ContextHandler<CHANNEL>
		implements GenericFutureListener<Future<Channel>> {

	static final Logger log = Loggers.getLogger(PooledClientContextHandler.class);

	final ClientOptions clientOptions;
	final boolean       secure;
	final ChannelPool   pool;

	Future<CHANNEL> f;

	PooledClientContextHandler(BiConsumer<? super CHANNEL, ? super Cancellation> doWithPipeline,
			ClientOptions options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler,
			boolean secure,
			ChannelPool pool) {
		super(doWithPipeline, options, sink, loggingHandler);
		this.clientOptions = options;
		this.secure = secure;
		this.pool = pool;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void setFuture(Future<?> future) {
		Objects.requireNonNull(future, "future");
		if (this.f != null) {
			future.cancel(true);
		}
		this.f = (Future<CHANNEL>) future;
		f.addListener(this);
		sink.setCancellation(this);
	}

	@Override
	public void operationComplete(Future<Channel> future) throws Exception {
		Channel c = future.get();
		if (!f.isSuccess()) {
			if (f.cause() != null) {
				sink.error(f.cause());
			}
			else {
				sink.error(new IOException("error while connecting to " + c.toString()));
			}
		}
	}

	@Override
	public void dispose() {
		if (!f.isDone()) {
			f.cancel(true);
			return;
		}

		try {
			Channel c = f.get();
			pool.release(c)
			    .sync();
		}
		catch (Exception e) {
			log.error("failed releasing channel");
		}
	}

	@Override
	protected void doDropped(Channel channel) {
		channel.close();
		sink.success();
	}

	@Override
	protected void doPipeline(ChannelPipeline pipeline) {
		ClientContextHandler.addSslAndLogHandlers(clientOptions,
				sink,
				loggingHandler,
				secure,
				pipeline);
		ClientContextHandler.addProxyHandler(clientOptions, pipeline);
	}
}
