/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.tcp;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.socket.DatagramChannel;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.resources.PoolResources;

/**
 * Hold the default Tcp resources
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public class TcpResources implements PoolResources, LoopResources {

	/**
	 * Return the global HTTP resources for event loops and pooling
	 *
	 * @return the global HTTP resources for event loops and pooling
	 */
	public static TcpResources get() {
		return getOrCreate(tcpResources, null, null, ON_TCP_NEW,  "tcp");
	}

	/**
	 * Update event loops resources and return the global HTTP resources
	 *
	 * @return the global HTTP resources
	 */
	public static TcpResources set(PoolResources pools) {
		return getOrCreate(tcpResources, null, pools, ON_TCP_NEW, "tcp");
	}

	/**
	 * Update pooling resources and return the global HTTP resources
	 *
	 * @return the global HTTP resources
	 */
	public static TcpResources set(LoopResources loops) {
		return getOrCreate(tcpResources, loops, null, ON_TCP_NEW ,"tcp");
	}

	/**
	 * Reset http resources to default and return its instance
	 *
	 * @return the global HTTP resources
	 */
	public static TcpResources reset() {
		shutdown();
		return getOrCreate(tcpResources, null, null, ON_TCP_NEW, "tcp");
	}

	/**
	 * Shutdown the global {@link TcpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones.
	 */
	public static void shutdown() {
		TcpResources resources = tcpResources.getAndSet(null);
		if (resources != null) {
			resources._dispose();
		}
	}

	/**
	 * Prepare to shutdown the global {@link TcpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones. This only
	 * occurs when the returned {@link Mono} is subscribed to.
	 *
	 * @return a {@link Mono} triggering the {@link #shutdown()} when subscribed to.
	 */
	public static Mono<Void> shutdownLater() {
		return Mono.defer(() -> {
			TcpResources resources = tcpResources.getAndSet(null);
			if (resources != null) {
				return resources._disposeLater();
			}
			return Mono.empty();
		});
	}

	final PoolResources defaultPools;
	final LoopResources defaultLoops;

	protected TcpResources(LoopResources defaultLoops, PoolResources defaultPools) {
		this.defaultLoops = defaultLoops;
		this.defaultPools = defaultPools;
	}

	@Override
	public void dispose() {
		//noop on global by default
	}

	@Override
	public Mono<Void> disposeLater() {
		return Mono.empty(); //noop on global by default
	}

	/**
	 * Dispose underlying resources
	 */
	//TODO make public?
	protected void _dispose(){
		defaultPools.dispose();
		defaultLoops.dispose();
	}

	/**
	 * Dispose underlying resources in a listenable fashion.
	 * @return the Mono that represents the end of disposal
	 */
	protected Mono<Void> _disposeLater() {
		return Mono.zip(
				defaultLoops.disposeLater(),
				defaultPools.disposeLater())
		           .then();
	}

	@Override
	public boolean isDisposed() {
		return defaultLoops.isDisposed() && defaultPools.isDisposed();
	}

	@Override
	public ChannelPool selectOrCreate(Bootstrap bootstrap) {
		return defaultPools.selectOrCreate(bootstrap);
	}

	@Override
	public Class<? extends Channel> onChannel(EventLoopGroup group) {
		return defaultLoops.onChannel(group);
	}

	@Override
	public EventLoopGroup onClient(boolean useNative) {
		return defaultLoops.onClient(useNative);
	}

	@Override
	public Class<? extends DatagramChannel> onDatagramChannel(EventLoopGroup group) {
		return defaultLoops.onDatagramChannel(group);
	}

	@Override
	public EventLoopGroup onServer(boolean useNative) {
		return defaultLoops.onServer(useNative);
	}

	@Override
	public Class<? extends ServerChannel> onServerChannel(EventLoopGroup group) {
		return defaultLoops.onServerChannel(group);
	}

	@Override
	public EventLoopGroup onServerSelect(boolean useNative) {
		return defaultLoops.onServerSelect(useNative);
	}

	@Override
	public boolean preferNative() {
		return defaultLoops.preferNative();
	}

	@Override
	public boolean daemon() {
		return defaultLoops.daemon();
	}

	/**
	 * Safely check if existing resource exist and proceed to update/cleanup if new
	 * resources references are passed.
	 *
	 * @param ref the resources atomic reference
	 * @param loops the eventual new {@link LoopResources}
	 * @param pools the eventual new {@link PoolResources}
	 * @param onNew a {@link TcpResources} factory
	 * @param name a name for resources
	 * @param <T> the reified type of {@link TcpResources}
	 *
	 * @return an existing or new {@link TcpResources}
	 */
	protected static <T extends TcpResources> T getOrCreate(AtomicReference<T> ref,
			LoopResources loops,
			PoolResources pools,
			BiFunction<LoopResources, PoolResources, T> onNew,
			String name) {
		T update;
		for (; ; ) {
			T resources = ref.get();
			if (resources == null || loops != null || pools != null) {
				update = create(resources, loops, pools, name, onNew);
				if (ref.compareAndSet(resources, update)) {
					if(resources != null){
						if(loops != null){
							resources.defaultLoops.dispose();
						}
						if(pools != null){
							resources.defaultPools.dispose();
						}
					}
					return update;
				}
				else {
					update._dispose();
				}
			}
			else {
				return resources;
			}
		}
	}

	static final AtomicReference<TcpResources>                          tcpResources;
	static final BiFunction<LoopResources, PoolResources, TcpResources> ON_TCP_NEW;

	static {
		ON_TCP_NEW = TcpResources::new;
		tcpResources  = new AtomicReference<>();
	}

	static <T extends TcpResources> T create(T previous,
			LoopResources loops,
			PoolResources pools,
			String name,
			BiFunction<LoopResources, PoolResources, T> onNew) {
		if (previous == null) {
			loops = loops == null ? LoopResources.create("reactor-" + name) : loops;
			pools = pools == null ? PoolResources.elastic(name) : pools;
		}
		else {
			loops = loops == null ? previous.defaultLoops : loops;
			pools = pools == null ? previous.defaultPools : pools;
		}
		return onNew.apply(loops, pools);
	}
}
