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
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Publisher;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;
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
		implements GenericFutureListener<Future<CHANNEL>> {

	static final Logger log = Loggers.getLogger(PooledClientContextHandler.class);

	final ClientOptions         clientOptions;
	final boolean               secure;
	final ChannelPool           pool;
	final DirectProcessor<Void> onReleaseEmitter;

	Future<CHANNEL> f;

	PooledClientContextHandler(BiFunction<? super CHANNEL, ? super ContextHandler<CHANNEL>, ? extends ChannelOperations<?, ?>> channelOpSelector,
			ClientOptions options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler,
			boolean secure,
			ChannelPool pool) {
		super(channelOpSelector, options, sink, loggingHandler);
		this.clientOptions = options;
		this.secure = secure;
		this.pool = pool;
		this.onReleaseEmitter = DirectProcessor.create();
	}

	@Override
	public void fireContextActive(NettyContext context) {
		if (!fired) {
			fired = true;
			sink.success(context);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void setFuture(Future<?> future) {
		Objects.requireNonNull(future, "future");
		if (this.f != null) {
			future.cancel(true);
		}
		if (log.isDebugEnabled()) {
			log.debug("Acquiring existing channel from pool: {}", pool.toString());
		}
		this.f = (Future<CHANNEL>) future;
		f.addListener(this);
		sink.setCancellation(this);
	}

	@Override
	public void operationComplete(Future<CHANNEL> future) throws Exception {
		if (!future.isSuccess()) {
			if (future.isCancelled()) {
				log.debug("Cancelled {}", future.toString());
				return;
			}
			if (future.cause() != null) {
				sink.error(future.cause());
			}
			else {
				sink.error(new IOException("error while connecting to " + future.toString()));
			}
			return;
		}
		CHANNEL c = future.get();

		if (c.eventLoop()
		     .inEventLoop()) {
			connectOrAcquire(c);
		}
		else {
			c.eventLoop()
			 .execute(() -> connectOrAcquire(c));
		}
	}

	@Override
	protected void terminateChannel(Channel channel) {
		dispose();
	}

	@Override
	protected Publisher<Void> onCloseOrRelease(Channel channel) {
		return onReleaseEmitter;
	}

	final void connectOrAcquire(CHANNEL c) {
		if (c.pipeline()
		     .get(NettyPipeline.BridgeSetup) == null) {
			if (log.isDebugEnabled()) {
				log.debug("Connected new channel: {}", c.toString());
			}
			doPipeline(c.pipeline());
			c.pipeline()
			 .addLast(NettyPipeline.BridgeSetup, new BridgeSetupHandler(this));
			if (c.isRegistered()) {
				c.pipeline()
				 .fireChannelRegistered();
			}
			if (c.isActive()) {
				c.pipeline()
				 .fireChannelActive();
				return;
			}
			c.pipeline()
			 .fireChannelInactive();
			if (!c.isRegistered()) {
				c.pipeline()
				 .fireChannelUnregistered();
			}
		}
		else {
			if (log.isDebugEnabled()) {
				log.debug("Acquired existing channel: {}", c.toString());
			}
			ChannelOperations<?, ?> op = channelOpSelector.apply(c, this);

			ChannelOperations<?, ?> previous =
					c.attr(ChannelOperations.OPERATIONS_ATTRIBUTE_KEY)
					 .getAndSet(op);

			if (previous != null) {
				previous.inbound.cancel();
			}
			op.onChannelActive(c.pipeline()
			                    .context(NettyPipeline.BridgeSetup));
		}
	}

	@Override
	public void dispose() {
		if (!f.isDone()) {
			if (log.isDebugEnabled()) {
				log.debug("Releasing pending channel acquisition: {}", f.toString());
			}
			f.cancel(true);
			return;
		}
		try {
			CHANNEL c = f.get();
			if (!c.isActive()) {
				return;
			}

			if (!c.eventLoop()
			      .inEventLoop()) {
				c.eventLoop()
				 .execute(() -> release(c));
			}
			else {
				release(c);
			}

		}
		catch (Exception e) {
			log.error("Failed releasing channel", e);
			onReleaseEmitter.onError(e);
		}
	}

	final void release(CHANNEL c) {
		if (log.isDebugEnabled()) {
			log.debug("Releasing channel: {}", c.toString());
		}

		if(!c.isOpen()) {
			onReleaseEmitter.onComplete();
			return;
		}



		pool.release(c).addListener(f -> {
			if(f.isSuccess()){
				onReleaseEmitter.onComplete();
			}
			else{
				onReleaseEmitter.onError(f.cause());
			}
			if (!c.isOpen()) {
				return;
			}
			Boolean attr = c.attr(CLOSE_CHANNEL).get();
			if(attr != null && attr){
				c.close();
			}
		});

	}

	@Override
	protected void doDropped(Channel channel) {
		dispose();
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
