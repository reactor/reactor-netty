/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty.resources;

import java.time.Duration;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.Future;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.NonBlocking;
import reactor.netty.FutureMono;

/**
 * An adapted global eventLoop handler.
 *
 * @since 0.6
 */
final class DefaultLoopResources extends AtomicLong implements LoopResources {

	final String                          prefix;
	final boolean                         daemon;
	final int                             selectCount;
	final int                             workerCount;
	final AtomicReference<EventLoopGroup> serverLoops;
	final AtomicReference<EventLoopGroup> clientLoops;
	final AtomicReference<EventLoopGroup> serverSelectLoops;
	final AtomicReference<EventLoopGroup> cacheNativeClientLoops;
	final AtomicReference<EventLoopGroup> cacheNativeServerLoops;
	final AtomicReference<EventLoopGroup> cacheNativeSelectLoops;
	final AtomicBoolean                   running;

	static ThreadFactory threadFactory(DefaultLoopResources parent, String prefix) {
		return new EventLoopFactory(parent.daemon,
				parent.prefix + "-" + prefix,
				parent);
	}

	DefaultLoopResources(String prefix, int workerCount, boolean daemon) {
		this(prefix, -1, workerCount, daemon);
	}

	DefaultLoopResources(String prefix,
			int selectCount,
			int workerCount,
			boolean daemon) {
		this.running = new AtomicBoolean(true);
		this.daemon = daemon;
		this.workerCount = workerCount;
		this.prefix = prefix;

		this.serverLoops = new AtomicReference<>();
		this.clientLoops = new AtomicReference<>();

		this.cacheNativeClientLoops = new AtomicReference<>();
		this.cacheNativeServerLoops = new AtomicReference<>();

		if (selectCount == -1) {
			this.selectCount = workerCount;
			this.serverSelectLoops = this.serverLoops;
			this.cacheNativeSelectLoops = this.cacheNativeServerLoops;
		}
		else {
			this.selectCount = selectCount;
			this.serverSelectLoops = new AtomicReference<>();
			this.cacheNativeSelectLoops = new AtomicReference<>();
		}
	}

