/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * A minimal {@link Scheduler} dedicated to connection pool maintenance tasks: background
 * eviction and inactive pool disposal.
 *
 * <p>Tasks are submitted directly to a shared daemon thread and deliberately do
 * NOT go through {@link reactor.core.scheduler.Schedulers} task decoration
 * ({@code Schedulers.onSchedule(...)}). Maintenance tasks are library-internal,
 * self-rescheduling and live as long as the application; decorating them with hooks such
 * as the context-capture hook installed by
 * {@code Hooks.enableAutomaticContextPropagation()} captures the scheduling caller's
 * {@code ThreadLocal} state (typically a request scope, because pools are created lazily
 * on request processing threads) into a pending task and re-captures it on every
 * reschedule, retaining the caller's object graph for the lifetime of the pool.
 */
final class PoolMaintenanceScheduler implements Scheduler {

	static final PoolMaintenanceScheduler INSTANCE = new PoolMaintenanceScheduler();

	final ScheduledThreadPoolExecutor executor;

	private PoolMaintenanceScheduler() {
		AtomicLong counter = new AtomicLong();
		ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(Schedulers.DEFAULT_POOL_SIZE, r -> {
			Thread t = new Thread(r, "reactor-netty-pool-maintenance-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		e.setRemoveOnCancelPolicy(true);
		this.executor = e;
	}

	@Override
	public Disposable schedule(Runnable task) {
		return toDisposable(executor.submit(task));
	}

	@Override
	public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
		return toDisposable(executor.schedule(task, delay, unit));
	}

	@Override
	public Disposable schedulePeriodically(Runnable task, long initialDelay, long period, TimeUnit unit) {
		return toDisposable(executor.scheduleAtFixedRate(task, initialDelay, period, unit));
	}

	@Override
	public Worker createWorker() {
		return new PoolMaintenanceWorker(this);
	}

	@Override
	public void dispose() {
		// shared infrastructure scheduler backed by a daemon thread, never disposed
	}

	static Disposable toDisposable(Future<?> future) {
		return () -> future.cancel(false);
	}

	static final class PoolMaintenanceWorker implements Worker {

		final PoolMaintenanceScheduler parent;
		final Disposable.Composite tasks = Disposables.composite();

		PoolMaintenanceWorker(PoolMaintenanceScheduler parent) {
			this.parent = parent;
		}

		@Override
		public Disposable schedule(Runnable task) {
			return track(parent.schedule(task));
		}

		@Override
		public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
			return track(parent.schedule(task, delay, unit));
		}

		@Override
		public Disposable schedulePeriodically(Runnable task, long initialDelay, long period, TimeUnit unit) {
			return track(parent.schedulePeriodically(task, initialDelay, period, unit));
		}

		@Override
		public void dispose() {
			tasks.dispose();
		}

		@Override
		public boolean isDisposed() {
			return tasks.isDisposed();
		}

		Disposable track(Disposable disposable) {
			tasks.add(disposable);
			return disposable;
		}
	}
}
