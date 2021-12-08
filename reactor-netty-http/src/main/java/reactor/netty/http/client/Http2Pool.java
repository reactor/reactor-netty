/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.client;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import io.netty.handler.codec.http2.Http2FrameCodec;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Scannable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.internal.shaded.reactor.pool.InstrumentedPool;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquirePendingLimitException;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquireTimeoutException;
import reactor.netty.internal.shaded.reactor.pool.PoolConfig;
import reactor.netty.internal.shaded.reactor.pool.PoolShutdownException;
import reactor.netty.internal.shaded.reactor.pool.PooledRef;
import reactor.netty.internal.shaded.reactor.pool.PooledRefMetadata;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import static reactor.netty.ReactorNetty.format;

/**
 * <p>This class is intended to be used only as {@code HTTP/2} connection pool. It doesn't have generic purpose.
 * <p>
 * The connection is removed from the pool when:
 * <ul>
 *     <li>The connection is closed.</li>
 *     <li>The connection has reached its life time and there are no active streams.</li>
 *     <li>The connection has no active streams.</li>
 *     <li>When the client is in one of the two modes: 1) H2 and HTTP/1.1 or 2) H2C and HTTP/1.1,
 *     and the negotiated protocol is HTTP/1.1.</li>
 * </ul>
 * <p>
 * The connection is filtered out when:
 * <ul>
 *     <li>The connection has reached its life time and there are active streams. In this case, the connection stays
 *     in the pool, but it is not used. Once there are no active streams, the connection is removed from the pool.</li>
 *     <li>The connection has reached its max active streams configuration. In this case, the connection stays
 *     in the pool, but it is not used. Once the number of the active streams is below max active streams configuration,
 *     the connection can be used again.</li>
 * </ul>
 * <p>
 * This pool always invalidate the {@link PooledRef}, there is no release functionality.
 * <ul>
 *     <li>{@link PoolMetrics#acquiredSize()} and {@link PoolMetrics#allocatedSize()} always return the number of
 *     the active streams from all connections currently in the pool.</li>
 *     <li>{@link PoolMetrics#idleSize()} always returns {@code 0}.</li>
 * </ul>
 * <p>
 * Configurations that are not applicable
 * <ul>
 *     <li>{@link PoolConfig#destroyHandler()} - the destroy handler cannot be used as the destruction is more complex.</li>
 *     <li>{@link PoolConfig#evictInBackgroundInterval()} and {@link PoolConfig#evictInBackgroundScheduler()} -
 *     there are no idle resources in the pool. Once the connection does not have active streams, it
 *     is returned to the parent pool.</li>
 *     <li>{@link PoolConfig#evictionPredicate()} - the eviction predicate cannot be used as more complex
 *     checks have to be done. Also the pool uses filtering for the connections (a connection might not be able
 *     to be used but is required to stay in the pool).</li>
 *     <li>{@link PoolConfig#metricsRecorder()} - no pool instrumentation.</li>
 *     <li>{@link PoolConfig#releaseHandler()} - release functionality works as invalidate.</li>
 *     <li>{@link PoolConfig#reuseIdleResourcesInLruOrder()} - FIFO is used when checking the connections.</li>
 *     <li>FIFO is used when obtaining the pending borrowers</li>
 *     <li>Warm up functionality is not supported</li>
 * </ul>
 * <p>This class is based on
 * https://github.com/reactor/reactor-pool/blob/v0.2.7/src/main/java/reactor/pool/SimpleDequePool.java
 *
 * @author Violeta Georgieva
 */
final class Http2Pool implements InstrumentedPool<Connection>, InstrumentedPool.PoolMetrics {

	static final Logger log = Loggers.getLogger(Http2Pool.class);

	volatile int acquired;
	static final AtomicIntegerFieldUpdater<Http2Pool> ACQUIRED =
			AtomicIntegerFieldUpdater.newUpdater(Http2Pool.class, "acquired");

	volatile ConcurrentLinkedQueue<Slot> connections;
	@SuppressWarnings("rawtypes")
	static final AtomicReferenceFieldUpdater<Http2Pool, ConcurrentLinkedQueue> CONNECTIONS =
			AtomicReferenceFieldUpdater.newUpdater(Http2Pool.class, ConcurrentLinkedQueue.class, "connections");

