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
		this.running = new AtomicBoolean();
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
	public Mono<Void> disposeDeferred() {
		return Mono.defer(() -> {
			if(running.compareAndSet(false, true)){
				Future<?> clFuture = clientLoops.shutdownGracefully();
				Mono<?> clMono = FutureMono.from((Future) clFuture);

				Future<?> sslFuture = serverSelectLoops.shutdownGracefully();
				Mono<?> sslMono = FutureMono.from((Future)sslFuture);

				Future<?> slFuture = serverLoops.shutdownGracefully();
				Mono<?> slMono = FutureMono.from((Future)slFuture);

				Mono<?> cnclMono = Mono.empty();
				EventLoopGroup group = cacheNativeClientLoops.get();
				if(group != null){
					final Future<?> cnclFuture = group.shutdownGracefully();
					cnclMono = FutureMono.from((Future)cnclFuture);
				}

				Mono<?> cnslMono = Mono.empty();
				group = cacheNativeSelectLoops.get();
				if(group != null){
					Future<?> cnslFuture = group.shutdownGracefully();
					cnslMono = FutureMono.from((Future)cnslFuture);
				}

				Mono<?> cnsrvlMono = Mono.empty();
				group = cacheNativeServerLoops.get();
				if(group != null){
					Future<?> cnsrvlFuture = group.shutdownGracefully();
					cnsrvlMono = FutureMono.from((Future)cnsrvlFuture);
				}

				return Mono.when(clMono, slMono, cnclMono, cnslMono, cnsrvlMono).then();
			}
			return Mono.empty();
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
