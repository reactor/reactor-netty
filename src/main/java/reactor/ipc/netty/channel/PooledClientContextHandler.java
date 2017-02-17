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
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.SucceededFuture;
import org.reactivestreams.Publisher;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyContext;
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

	volatile Future<CHANNEL> future;

	static final AtomicReferenceFieldUpdater<PooledClientContextHandler, Future> FUTURE =
			AtomicReferenceFieldUpdater.newUpdater(PooledClientContextHandler.class,
					Future.class,
					"future");

	static final Future DISPOSED = new SucceededFuture<>(null, null);

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

		Future<CHANNEL> f;
		for (; ; ) {
			f = this.future;

			if (f == DISPOSED) {
				if (log.isDebugEnabled()) {
					log.debug("Cancelled existing channel from pool: {}", pool.toString());
				}
				sink.success();
				return;
			}

			if (FUTURE.compareAndSet(this, f, future)) {
				break;
			}
		}
		if (log.isDebugEnabled()) {
			log.debug("Acquiring existing channel from pool: {} {}", future, pool
					.toString());
		}
		sink.setCancellation(this);
		((Future<CHANNEL>) future).addListener(this);
	}

	@Override
	@SuppressWarnings("unchecked")
	protected void terminateChannel(Channel channel) {
		release((CHANNEL) channel);
	}

	@Override
	public void operationComplete(Future<CHANNEL> future) throws Exception {
		if (future.isCancelled()) {
			if (log.isDebugEnabled()) {
				log.debug("Cancelled {}", future.toString());
			}
			return;
		}

		if (DISPOSED == this.future) {
			if (log.isDebugEnabled()) {
				log.debug("Dropping acquisition {} because of {}",
						future, "asynchronous user cancellation");
			}
			if (future.isSuccess()) {
				disposeOperationThenRelease(future.get());
			}
			sink.success();
			return;
		}

		if (!future.isSuccess()) {
			if (future.cause() != null) {
				fireContextError(future.cause());
			}
			else {
				fireContextError(new AbortedException("error while acquiring connection"));
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
		if (DISPOSED == this.future) {
			if (log.isDebugEnabled()) {
				log.debug("Dropping acquisition {} because of {}",
						"asynchronous user cancellation");
			}
			disposeOperationThenRelease(c);
			sink.success();
			return;
		}

		ChannelOperationsHandler op = c.pipeline()
		                               .get(ChannelOperationsHandler.class);

		if (op == null) {
			if (log.isDebugEnabled()) {
				log.debug("Created new pooled channel: " + c.toString());
			}
			c.closeFuture().addListener(ff -> release(c));
			return;
		}
		if (!c.isActive()) {
			log.debug("Immediately aborted pooled channel, re-acquiring new " + "channel: {}", c.toString());
			release(c);
			setFuture(pool.acquire());
			return;
		}
		if (log.isDebugEnabled()) {
			log.debug("Acquired active channel: " + c.toString());
		}
		createOperations(c, null);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void dispose() {
		Future<CHANNEL> f = FUTURE.getAndSet(this, DISPOSED);
		if (f == null || f == DISPOSED) {
			return;
		}
		if (!f.isDone()) {
			return;
		}

		try {
			CHANNEL c = f.get();

			if (!c.eventLoop()
			      .inEventLoop()) {
				c.eventLoop()
				 .execute(() -> disposeOperationThenRelease(c));

			}
			else {
				disposeOperationThenRelease(c);
			}

		}
		catch (Exception e) {
			log.error("Failed releasing channel", e);
			onReleaseEmitter.onError(e);
		}
	}

	final void disposeOperationThenRelease(CHANNEL c){
		ChannelOperations<?, ?> ops = ChannelOperations.get(c);
		//defer to operation dispose if present
		if (ops != null) {
			ops.dispose();
			return;
		}

		release(c);
	}

	final void release(CHANNEL c) {
		if (log.isDebugEnabled()) {
			log.debug("Releasing channel: {}", c.toString());
		}

		pool.release(c)
		    .addListener(f -> {
			    if (!c.isActive()) {
				    return;
			    }
			    Boolean attr = c.attr(CLOSE_CHANNEL)
			                    .get();
			    if (attr != null && attr && c.isActive()) {
				    c.close();
			    }
			    else if (f.isSuccess()) {
				    onReleaseEmitter.onComplete();
			    }
			    else {
				    onReleaseEmitter.onError(f.cause());
			    }
		    });

	}

	@Override
	protected void doDropped(Channel channel) {
		dispose();
		fireContextError(new AbortedException("Channel has been dropped"));
	}

	@Override
	protected void doPipeline(Channel ch) {
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
