package reactor.netty.resources;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.resolver.AddressResolverGroup;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.ConnectionObserver;
import reactor.netty.SocketUtils;
import reactor.netty.tcp.TcpClientTests;
import reactor.netty.tcp.TcpResources;
import reactor.netty.transport.NameResolverProvider;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class WaitingTest {

	@Test
	void waitingForThreadTest() throws Exception {
		final ScheduledExecutorService service = Executors.newScheduledThreadPool(1);
		int echoServerPort = SocketUtils.findAvailableTcpPort();
		TcpClientTests.EchoServer echoServer = new TcpClientTests.EchoServer(echoServerPort);
		EventLoopGroup group = new MultiThreadIoEventLoopGroup(1 , NioIoHandler.newFactory());

		try (AddressResolverGroup<?> resolver =
				     NameResolverProvider.builder().build().newNameResolverGroup(TcpResources.get(), true)) {
			final InetSocketAddress address = InetSocketAddress.createUnresolved("localhost", echoServerPort);
			ConnectionProvider pool = ConnectionProvider.create("fixed10connectionsPool", 10);

			Supplier<? extends SocketAddress> remoteAddress = () -> address;
			ConnectionObserver observer = ConnectionObserver.emptyListener();
			DefaultPooledConnectionProviderTest.ClientTransportConfigImpl config =
					new DefaultPooledConnectionProviderTest.ClientTransportConfigImpl(group, pool, Collections.emptyMap(), remoteAddress, resolver);

			//start the echo server
			service.submit(echoServer);
			Thread.sleep(100);

			// simulate first request
			Long startExecutingRequests = System.nanoTime();
			new Thread(() -> {
				pool.acquire(config, observer, remoteAddress, config.resolverInternal())
						.doOnNext(connection -> executeBlockingOperation(startExecutingRequests, "id1"))
						.doOnNext(connection -> connection.disposeNow())
						.subscribe();
			}).start();

			// simulate second request
			new Thread(() -> {
				pool.acquire(config, observer, remoteAddress, config.resolverInternal())
						.doOnNext(connection -> executeBlockingOperation(startExecutingRequests, "id2"))
						.doOnNext(connection -> connection.disposeNow())
						.subscribe();
			}).start();

			// simulate third request
			new Thread(() -> {
				pool.acquire(config, observer, remoteAddress, config.resolverInternal())
						.doOnNext(connection -> executeBlockingOperation(startExecutingRequests, "id3"))
						.doOnNext(connection -> connection.disposeNow())
						.subscribe();
			}).start();

			Thread.sleep(10000);
		}
		finally {
			service.shutdownNow();
			echoServer.close();
			group.shutdownGracefully()
					.get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	void waitingForThreadTestButThereIsPublishOn() throws Exception {

		Scheduler cpuBoundPool = Schedulers.newBoundedElastic(4, 100, "cpuBound");

		final ScheduledExecutorService service = Executors.newScheduledThreadPool(1);
		int echoServerPort = SocketUtils.findAvailableTcpPort();
		TcpClientTests.EchoServer echoServer = new TcpClientTests.EchoServer(echoServerPort);
		EventLoopGroup group = new MultiThreadIoEventLoopGroup(1 , NioIoHandler.newFactory());

		try (AddressResolverGroup<?> resolver =
				     NameResolverProvider.builder().build().newNameResolverGroup(TcpResources.get(), true)) {
			final InetSocketAddress address = InetSocketAddress.createUnresolved("localhost", echoServerPort);
			ConnectionProvider pool = ConnectionProvider.create("fixed10connectionsPool", 10);

			Supplier<? extends SocketAddress> remoteAddress = () -> address;
			ConnectionObserver observer = ConnectionObserver.emptyListener();
			DefaultPooledConnectionProviderTest.ClientTransportConfigImpl config =
					new DefaultPooledConnectionProviderTest.ClientTransportConfigImpl(group, pool, Collections.emptyMap(), remoteAddress, resolver);

			//start the echo server
			service.submit(echoServer);
			Thread.sleep(100);

			// simulate first request
			Long startExecutingRequests = System.nanoTime();
			new Thread(() -> {
				pool.acquire(config, observer, remoteAddress, config.resolverInternal())
						.publishOn(cpuBoundPool)
						.doOnNext(connection -> executeBlockingOperation(startExecutingRequests, "id1"))
						.doOnNext(connection -> connection.disposeNow())
						.subscribe();
			}).start();

			// simulate second request
			new Thread(() -> {
				pool.acquire(config, observer, remoteAddress, config.resolverInternal())
						.publishOn(cpuBoundPool)
						.doOnNext(connection -> executeBlockingOperation(startExecutingRequests, "id2"))
						.doOnNext(connection -> connection.disposeNow())
						.subscribe();
			}).start();

			// simulate third request
			new Thread(() -> {
				pool.acquire(config, observer, remoteAddress, config.resolverInternal())
						.publishOn(cpuBoundPool)
						.doOnNext(connection -> executeBlockingOperation(startExecutingRequests, "id3"))
						.doOnNext(connection -> connection.disposeNow())
						.subscribe();
			}).start();

			Thread.sleep(10000);
		}
		finally {
			service.shutdownNow();
			echoServer.close();
			group.shutdownGracefully()
					.get(10, TimeUnit.SECONDS);
		}
	}

	public void executeBlockingOperation(Long start, String operationId) {
		try {
			// some cpu bound operation, maybe simple deserializing and then serializing response etc
			// in order to allow Thread.sleep(), blockhound was disabled
			Thread.sleep(3000);
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		} finally {
			Duration took =  Duration.ofNanos(System.nanoTime() - start);
			System.out.println("WAITING TEST ### Blocking operation with id: " + operationId + " took: " + took);
		}
	}


}
