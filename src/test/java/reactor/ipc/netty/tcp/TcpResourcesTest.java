package reactor.ipc.netty.tcp;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.ChannelPool;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.resources.PoolResources;

import static org.assertj.core.api.Assertions.assertThat;

public class TcpResourcesTest {

	private AtomicBoolean loopDisposed;
	private AtomicBoolean poolDisposed;
	private LoopResources loopResources;
	private PoolResources poolResources;
	private TcpResources tcpResources;

	@Before
	public void before() {
		loopDisposed = new AtomicBoolean();
		poolDisposed = new AtomicBoolean();

		loopResources = new LoopResources() {
			@Override
			public EventLoopGroup onServer(boolean useNative) {
				return null;
			}

			@Override
			public Mono<Void> disposeLater() {
				return Mono.<Void>empty().doOnSuccess(c -> loopDisposed.set(true));
			}

			@Override
			public boolean isDisposed() {
				return loopDisposed.get();
			}
		};

		poolResources = new PoolResources() {
			@Override
			public ChannelPool selectOrCreate(SocketAddress address,
					Supplier<? extends Bootstrap> bootstrap,
					Consumer<? super Channel> onChannelCreate, EventLoopGroup group) {
				return null;
			}

			public Mono<Void> disposeLater() {
				return Mono.<Void>empty().doOnSuccess(c -> poolDisposed.set(true));
			}

			@Override
			public boolean isDisposed() {
				return poolDisposed.get();
			}
		};

		tcpResources = new TcpResources(loopResources, poolResources);
	}

	@Test
	public void disposeLaterDefers() {
		assertThat(tcpResources.isDisposed()).isFalse();

		tcpResources.disposeLater();
		assertThat(tcpResources.isDisposed()).isFalse();

		tcpResources.disposeLater()
		            .doOnSuccess(c -> assertThat(tcpResources.isDisposed()).isTrue())
		            .subscribe();
		//not immediately disposed when subscribing
		assertThat(tcpResources.isDisposed()).as("immediate status on disposeLater subscribe").isFalse();
	}

	@Test
	public void shutdownLaterDefers() {
		TcpResources oldTcpResources = TcpResources.tcpResources.getAndSet(tcpResources);
		TcpResources newTcpResources = TcpResources.tcpResources.get();

		try {
			assertThat(newTcpResources).isSameAs(tcpResources);

			TcpResources.shutdownLater();
			assertThat(newTcpResources.isDisposed()).isFalse();

			TcpResources.shutdownLater().block();
			assertThat(newTcpResources.isDisposed()).as("shutdownLater completion").isTrue();

			assertThat(TcpResources.tcpResources.get()).isNull();
		}
		finally {
			if (oldTcpResources != null && !TcpResources.tcpResources.compareAndSet(null, oldTcpResources)) {
				oldTcpResources.dispose();
			}
		}
	}

}