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

package reactor.ipc.netty.resources;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.FutureMono;

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
	final EventLoopGroup                  serverLoops;
	final EventLoopGroup                  clientLoops;
	final EventLoopGroup                  serverSelectLoops;
	final AtomicReference<EventLoopGroup> cacheNativeClientLoops;
	final AtomicReference<EventLoopGroup> cacheNativeServerLoops;
	final AtomicReference<EventLoopGroup> cacheNativeSelectLoops;
	final AtomicBoolean                   running;

	static ThreadFactory threadFactory(DefaultLoopResources parent, String prefix) {
		return new EventLoopSelectorFactory(parent.daemon,
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

		this.serverLoops = new NioEventLoopGroup(workerCount,
				threadFactory(this, "nio"));

		this.clientLoops = LoopResources.colocate(serverLoops);

		this.cacheNativeClientLoops = new AtomicReference<>();
		this.cacheNativeServerLoops = new AtomicReference<>();

		if (selectCount == -1) {
			this.selectCount = workerCount;
			this.serverSelectLoops = this.serverLoops;
			this.cacheNativeSelectLoops = this.cacheNativeServerLoops;
		}
		else {
			this.selectCount = selectCount;
			this.serverSelectLoops =
					new NioEventLoopGroup(selectCount, threadFactory(this, "select-nio"));
			this.cacheNativeSelectLoops = new AtomicReference<>();
		}
	}

	@Override
	public boolean isDisposed() {
		return !running.get();
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<Void> disposeLater() {
		return Mono.defer(() -> {
			EventLoopGroup cacheNativeClientGroup = cacheNativeClientLoops.get();
			EventLoopGroup cacheNativeSelectGroup = cacheNativeSelectLoops.get();
			EventLoopGroup cacheNativeServerGroup = cacheNativeServerLoops.get();

			if(running.compareAndSet(true, false)) {
				clientLoops.shutdownGracefully();
				serverSelectLoops.shutdownGracefully();
				serverLoops.shutdownGracefully();
				if(cacheNativeClientGroup != null){
					cacheNativeClientGroup.shutdownGracefully();
				}
				if(cacheNativeSelectGroup != null){
					cacheNativeSelectGroup.shutdownGracefully();
				}
				if(cacheNativeServerGroup != null){
					cacheNativeServerGroup.shutdownGracefully();
				}
			}

			Mono<?> clMono = FutureMono.from((Future) clientLoops.terminationFuture());
			Mono<?> sslMono = FutureMono.from((Future)serverSelectLoops.terminationFuture());
			Mono<?> slMono = FutureMono.from((Future)serverLoops.terminationFuture());
			Mono<?> cnclMono = Mono.empty();
			if(cacheNativeClientGroup != null){
				cnclMono = FutureMono.from((Future) cacheNativeClientGroup.terminationFuture());
			}
			Mono<?> cnslMono = Mono.empty();
			if(cacheNativeSelectGroup != null){
				cnslMono = FutureMono.from((Future) cacheNativeSelectGroup.terminationFuture());
			}
			Mono<?> cnsrvlMono = Mono.empty();
			if(cacheNativeServerGroup != null){
				cnsrvlMono = FutureMono.from((Future) cacheNativeServerGroup.terminationFuture());
			}

			return Mono.when(clMono, sslMono, slMono, cnclMono, cnslMono, cnsrvlMono);
		});
	}

	@Override
	public EventLoopGroup onServerSelect(boolean useNative) {
		if (useNative && preferNative()) {
			return cacheNativeSelectLoops();
		}
		return serverSelectLoops;
	}

	@Override
	public EventLoopGroup onServer(boolean useNative) {
		if (useNative && preferNative()) {
			return cacheNativeServerLoops();
		}
		return serverLoops;
	}

	@Override
	public EventLoopGroup onClient(boolean useNative) {
		if (useNative && preferNative()) {
			return cacheNativeClientLoops();
		}
		return clientLoops;
	}

	EventLoopGroup cacheNativeSelectLoops() {
		if (cacheNativeSelectLoops == cacheNativeServerLoops) {
			return cacheNativeServerLoops();
		}

		EventLoopGroup eventLoopGroup = cacheNativeSelectLoops.get();
		if (null == eventLoopGroup) {
			EventLoopGroup newEventLoopGroup = DefaultLoopEpollDetector.newEventLoopGroup(
					selectCount,
					threadFactory(this, "select-epoll"));
			if (!cacheNativeSelectLoops.compareAndSet(null, newEventLoopGroup)) {
				newEventLoopGroup.shutdownGracefully();
			}
			eventLoopGroup = cacheNativeSelectLoops();
		}
		return eventLoopGroup;
	}

	EventLoopGroup cacheNativeServerLoops() {
		EventLoopGroup eventLoopGroup = cacheNativeServerLoops.get();
		if (null == eventLoopGroup) {
			EventLoopGroup newEventLoopGroup = DefaultLoopEpollDetector.newEventLoopGroup(
					workerCount,
					threadFactory(this, "server-epoll"));
			if (!cacheNativeServerLoops.compareAndSet(null, newEventLoopGroup)) {
				newEventLoopGroup.shutdownGracefully();
			}
			eventLoopGroup = cacheNativeServerLoops();
		}
		return eventLoopGroup;
	}

	EventLoopGroup cacheNativeClientLoops() {
		EventLoopGroup eventLoopGroup = cacheNativeClientLoops.get();
		if (null == eventLoopGroup) {
			EventLoopGroup newEventLoopGroup = DefaultLoopEpollDetector.newEventLoopGroup(
					workerCount,
					threadFactory(this, "client-epoll"));
			newEventLoopGroup = LoopResources.colocate(newEventLoopGroup);
			if (!cacheNativeClientLoops.compareAndSet(null, newEventLoopGroup)) {
				newEventLoopGroup.shutdownGracefully();
			}
			eventLoopGroup = cacheNativeClientLoops();
		}
		return eventLoopGroup;
	}

	final static class EventLoopSelectorFactory implements ThreadFactory {

		final boolean    daemon;
		final AtomicLong counter;
		final String     prefix;

		public EventLoopSelectorFactory(boolean daemon,
				String prefix,
				AtomicLong counter) {
			this.daemon = daemon;
			this.counter = counter;
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r);
			t.setDaemon(daemon);
			t.setName(prefix + "-" + counter.incrementAndGet());
			return t;
		}
	}
}
