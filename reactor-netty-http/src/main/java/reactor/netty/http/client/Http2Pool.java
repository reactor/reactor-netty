/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslHandler;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Scannable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.netty.Connection;
import reactor.netty.FutureMono;
import reactor.netty.NettyPipeline;
import reactor.netty.internal.shaded.reactor.pool.InstrumentedPool;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquirePendingLimitException;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquireTimeoutException;
import reactor.netty.internal.shaded.reactor.pool.PoolConfig;
import reactor.netty.internal.shaded.reactor.pool.PoolShutdownException;
import reactor.netty.internal.shaded.reactor.pool.PooledRef;
import reactor.netty.internal.shaded.reactor.pool.PooledRefMetadata;
import reactor.netty.resources.ConnectionProvider;
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
 *     <li>GO_AWAY is received and there are no active streams.</li>
 *     <li>The eviction predicate evaluates to true and there are no active streams.</li>
 *     <li>When the client is in one of the two modes: 1) H2 and HTTP/1.1 or 2) H2C and HTTP/1.1,
 *     and the negotiated protocol is HTTP/1.1.</li>
 * </ul>
 * <p>
 * The connection is filtered out when:
 * <ul>
 *     <li>The connection's eviction predicate evaluates to true or GO_AWAY is received, and there are active streams. In this case, the
 *     connection stays in the pool, but it is not used. Once there are no active streams, the connection is removed
 *     from the pool.</li>
 *     <li>The connection has reached its max active streams configuration. In this case, the connection stays
 *     in the pool, but it is not used. Once the number of the active streams is below max active streams configuration,
 *     the connection can be used again.</li>
 * </ul>
 * <p>
 * This pool always invalidate the {@link PooledRef}, there is no release functionality.
 * <ul>
 *     <li>{@link PoolMetrics#acquiredSize()}, {@link PoolMetrics#allocatedSize()} and {@link PoolMetrics#idleSize()}
 *     always return the number of the cached connections.</li>
 *     <li>{@link Http2Pool#activeStreams()} always return the active streams from all connections currently in the pool.</li>
 * </ul>
 * <p>
 * If minimum connections is specified, the cached connections with active streams will be kept at that minimum
 * (can be the best effort). However, if the cached connections have reached max concurrent streams,
 * then new connections will be allocated up to the maximum connections limit.
 * <p>
 * Configurations that are not applicable
 * <ul>
 *     <li>{@link PoolConfig#destroyHandler()} - the destroy handler cannot be used as the destruction is more complex.</li>
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
class Http2Pool implements InstrumentedPool<Connection>, InstrumentedPool.PoolMetrics {

	static final Logger log = Loggers.getLogger(Http2Pool.class);

	volatile int acquired;
	static final AtomicIntegerFieldUpdater<Http2Pool> ACQUIRED =
			AtomicIntegerFieldUpdater.newUpdater(Http2Pool.class, "acquired");

	volatile ConcurrentLinkedQueue<Slot> connections;
	@SuppressWarnings("rawtypes")
	static final AtomicReferenceFieldUpdater<Http2Pool, ConcurrentLinkedQueue> CONNECTIONS =
			AtomicReferenceFieldUpdater.newUpdater(Http2Pool.class, ConcurrentLinkedQueue.class, "connections");

	volatile int idleSize;
	private static final AtomicIntegerFieldUpdater<Http2Pool> IDLE_SIZE =
			AtomicIntegerFieldUpdater.newUpdater(Http2Pool.class, "idleSize");

	/**
	 * Pending borrowers queue. Never invoke directly the poll/add/remove methods and instead of that,
	 * use addPending/pollPending/removePending methods which take care of maintaining the pending queue size.
	 * @see #removePending(ConcurrentLinkedDeque, Borrower)
	 * @see #addPending(ConcurrentLinkedDeque, Borrower, boolean)
	 * @see #pollPending(ConcurrentLinkedDeque, boolean)
	 * @see #PENDING_SIZE
	 * @see #pendingSize
	 */
	volatile ConcurrentLinkedDeque<Borrower> pending;
	@SuppressWarnings("rawtypes")
	static final AtomicReferenceFieldUpdater<Http2Pool, ConcurrentLinkedDeque> PENDING =
			AtomicReferenceFieldUpdater.newUpdater(Http2Pool.class, ConcurrentLinkedDeque.class, "pending");

	volatile int pendingSize;
	private static final AtomicIntegerFieldUpdater<Http2Pool> PENDING_SIZE =
			AtomicIntegerFieldUpdater.newUpdater(Http2Pool.class, "pendingSize");

	@SuppressWarnings("rawtypes")
	static final ConcurrentLinkedDeque TERMINATED = new ConcurrentLinkedDeque();

	volatile long totalMaxConcurrentStreams;
	static final AtomicLongFieldUpdater<Http2Pool> TOTAL_MAX_CONCURRENT_STREAMS =
			AtomicLongFieldUpdater.newUpdater(Http2Pool.class, "totalMaxConcurrentStreams");

	volatile int wip;
	static final AtomicIntegerFieldUpdater<Http2Pool> WIP =
			AtomicIntegerFieldUpdater.newUpdater(Http2Pool.class, "wip");

	final Clock clock;
	final Long maxConcurrentStreams;
	final int minConnections;
	final PoolConfig<Connection> poolConfig;

	long lastInteractionTimestamp;

	Disposable evictionTask;

	Http2Pool(PoolConfig<Connection> poolConfig, @Nullable ConnectionProvider.AllocationStrategy<?> allocationStrategy) {
		this.clock = poolConfig.clock();
		this.connections = new ConcurrentLinkedQueue<>();
		this.lastInteractionTimestamp = clock.millis();
		this.maxConcurrentStreams = allocationStrategy instanceof Http2AllocationStrategy ?
				((Http2AllocationStrategy) allocationStrategy).maxConcurrentStreams() : -1;
		this.minConnections = allocationStrategy == null ? 0 : allocationStrategy.permitMinimum();
		this.pending = new ConcurrentLinkedDeque<>();
		this.poolConfig = poolConfig;

		recordInteractionTimestamp();
		scheduleEviction();
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
		return allocatedSize() - idleSize();
	}

	@Override
	public int allocatedSize() {
		return poolConfig.allocationStrategy().permitGranted();
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
				evictionTask.dispose();

				Borrower p;
				while ((p = pollPending(q, true)) != null) {
					p.fail(new PoolShutdownException());
				}

				@SuppressWarnings("unchecked")
				ConcurrentLinkedQueue<Slot> slots = CONNECTIONS.getAndSet(this, null);
				if (slots != null) {
					Mono<Void> closeMonos = Mono.empty();
					while (!slots.isEmpty()) {
						Slot slot = pollSlot(slots);
						if (slot != null) {
							slot.invalidate();
							closeMonos = closeMonos.and(DEFAULT_DESTROY_HANDLER.apply(slot.connection));
						}
					}
					return closeMonos;
				}
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
		return idleSize;
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
		return pendingSize;
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

	int activeStreams() {
		return acquired;
	}

	void cancelAcquire(Borrower borrower) {
		if (!isDisposed()) {
			ConcurrentLinkedDeque<Borrower> q = pending;
			removePending(q, borrower);
		}
	}

	Slot createSlot(Connection connection) {
		return new Slot(this, connection);
	}

	Mono<Void> destroyPoolable(Http2PooledRef ref) {
		assert ref.slot.connection.channel().eventLoop().inEventLoop();
		Mono<Void> mono = Mono.empty();
		try {
			// By default, check the connection for removal on acquire and invalidate (only if there are no active streams)
			if (ref.slot.decrementConcurrencyAndGet() == 0) {
				destroyPoolableInternal(ref);
			}
		}
		catch (Throwable destroyFunctionError) {
			mono = Mono.error(destroyFunctionError);
		}
		return mono;
	}

	void destroyPoolableInternal(Http2PooledRef ref) {
		// not HTTP/2 request
		if (ref.slot.http2FrameCodecCtx() == null) {
			ref.slot.invalidate();
			removeSlot(ref.slot);
		}
		// If there is eviction in background, the background process will remove this connection
		else if (poolConfig.evictInBackgroundInterval().isZero()) {
			// not active
			if (!ref.poolable().channel().isActive()) {
				ref.slot.invalidate();
				removeSlot(ref.slot);
			}
			// received GO_AWAY
			if (ref.slot.goAwayReceived()) {
				ref.slot.invalidate();
				removeSlot(ref.slot);
			}
			// eviction predicate evaluates to true
			else if (testEvictionPredicate(ref.slot)) {
				closeChannel(ref.slot.connection.channel());
				ref.slot.invalidate();
				removeSlot(ref.slot);
			}
		}
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

			int borrowersCount = pendingSize;

			if (borrowersCount != 0) {
				// find a connection that can be used for opening a new stream
				// when cached connections are below minimum connections, then allocate a new connection
				boolean belowMinConnections = minConnections > 0 &&
						poolConfig.allocationStrategy().permitGranted() < minConnections;
				Slot slot = belowMinConnections ? null : findConnection(resources);
				if (slot != null) {
					Borrower borrower = pollPending(borrowers, true);
					if (borrower == null) {
						offerSlot(resources, slot);
						continue;
					}
					if (isDisposed()) {
						borrower.fail(new PoolShutdownException());
						return;
					}
					borrower.stopPendingCountdown(true);
					if (log.isDebugEnabled()) {
						log.debug(format(slot.connection.channel(), "Channel activated"));
					}
					ACQUIRED.incrementAndGet(this);
					slot.connection.channel().eventLoop().execute(() -> {
						borrower.deliver(new Http2PooledRef(slot));
						drain();
					});
				}
				else {
					int resourcesCount = idleSize;
					if (minConnections > 0 &&
							poolConfig.allocationStrategy().permitGranted() >= minConnections &&
							resourcesCount == 0) {
						// connections allocations were triggered
					}
					else {
						int permits = poolConfig.allocationStrategy().getPermits(1);
						if (permits <= 0) {
							if (maxPending >= 0) {
								borrowersCount = pendingSize;
								int toCull = borrowersCount - maxPending;
								for (int i = 0; i < toCull; i++) {
									Borrower extraneous = pollPending(borrowers, true);
									if (extraneous != null) {
										pendingAcquireLimitReached(extraneous, maxPending);
									}
								}
							}
						}
						else {
							if (permits > 1) {
								// warmup is not supported
								poolConfig.allocationStrategy().returnPermits(permits - 1);
							}
							Borrower borrower = pollPending(borrowers, true);
							if (borrower == null) {
								continue;
							}
							if (isDisposed()) {
								borrower.fail(new PoolShutdownException());
								return;
							}
							borrower.stopPendingCountdown(true);
							Mono<Connection> allocator = poolConfig.allocator();
							Mono<Connection> primary =
									allocator.doOnEach(sig -> {
									             if (sig.isOnNext()) {
									                 Connection newInstance = sig.get();
									                 assert newInstance != null;
									                 Slot newSlot = createSlot(newInstance);
									                 if (log.isDebugEnabled()) {
									                     log.debug(format(newInstance.channel(), "Channel activated"));
									                 }
									                 ACQUIRED.incrementAndGet(this);
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
			}

			if (WIP.decrementAndGet(this) == 0) {
				recordInteractionTimestamp();
				break;
			}
		}
	}

	void evictInBackground() {
		@SuppressWarnings("unchecked")
		ConcurrentLinkedQueue<Slot> resources = CONNECTIONS.get(this);
		if (resources == null) {
			//no need to schedule the task again, pool has been disposed
			return;
		}

		if (WIP.getAndIncrement(this) == 0) {
			if (pendingSize == 0) {
				Iterator<Slot> slots = resources.iterator();
				while (slots.hasNext()) {
					Slot slot = slots.next();
					if (slot.concurrency() == 0) {
						if (!slot.connection.channel().isActive()) {
							if (log.isDebugEnabled()) {
								log.debug(format(slot.connection.channel(), "Channel is closed, remove from pool"));
							}
							recordInteractionTimestamp();
							slots.remove();
							IDLE_SIZE.decrementAndGet(this);
							slot.invalidate();
							continue;
						}

						if (slot.goAwayReceived()) {
							if (log.isDebugEnabled()) {
								log.debug(format(slot.connection.channel(), "Channel received GO_AWAY, remove from pool"));
							}
							recordInteractionTimestamp();
							slots.remove();
							IDLE_SIZE.decrementAndGet(this);
							slot.invalidate();
							continue;
						}

						if (testEvictionPredicate(slot)) {
							if (log.isDebugEnabled()) {
								log.debug(format(slot.connection.channel(), "Eviction predicate was true, remove from pool"));
							}
							closeChannel(slot.connection.channel());
							recordInteractionTimestamp();
							slots.remove();
							IDLE_SIZE.decrementAndGet(this);
							slot.invalidate();
						}
					}
				}
			}
			//at the end if there are racing drain calls, go into the drainLoop
			if (WIP.decrementAndGet(this) > 0) {
				drainLoop();
			}
		}
		//schedule the next iteration
		scheduleEviction();
	}

	@Nullable
	Slot findConnection(ConcurrentLinkedQueue<Slot> resources) {
		int resourcesCount = idleSize;
		while (resourcesCount > 0) {
			// There are connections in the queue

			resourcesCount--;

			// get the connection
			Slot slot = pollSlot(resources);
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
					offerSlot(resources, slot);
				}
				else {
					if (log.isDebugEnabled()) {
						log.debug(format(slot.connection.channel(), "Channel is closed, remove from pool"));
					}
					slot.invalidate();
				}
				continue;
			}

			// check the connection received GO_AWAY
			if (slot.goAwayReceived()) {
				if (slot.concurrency() > 0) {
					if (log.isDebugEnabled()) {
						log.debug(format(slot.connection.channel(), "Channel received GO_AWAY, {} active streams"),
								slot.concurrency());
					}
					offerSlot(resources, slot);
				}
				else {
					if (log.isDebugEnabled()) {
						log.debug(format(slot.connection.channel(), "Channel received GO_AWAY, remove from pool"));
					}
					slot.invalidate();
				}
				continue;
			}

			// check whether the eviction predicate for the connection evaluates to true
			if (testEvictionPredicate(slot)) {
				if (slot.concurrency() > 0) {
					if (log.isDebugEnabled()) {
						log.debug(format(slot.connection.channel(), "Eviction predicate was true, {} active streams"),
								slot.concurrency());
					}
					offerSlot(resources, slot);
				}
				else {
					if (log.isDebugEnabled()) {
						log.debug(format(slot.connection.channel(), "Eviction predicate was true, remove from pool"));
					}
					closeChannel(slot.connection.channel());
					slot.invalidate();
				}
				continue;
			}

			// check that the connection's max active streams has not been reached
			if (!slot.canOpenStream()) {
				offerSlot(resources, slot);
				if (log.isDebugEnabled()) {
					log.debug(format(slot.connection.channel(), "Max active streams is reached"));
				}
				continue;
			}

			return slot;
		}

		return null;
	}

	boolean testEvictionPredicate(Slot slot) {
		return poolConfig.evictionPredicate().test(slot.connection, slot);
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
	 * Adds a new {@link Borrower} to the queue.
	 *
	 * @param borrower a new {@link Borrower} to add to the queue and later either serve or consider pending
	 */
	void pendingOffer(Borrower borrower) {
		ConcurrentLinkedDeque<Borrower> pendingQueue = pending;
		if (pendingQueue == TERMINATED) {
			return;
		}

		int postOffer = addPending(pendingQueue, borrower, false);

		long estimateStreamsCount = totalMaxConcurrentStreams - acquired;
		int permits = poolConfig.allocationStrategy().estimatePermitCount();
		if (permits + estimateStreamsCount < postOffer) {
			borrower.pendingAcquireStart = clock.millis();
			if (!borrower.acquireTimeout.isZero()) {
				borrower.timeoutTask = poolConfig.pendingAcquireTimer().apply(borrower, borrower.acquireTimeout);
			}
		}

		if (WIP.getAndIncrement(this) == 0) {
			int maxPending = poolConfig.maxPending();
			ConcurrentLinkedQueue<Slot> ir = connections;
			if (maxPending >= 0 && postOffer > maxPending && ir.isEmpty() && poolConfig.allocationStrategy().estimatePermitCount() == 0) {
				Borrower toCull = pollPending(pendingQueue, false);
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

	@Nullable
	Borrower pollPending(ConcurrentLinkedDeque<Borrower> borrowers, boolean pollFirst) {
		Borrower borrower = pollFirst ? borrowers.pollFirst() : borrowers.pollLast();
		if (borrower != null) {
			PENDING_SIZE.decrementAndGet(this);
		}
		return borrower;
	}

	void removePending(ConcurrentLinkedDeque<Borrower> borrowers, Borrower borrower) {
		if (borrowers.remove(borrower)) {
			PENDING_SIZE.decrementAndGet(this);
		}
	}

	int addPending(ConcurrentLinkedDeque<Borrower> borrowers, Borrower borrower, boolean first) {
		if (first) {
			borrowers.offerFirst(borrower);
		}
		else {
			borrowers.offerLast(borrower);
		}
		return PENDING_SIZE.incrementAndGet(this);
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	void closeChannel(Channel channel) {
		//"FutureReturnValueIgnored" this is deliberate
		channel.close();
	}

	void offerSlot(@Nullable ConcurrentLinkedQueue<Slot> slots, Slot slot) {
		if (slots != null && slots.offer(slot)) {
			IDLE_SIZE.incrementAndGet(this);
		}
	}

	@Nullable
	Slot pollSlot(@Nullable ConcurrentLinkedQueue<Slot> slots) {
		if (slots == null) {
			return null;
		}
		Slot slot = slots.poll();
		if (slot != null) {
			IDLE_SIZE.decrementAndGet(this);
		}
		return slot;
	}

	void removeSlot(Slot slot) {
		@SuppressWarnings("unchecked")
		ConcurrentLinkedQueue<Slot> q = CONNECTIONS.get(slot.pool);
		if (q != null && q.remove(slot)) {
			IDLE_SIZE.decrementAndGet(this);
		}
	}

	void scheduleEviction() {
		if (!poolConfig.evictInBackgroundInterval().isZero()) {
			long nanosEvictionInterval = poolConfig.evictInBackgroundInterval().toNanos();
			this.evictionTask = poolConfig.evictInBackgroundScheduler()
					.schedule(this::evictInBackground, nanosEvictionInterval, TimeUnit.NANOSECONDS);
		}
		else {
			this.evictionTask = Disposables.disposed();
		}
	}

	static final Function<Connection, Publisher<Void>> DEFAULT_DESTROY_HANDLER =
			connection -> {
				if (!connection.channel().isActive()) {
					return Mono.empty();
				}
				return FutureMono.from(connection.channel().close());
			};

	static final class Borrower extends AtomicBoolean implements Scannable, Subscription, Runnable {

		static final Disposable TIMEOUT_DISPOSED = Disposables.disposed();

		final Duration acquireTimeout;
		final CoreSubscriber<? super Http2PooledRef> actual;
		final Http2Pool pool;

		long pendingAcquireStart;

		Disposable timeoutTask;

		Borrower(CoreSubscriber<? super Http2PooledRef> actual, Http2Pool pool, Duration acquireTimeout) {
			this.acquireTimeout = acquireTimeout;
			this.actual = actual;
			this.pool = pool;
			this.timeoutTask = TIMEOUT_DISPOSED;
		}

		@Override
		public void cancel() {
			stopPendingCountdown(true); // this is not failure, the subscription was canceled
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
				pool.doAcquire(this);
			}
		}

		@Override
		public void run() {
			if (compareAndSet(false, true)) {
				// this is failure, a timeout was observed
				stopPendingCountdown(false);
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
			assert poolSlot.slot.connection.channel().eventLoop().inEventLoop();
			poolSlot.slot.incrementConcurrencyAndGet();
			poolSlot.slot.deactivate();
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
			stopPendingCountdown(false);
			if (!get()) {
				actual.onError(error);
			}
		}

		void stopPendingCountdown(boolean success) {
			if (pendingAcquireStart > 0) {
				if (success) {
					pool.poolConfig.metricsRecorder().recordPendingSuccessAndLatency(pool.clock.millis() - pendingAcquireStart);
				}
				else {
					pool.poolConfig.metricsRecorder().recordPendingFailureAndLatency(pool.clock.millis() - pendingAcquireStart);
				}

				pendingAcquireStart = 0;
			}
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

	static class Slot extends AtomicBoolean implements PooledRefMetadata {

		volatile int concurrency;
		static final AtomicIntegerFieldUpdater<Slot> CONCURRENCY =
				AtomicIntegerFieldUpdater.newUpdater(Slot.class, "concurrency");

		final Connection connection;
		final long creationTimestamp;
		final Http2Pool pool;
		final String applicationProtocol;

		long idleTimestamp;
		long maxConcurrentStreams;

		volatile ChannelHandlerContext http2FrameCodecCtx;
		volatile ChannelHandlerContext http2MultiplexHandlerCtx;
		volatile ChannelHandlerContext h2cUpgradeHandlerCtx;

		Slot(Http2Pool pool, Connection connection) {
			this.connection = connection;
			this.creationTimestamp = pool.clock.millis();
			this.pool = pool;
			SslHandler handler = connection.channel().pipeline().get(SslHandler.class);
			if (handler != null) {
				this.applicationProtocol = handler.applicationProtocol() != null ?
						handler.applicationProtocol() : ApplicationProtocolNames.HTTP_1_1;
			}
			else {
				this.applicationProtocol = null;
			}
			initMaxConcurrentStreams();
			TOTAL_MAX_CONCURRENT_STREAMS.addAndGet(this.pool, this.maxConcurrentStreams);
		}

		void initMaxConcurrentStreams() {
			ChannelHandlerContext frameCodec = http2FrameCodecCtx();
			if (frameCodec != null && http2MultiplexHandlerCtx() != null) {
				this.maxConcurrentStreams = ((Http2FrameCodec) frameCodec.handler()).connection().local().maxActiveStreams();
				this.maxConcurrentStreams = pool.maxConcurrentStreams == -1 ? maxConcurrentStreams :
						Math.min(pool.maxConcurrentStreams, maxConcurrentStreams);
			}
		}

		boolean canOpenStream() {
			ChannelHandlerContext frameCodec = http2FrameCodecCtx();
			if (frameCodec != null && http2MultiplexHandlerCtx() != null) {
				long maxActiveStreams = ((Http2FrameCodec) frameCodec.handler()).connection().local().maxActiveStreams();
				maxActiveStreams = pool.maxConcurrentStreams == -1 ? maxActiveStreams :
						Math.min(pool.maxConcurrentStreams, maxActiveStreams);
				long diff = maxActiveStreams - maxConcurrentStreams;
				if (diff != 0) {
					maxConcurrentStreams = maxActiveStreams;
					TOTAL_MAX_CONCURRENT_STREAMS.addAndGet(this.pool, diff);
				}
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
			@SuppressWarnings("unchecked")
			ConcurrentLinkedQueue<Slot> slots = CONNECTIONS.get(pool);
			pool.offerSlot(slots, this);
		}

		int decrementConcurrencyAndGet() {
			int concurrency = CONCURRENCY.decrementAndGet(this);
			idleTimestamp = pool.clock.millis();
			return concurrency;
		}

		boolean goAwayReceived() {
			ChannelHandlerContext frameCodec = http2FrameCodecCtx();
			return frameCodec != null && ((Http2FrameCodec) frameCodec.handler()).connection().goAwayReceived();
		}

		@Nullable
		ChannelHandlerContext http2FrameCodecCtx() {
			ChannelHandlerContext ctx = http2FrameCodecCtx;
			// ChannelHandlerContext.isRemoved is only meant to be called from within the EventLoop
			if (ctx != null && connection.channel().eventLoop().inEventLoop() && !ctx.isRemoved()) {
				return ctx;
			}
			ctx = connection.channel().pipeline().context(Http2FrameCodec.class);
			http2FrameCodecCtx = ctx;
			return ctx;
		}

		@Nullable
		ChannelHandlerContext http2MultiplexHandlerCtx() {
			ChannelHandlerContext ctx = http2MultiplexHandlerCtx;
			// ChannelHandlerContext.isRemoved is only meant to be called from within the EventLoop
			if (ctx != null && connection.channel().eventLoop().inEventLoop() && !ctx.isRemoved()) {
				return ctx;
			}
			ctx = connection.channel().pipeline().context(Http2MultiplexHandler.class);
			http2MultiplexHandlerCtx = ctx;
			return ctx;
		}

		@Nullable
		ChannelHandlerContext h2cUpgradeHandlerCtx() {
			ChannelHandlerContext ctx = h2cUpgradeHandlerCtx;
			// ChannelHandlerContext.isRemoved is only meant to be called from within the EventLoop
			if (ctx != null && connection.channel().eventLoop().inEventLoop() && !ctx.isRemoved()) {
				return ctx;
			}
			ctx = connection.channel().pipeline().context(NettyPipeline.H2CUpgradeHandler);
			h2cUpgradeHandlerCtx = ctx;
			return ctx;
		}

		void incrementConcurrencyAndGet() {
			CONCURRENCY.incrementAndGet(this);
		}

		void invalidate() {
			if (compareAndSet(false, true)) {
				if (log.isDebugEnabled()) {
					log.debug(format(connection.channel(), "Channel removed from pool"));
				}
				pool.poolConfig.allocationStrategy().returnPermits(1);
				TOTAL_MAX_CONCURRENT_STREAMS.addAndGet(this.pool, -maxConcurrentStreams);
			}
		}

		@Override
		public long idleTime() {
			if (concurrency() > 0) {
				return 0L;
			}
			long idleTime = idleTimestamp != 0 ? idleTimestamp : creationTimestamp;
			return pool.clock.millis() - idleTime;
		}

		@Override
		public int acquireCount() {
			return 1;
		}

		@Override
		public long lifeTime() {
			return pool.clock.millis() - creationTimestamp;
		}

		@Override
		public long releaseTimestamp() {
			return 0;
		}

		@Override
		public long allocationTimestamp() {
			return creationTimestamp;
		}

	}
}