	volatile ConcurrentLinkedDeque<Borrower> pending;
	@SuppressWarnings("rawtypes")
	static final AtomicReferenceFieldUpdater<Http2Pool, ConcurrentLinkedDeque> PENDING =
			AtomicReferenceFieldUpdater.newUpdater(Http2Pool.class, ConcurrentLinkedDeque.class, "pending");

	@SuppressWarnings("rawtypes")
	static final ConcurrentLinkedDeque TERMINATED = new ConcurrentLinkedDeque();

	volatile int wip;
	static final AtomicIntegerFieldUpdater<Http2Pool> WIP =
			AtomicIntegerFieldUpdater.newUpdater(Http2Pool.class, "wip");

	final Clock clock;
	final long maxLifeTime;
	final PoolConfig<Connection> poolConfig;

	long lastInteractionTimestamp;

	Http2Pool(PoolConfig<Connection> poolConfig, long maxLifeTime) {
		this.clock = poolConfig.clock();
		this.connections = new ConcurrentLinkedQueue<>();
		this.lastInteractionTimestamp = clock.millis();
		this.maxLifeTime = maxLifeTime;
		this.pending = new ConcurrentLinkedDeque<>();
		this.poolConfig = poolConfig;

		recordInteractionTimestamp();
	}

	@Override
	public Mono<PooledRef<Connection>> acquire() {
		return new BorrowerMono(this, Duration.ZERO);
	}

	@Override
	public Mono<PooledRef<Connection>> acquire(Duration timeout) {
		return new BorrowerMono(this, timeout);
	}

	@Override
	public int acquiredSize() {
		return acquired;
	}

	@Override
	public int allocatedSize() {
		return acquired;
	}

	@Override
	public PoolConfig<Connection> config() {
		return poolConfig;
	}

	@Override
	public Mono<Void> disposeLater() {
		return Mono.defer(() -> {
			recordInteractionTimestamp();

			@SuppressWarnings("unchecked")
			ConcurrentLinkedDeque<Borrower> q = PENDING.getAndSet(this, TERMINATED);
			if (q != TERMINATED) {
				Borrower p;
				while ((p = q.pollFirst()) != null) {
					p.fail(new PoolShutdownException());
				}

				// the last stream on that connection will release the connection to the parent pool
				// the structure should not contain connections with 0 streams as the last stream on that connection
				// always removes the connection from this pool
				CONNECTIONS.getAndSet(this, null);
			}
			return Mono.empty();
		});
	}

	@Override
	public int getMaxAllocatedSize() {
		return Integer.MAX_VALUE;
	}

	@Override
	public int getMaxPendingAcquireSize() {
		return poolConfig.maxPending() < 0 ? Integer.MAX_VALUE : poolConfig.maxPending();
	}

	@Override
	public int idleSize() {
		return 0;
	}

	@Override
	public boolean isDisposed() {
		return PENDING.get(this) == TERMINATED || CONNECTIONS.get(this) == null;
	}

	@Override
	public boolean isInactiveForMoreThan(Duration duration) {
		return pendingAcquireSize() == 0 && allocatedSize() == 0
				&& secondsSinceLastInteraction() >= duration.getSeconds();
	}

	@Override
	public PoolMetrics metrics() {
		return this;
	}

	@Override
	public int pendingAcquireSize() {
		return PENDING.get(this).size();
	}

	@Override
	public long secondsSinceLastInteraction() {
		long sinceMs = clock.millis() - lastInteractionTimestamp;
		return sinceMs / 1000;
	}

	@Override
	public Mono<Integer> warmup() {
		return Mono.just(0);
	}

	void cancelAcquire(Borrower borrower) {
		if (!isDisposed()) {
			ConcurrentLinkedDeque<Borrower> q = pending;
			q.remove(borrower);
		}
	}

	Mono<Void> destroyPoolable(Http2PooledRef ref) {
		Mono<Void> mono = Mono.empty();
		try {
			if (ref.slot.decrementConcurrencyAndGet() == 0) {
				ref.slot.invalidate();
				Connection connection = ref.poolable();
				Http2FrameCodec frameCodec = connection.channel().pipeline().get(Http2FrameCodec.class);
				if (frameCodec != null) {
					releaseConnection(connection);
				}
			}
		}
		catch (Throwable destroyFunctionError) {
			mono = Mono.error(destroyFunctionError);
		}
		return mono;
	}

	void doAcquire(Borrower borrower) {
		if (isDisposed()) {
			borrower.fail(new PoolShutdownException());
			return;
		}

		pendingOffer(borrower);
		drain();
	}

