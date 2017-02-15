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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;

import io.netty.channel.Channel;
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
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

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

	volatile boolean cancelled;

	Future<CHANNEL> f;

	PooledClientContextHandler(ChannelOperations.OnNew<CHANNEL> channelOpFactory,
			ClientOptions options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler,
			boolean secure,
			SocketAddress providedAddress,
			ChannelPool pool) {
		super(channelOpFactory, options, sink, loggingHandler, providedAddress);
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
			return;
		}
		if (log.isDebugEnabled()) {
			log.debug("Acquiring existing channel from pool: {}", pool.toString());
		}
		this.f = (Future<CHANNEL>) future;
		f.addListener(this);
	}

	@Override
	@SuppressWarnings("unchecked")
	protected void terminateChannel(Channel channel) {
		release((CHANNEL)channel);
	}

	@Override
	public void operationComplete(Future<CHANNEL> future) throws Exception {
		sink.setCancellation(this);
		if (future.isCancelled() || cancelled) {
			if(log.isDebugEnabled()) {
				log.debug("Cancelled {}", future.toString());
			}
			return;
		}
		if (!future.isSuccess()) {
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
	protected Publisher<Void> onCloseOrRelease(Channel channel) {
		return onReleaseEmitter;
	}

	@SuppressWarnings("unchecked")
	final void connectOrAcquire(CHANNEL c) {
		ChannelOperationsHandler handler = (ChannelOperationsHandler) c.pipeline()
		                                                               .get(NettyPipeline.ReactiveBridge);
		if (handler == null) {
			if (log.isDebugEnabled()) {
				log.debug("Connected new channel: {}", c.toString());
			}
			accept(c);
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
			if (c.isActive()) {
				if (log.isDebugEnabled()) {
					log.debug("Acquired existing channel: {}", c.toString());
				}
				handler.parentContext = this;
				createOperations(c, null);
			}
			else {
				fireContextError(AbortedException.instance());
			}
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void dispose() {
		if (f == null) {
			return;
		}
		if (!f.isDone()) {
			if (log.isDebugEnabled()) {
				log.debug("Cancel pending pooled channel acquisition: {}", f.toString());
			}
			f.addListener(ff -> {
				if(ff.isSuccess()) {
					release((CHANNEL) ff.get());
				}
			});
			return;
		}

		try {
			CHANNEL c = f.get();

			if (!c.eventLoop()
			      .inEventLoop()) {
				c.eventLoop().execute(() -> {
					ChannelOperations<?, ?> ops = ChannelOperations.get(c);
					//defer to operation dispose if present
					if (ops != null) {
						ops.dispose();
						return;
					}

					release(c);

				});

			}
			else {
				ChannelOperations<?, ?> ops = ChannelOperations.get(c);
				//defer to operation dispose if present
				if (ops != null) {
					ops.dispose();
					return;
				}

				release(c);
			}

		}
		catch (Exception e) {
			log.error("Failed releasing channel", e);
			onReleaseEmitter.onError(e);
		}
	}

	final void release(CHANNEL c) {
		if (cancelled){
			return;
		}
		cancelled = true;
		if (log.isDebugEnabled()) {
			log.debug("Releasing channel: {}", c.toString());
		}

		pool.release(c).addListener(f -> {
			if (!c.isActive()) {
				return;
			}
			Boolean attr = c.attr(CLOSE_CHANNEL).get();
			if(attr != null && attr && c.isActive()){
				c.close();
			}
			else if(f.isSuccess()){
				onReleaseEmitter.onComplete();
			}
			else{
				onReleaseEmitter.onError(f.cause());
			}
		});

	}

	@Override
	protected void doDropped(Channel channel) {
		dispose();
		fireContextError(AbortedException.INSTANCE);
	}

	@Override
	public void accept(Channel ch) {
		//do not add in channelCreated pool
	}

	@Override
	protected Tuple2<String, Integer> getSNI() {
		if (providedAddress instanceof InetSocketAddress) {
			InetSocketAddress ipa = (InetSocketAddress) providedAddress;
			return Tuples.of(ipa.getHostName(), ipa.getPort());
		}
		return null;
	}
}