	@Override
	public boolean isDisposed() {
		return !running.get();
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<Void> disposeLater(Duration quietPeriod, Duration timeout) {
		return Mono.defer(() -> {
			long quietPeriodMillis = quietPeriod.toMillis();
			long timeoutMillis = timeout.toMillis();

			EventLoopGroup serverLoopsGroup = serverLoops.get();
			EventLoopGroup clientLoopsGroup = clientLoops.get();
			EventLoopGroup serverSelectLoopsGroup = serverSelectLoops.get();
			EventLoopGroup cacheNativeClientGroup = cacheNativeClientLoops.get();
			EventLoopGroup cacheNativeSelectGroup = cacheNativeSelectLoops.get();
			EventLoopGroup cacheNativeServerGroup = cacheNativeServerLoops.get();

			Mono<?> clMono = Mono.empty();
			Mono<?> sslMono = Mono.empty();
			Mono<?> slMono = Mono.empty();
			Mono<?> cnclMono = Mono.empty();
			Mono<?> cnslMono = Mono.empty();
			Mono<?> cnsrvlMono = Mono.empty();
			if(running.compareAndSet(true, false)) {
				if (clientLoopsGroup != null) {
					clMono = FutureMono.from((Future) clientLoopsGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS));
				}
				if (serverSelectLoopsGroup != null) {
					sslMono = FutureMono.from((Future) serverSelectLoopsGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS));
				}
				if (serverLoopsGroup != null) {
					slMono = FutureMono.from((Future) serverLoopsGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS));
				}
				if(cacheNativeClientGroup != null){
					cnclMono = FutureMono.from((Future) cacheNativeClientGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS));
				}
				if(cacheNativeSelectGroup != null){
					cnslMono = FutureMono.from((Future) cacheNativeSelectGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS));
				}
				if(cacheNativeServerGroup != null){
					cnsrvlMono = FutureMono.from((Future) cacheNativeServerGroup.shutdownGracefully(
							quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS));
				}
			}

			return Mono.when(clMono, sslMono, slMono, cnclMono, cnslMono, cnsrvlMono);
		});
	}

	@Override
	public EventLoopGroup onServerSelect(boolean useNative) {
		if (useNative && preferNative()) {
			return cacheNativeSelectLoops();
		}
		return cacheNioSelectLoops();
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	EventLoopGroup cacheNioSelectLoops() {
		if (serverSelectLoops == serverLoops) {
			return cacheNioServerLoops();
		}

		EventLoopGroup eventLoopGroup = serverSelectLoops.get();
		if (null == eventLoopGroup) {
			EventLoopGroup newEventLoopGroup = new NioEventLoopGroup(selectCount,
					threadFactory(this, "select-nio"));
			if (!serverSelectLoops.compareAndSet(null, newEventLoopGroup)) {
				//"FutureReturnValueIgnored" this is deliberate
				newEventLoopGroup.shutdownGracefully();
			}
			eventLoopGroup = cacheNioSelectLoops();
		}
		return eventLoopGroup;
	}

	@Override
	public EventLoopGroup onServer(boolean useNative) {
		if (useNative && preferNative()) {
			return cacheNativeServerLoops();
		}
		return cacheNioServerLoops();
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	EventLoopGroup cacheNioServerLoops() {
		EventLoopGroup eventLoopGroup = serverLoops.get();
		if (null == eventLoopGroup) {
			EventLoopGroup newEventLoopGroup = new NioEventLoopGroup(workerCount,
					threadFactory(this, "nio"));
			if (!serverLoops.compareAndSet(null, newEventLoopGroup)) {
				//"FutureReturnValueIgnored" this is deliberate
				newEventLoopGroup.shutdownGracefully();
			}
			eventLoopGroup = cacheNioServerLoops();
		}
		return eventLoopGroup;
	}

	@Override
	public EventLoopGroup onClient(boolean useNative) {
		if (useNative && preferNative()) {
			return cacheNativeClientLoops();
		}
		return cacheNioClientLoops();
	}

	EventLoopGroup cacheNioClientLoops() {
		EventLoopGroup eventLoopGroup = clientLoops.get();
		if (null == eventLoopGroup) {
			EventLoopGroup newEventLoopGroup = LoopResources.colocate(cacheNioServerLoops());
			if (!clientLoops.compareAndSet(null, newEventLoopGroup)) {
				// Do not shutdown newEventLoopGroup as this will shutdown the server loops
			}
			eventLoopGroup = cacheNioClientLoops();
		}
		return eventLoopGroup;
	}

	@Override
	public String toString() {
		return "DefaultLoopResources {" +
				"prefix=" + prefix +
				", daemon=" + daemon +
				", selectCount=" + selectCount +
				", workerCount=" + workerCount +
				'}';
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	EventLoopGroup cacheNativeSelectLoops() {
		if (cacheNativeSelectLoops == cacheNativeServerLoops) {
			return cacheNativeServerLoops();
		}

		EventLoopGroup eventLoopGroup = cacheNativeSelectLoops.get();
		if (null == eventLoopGroup) {
			DefaultLoop defaultLoop = DefaultLoopNativeDetector.getInstance();
			EventLoopGroup newEventLoopGroup = defaultLoop.newEventLoopGroup(
					selectCount,
					threadFactory(this, "select-" + defaultLoop.getName()));
			if (!cacheNativeSelectLoops.compareAndSet(null, newEventLoopGroup)) {
				//"FutureReturnValueIgnored" this is deliberate
				newEventLoopGroup.shutdownGracefully();
			}
			eventLoopGroup = cacheNativeSelectLoops();
		}
		return eventLoopGroup;
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	EventLoopGroup cacheNativeServerLoops() {
		EventLoopGroup eventLoopGroup = cacheNativeServerLoops.get();
		if (null == eventLoopGroup) {
			DefaultLoop defaultLoop = DefaultLoopNativeDetector.getInstance();
			EventLoopGroup newEventLoopGroup = defaultLoop.newEventLoopGroup(
					workerCount,
					threadFactory(this, defaultLoop.getName()));
			if (!cacheNativeServerLoops.compareAndSet(null, newEventLoopGroup)) {
				//"FutureReturnValueIgnored" this is deliberate
				newEventLoopGroup.shutdownGracefully();
			}
			eventLoopGroup = cacheNativeServerLoops();
		}
		return eventLoopGroup;
	}

	EventLoopGroup cacheNativeClientLoops() {
		EventLoopGroup eventLoopGroup = cacheNativeClientLoops.get();
		if (null == eventLoopGroup) {
			EventLoopGroup newEventLoopGroup = LoopResources.colocate(cacheNativeServerLoops());
			if (!cacheNativeClientLoops.compareAndSet(null, newEventLoopGroup)) {
				// Do not shutdown newEventLoopGroup as this will shutdown the server loops
			}
			eventLoopGroup = cacheNativeClientLoops();
		}
		return eventLoopGroup;
	}

	final static class EventLoopFactory implements ThreadFactory {

		final boolean    daemon;
		final AtomicLong counter;
		final String     prefix;

		EventLoopFactory(boolean daemon,
				String prefix,
				AtomicLong counter) {
			this.daemon = daemon;
			this.counter = counter;
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new EventLoop(r);
			t.setDaemon(daemon);
			t.setName(prefix + "-" + counter.incrementAndGet());
			return t;
		}
	}

	final static class EventLoop extends FastThreadLocalThread implements NonBlocking {

		EventLoop(Runnable target) {
			super(target);
		}
	}
}