	void drain() {
		if (WIP.getAndIncrement(this) == 0) {
			drainLoop();
		}
	}

	void drainLoop() {
		recordInteractionTimestamp();
		int maxPending = poolConfig.maxPending();

		for (;;) {
			@SuppressWarnings("unchecked")
			ConcurrentLinkedQueue<Slot> resources = CONNECTIONS.get(this);
			@SuppressWarnings("unchecked")
			ConcurrentLinkedDeque<Borrower> borrowers = PENDING.get(this);
			if (resources == null || borrowers == TERMINATED) {
				return;
			}

			int borrowersCount = borrowers.size();

			if (borrowersCount != 0) {
				// find a connection that can be used for opening a new stream
				Slot slot = findConnection(resources);
				if (slot != null) {
					Borrower borrower = borrowers.pollFirst();
					if (borrower == null) {
						resources.offer(slot);
						continue;
					}
					if (isDisposed()) {
						borrower.fail(new PoolShutdownException());
						return;
					}
					if (slot.incrementConcurrencyAndGet() > 1) {
						borrower.stopPendingCountdown();
						if (log.isDebugEnabled()) {
							log.debug(format(slot.connection.channel(), "Channel activated"));
						}
						ACQUIRED.incrementAndGet(this);
						// we are ready here, the connection can be used for opening another stream
						slot.deactivate();
						poolConfig.acquisitionScheduler().schedule(() -> borrower.deliver(new Http2PooledRef(slot)));
					}
					else {
						borrowers.offerFirst(borrower);
						continue;
					}
				}
				else {
					int permits = poolConfig.allocationStrategy().getPermits(1);
					if (permits <= 0) {
						if (maxPending >= 0) {
							borrowersCount = borrowers.size();
							int toCull = borrowersCount - maxPending;
							for (int i = 0; i < toCull; i++) {
								Borrower extraneous = borrowers.pollFirst();
								if (extraneous != null) {
									pendingAcquireLimitReached(extraneous, maxPending);
								}
							}
						}
					}
					else {
						Borrower borrower = borrowers.pollFirst();
						if (borrower == null) {
							continue;
						}
						if (isDisposed()) {
							borrower.fail(new PoolShutdownException());
							return;
						}
						borrower.stopPendingCountdown();
						Mono<Connection> allocator = poolConfig.allocator();
						Mono<Connection> primary =
								allocator.doOnEach(sig -> {
								             if (sig.isOnNext()) {
								                 Connection newInstance = sig.get();
								                 assert newInstance != null;
								                 Slot newSlot = new Slot(this, newInstance);
								                 if (log.isDebugEnabled()) {
								                     log.debug(format(newInstance.channel(), "Channel activated"));
								                 }
								                 ACQUIRED.incrementAndGet(this);
								                 newSlot.incrementConcurrencyAndGet();
								                 newSlot.deactivate();
								                 borrower.deliver(new Http2PooledRef(newSlot));
								             }
								             else if (sig.isOnError()) {
								                 Throwable error = sig.getThrowable();
								                 assert error != null;
								                 poolConfig.allocationStrategy().returnPermits(1);
								                 borrower.fail(error);
								             }
								         })
								         .contextWrite(borrower.currentContext());

						primary.subscribe(alreadyPropagated -> {}, alreadyPropagatedOrLogged -> drain(), this::drain);
					}
				}
			}

			if (WIP.decrementAndGet(this) == 0) {
				recordInteractionTimestamp();
				break;
			}
		}
	}

	@Nullable
	Slot findConnection(ConcurrentLinkedQueue<Slot> resources) {
		int resourcesCount = resources.size();
		while (resourcesCount > 0) {
			// There are connections in the queue

			resourcesCount--;

			// get the connection
			Slot slot = resources.poll();
			if (slot == null) {
				continue;
			}

			// check the connection is active
			if (!slot.connection.channel().isActive()) {
				if (slot.concurrency() > 0) {
					if (log.isDebugEnabled()) {
						log.debug(format(slot.connection.channel(), "Channel is closed, {} active streams"),
								slot.concurrency());
					}
					resources.offer(slot);
				}
				else {
					if (log.isDebugEnabled()) {
						log.debug(format(slot.connection.channel(), "Channel is closed, remove from pool"));
					}
					resources.remove(slot);
				}
				continue;
			}

			// check that the connection's max lifetime has not been reached
			if (maxLifeTime != -1 && slot.lifeTime() >= maxLifeTime) {
				if (slot.concurrency() > 0) {
					if (log.isDebugEnabled()) {
						log.debug(format(slot.connection.channel(), "Max life time is reached, {} active streams"),
								slot.concurrency());
					}
					resources.offer(slot);
				}
				else {
					if (log.isDebugEnabled()) {
						log.debug(format(slot.connection.channel(), "Max life time is reached, remove from pool"));
					}
					resources.remove(slot);
				}
				continue;
			}

			// check that the connection's max active streams has not been reached
			if (!slot.canOpenStream()) {
				resources.offer(slot);
				if (log.isDebugEnabled()) {
					log.debug(format(slot.connection.channel(), "Max active streams is reached"));
				}
				continue;
			}

			return slot;
		}

		return null;
	}

	void pendingAcquireLimitReached(Borrower borrower, int maxPending) {
		if (maxPending == 0) {
			borrower.fail(new PoolAcquirePendingLimitException(0,
					"No pending allowed and pool has reached allocation limit"));
		}
		else {
			borrower.fail(new PoolAcquirePendingLimitException(maxPending));
		}
	}

	/**
	 * @param borrower a new {@link Borrower} to add to the queue and later either serve or consider pending
	 */
	void pendingOffer(Borrower borrower) {
		int maxPending = poolConfig.maxPending();
		ConcurrentLinkedDeque<Borrower> pendingQueue = pending;
		if (pendingQueue == TERMINATED) {
			return;
		}
		pendingQueue.offerLast(borrower);
		int postOffer = pendingQueue.size();

		if (WIP.getAndIncrement(this) == 0) {
			ConcurrentLinkedQueue<Slot> ir = connections;
			if (maxPending >= 0 && postOffer > maxPending && ir.isEmpty() && poolConfig.allocationStrategy().estimatePermitCount() == 0) {
				Borrower toCull = pendingQueue.pollLast();
				if (toCull != null) {
					pendingAcquireLimitReached(toCull, maxPending);
				}

				if (WIP.decrementAndGet(this) > 0) {
					drainLoop();
				}
				return;
			}

			drainLoop();
		}
	}

	void recordInteractionTimestamp() {
		this.lastInteractionTimestamp = clock.millis();
	}

	static boolean offerSlot(Slot slot) {
		@SuppressWarnings("unchecked")
		ConcurrentLinkedQueue<Slot> q = CONNECTIONS.get(slot.pool);
		return q != null && q.offer(slot);
	}

	static void releaseConnection(Connection connection) {
		ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
		if (ops != null) {
			ops.listener().onStateChange(ops, ConnectionObserver.State.DISCONNECTING);
		}
		else if (connection instanceof ConnectionObserver) {
			((ConnectionObserver) connection).onStateChange(connection, ConnectionObserver.State.DISCONNECTING);
		}
		else {
			connection.dispose();
		}
	}

	static void removeSlot(Slot slot) {
		@SuppressWarnings("unchecked")
		ConcurrentLinkedQueue<Slot> q = CONNECTIONS.get(slot.pool);
		if (q != null) {
			q.remove(slot);
		}
	}

	static final class Borrower extends AtomicBoolean implements Scannable, Subscription, Runnable {

		static final Disposable TIMEOUT_DISPOSED = Disposables.disposed();

		final Duration acquireTimeout;
		final CoreSubscriber<? super Http2PooledRef> actual;
		final Http2Pool pool;

		Disposable timeoutTask;

		Borrower(CoreSubscriber<? super Http2PooledRef> actual, Http2Pool pool, Duration acquireTimeout) {
			this.acquireTimeout = acquireTimeout;
			this.actual = actual;
			this.pool = pool;
			this.timeoutTask = TIMEOUT_DISPOSED;
		}

		@Override
		public void cancel() {
			stopPendingCountdown();
			if (compareAndSet(false, true)) {
				pool.cancelAcquire(this);
			}
		}

		Context currentContext() {
			return actual.currentContext();
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				if (!acquireTimeout.isZero()) {
					timeoutTask = Schedulers.parallel().schedule(this, acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
				}
				pool.doAcquire(this);
			}
		}

		@Override
		public void run() {
			if (compareAndSet(false, true)) {
				pool.cancelAcquire(Http2Pool.Borrower.this);
				actual.onError(new PoolAcquireTimeoutException(acquireTimeout));
			}
		}

		@Override
		@Nullable
		@SuppressWarnings("rawtypes")
		public Object scanUnsafe(Attr key) {
			if (key == Attr.CANCELLED) {
				return get();
			}
			if (key == Attr.REQUESTED_FROM_DOWNSTREAM) {
				return 1;
			}
			if (key == Attr.ACTUAL) {
				return actual;
			}

			return null;
		}

		@Override
		public String toString() {
			return get() ? "Borrower(cancelled)" : "Borrower";
		}

		void deliver(Http2PooledRef poolSlot) {
			stopPendingCountdown();
			if (get()) {
				//CANCELLED or timeout reached
				poolSlot.invalidate().subscribe(aVoid -> {}, e -> Operators.onErrorDropped(e, Context.empty()));
			}
			else {
				actual.onNext(poolSlot);
				actual.onComplete();
			}
		}

		void fail(Throwable error) {
			stopPendingCountdown();
			if (!get()) {
				actual.onError(error);
			}
		}

		void stopPendingCountdown() {
			timeoutTask.dispose();
		}
	}

	static final class BorrowerMono extends Mono<PooledRef<Connection>> {

		final Duration acquireTimeout;
		final Http2Pool parent;

		BorrowerMono(Http2Pool pool, Duration acquireTimeout) {
			this.acquireTimeout = acquireTimeout;
			this.parent = pool;
		}

		@Override
		public void subscribe(CoreSubscriber<? super PooledRef<Connection>> actual) {
			Objects.requireNonNull(actual, "subscribing with null");
			Borrower borrower = new Borrower(actual, parent, acquireTimeout);
			actual.onSubscribe(borrower);
		}
	}

	static final class Http2PooledRef extends AtomicBoolean implements PooledRef<Connection>, PooledRefMetadata {

		final int acquireCount;
		final Slot slot;

		Http2PooledRef(Slot slot) {
			this.acquireCount = 0;
			this.slot = slot;
		}

		@Override
		public int acquireCount() {
			return 1;
		}

		@Override
		public long allocationTimestamp() {
			return 0;
		}

		@Override
		public long idleTime() {
			return 0;
		}

		@Override
		public Mono<Void> invalidate() {
			return Mono.defer(() -> {
				if (compareAndSet(false, true)) {
					ACQUIRED.decrementAndGet(slot.pool);
					return slot.pool.destroyPoolable(this).doFinally(st -> slot.pool.drain());
				}
				else {
					return Mono.empty();
				}
			});
		}

		@Override
		public long lifeTime() {
			return 0;
		}

		@Override
		public PooledRefMetadata metadata() {
			return this;
		}

		@Override
		public Connection poolable() {
			return slot.connection;
		}

		@Override
		public Mono<Void> release() {
			return invalidate();
		}

		@Override
		public long releaseTimestamp() {
			return 0;
		}

		@Override
		public String toString() {
			return "PooledRef{poolable=" + slot.connection + '}';
		}
	}

	static final class Slot {

		volatile int concurrency;
		static final AtomicIntegerFieldUpdater<Slot> CONCURRENCY =
				AtomicIntegerFieldUpdater.newUpdater(Slot.class, "concurrency");

		final Connection connection;
		final long creationTimestamp;
		final Http2Pool pool;

		Slot(Http2Pool pool, Connection connection) {
			this.connection = connection;
			this.creationTimestamp = pool.clock.millis();
			this.pool = pool;
		}

		boolean canOpenStream() {
			Http2FrameCodec frameCodec = connection.channel().pipeline().get(Http2FrameCodec.class);
			if (frameCodec != null) {
				int maxActiveStreams = frameCodec.connection().local().maxActiveStreams();
				int concurrency = this.concurrency;
				return concurrency < maxActiveStreams;
			}
			return false;
		}

		int concurrency() {
			return concurrency;
		}

		void deactivate() {
			if (log.isDebugEnabled()) {
				log.debug(format(connection.channel(), "Channel deactivated"));
			}
			offerSlot(this);
		}

		int decrementConcurrencyAndGet() {
			return CONCURRENCY.decrementAndGet(this);
		}

		int incrementConcurrencyAndGet() {
			return CONCURRENCY.incrementAndGet(this);
		}

		void invalidate() {
			if (log.isDebugEnabled()) {
				log.debug(format(connection.channel(), "Channel removed from pool"));
			}
			pool.poolConfig.allocationStrategy().returnPermits(1);
			removeSlot(this);
		}

		long lifeTime() {
			return pool.clock.millis() - creationTimestamp;
		}
	}
}