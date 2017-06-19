package reactor.ipc.netty.tcp;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public class NettyContextFacadeTest {

	static final NettyContext NEVER_STOP_CONTEXT = new NettyContext() {
		@Override
		public Channel channel() {
			return new EmbeddedChannel();
		}

		@Override
		public InetSocketAddress address() {
			return InetSocketAddress.createUnresolved("localhost", 4321);
		}

		@Override
		public Mono<Void> onClose() {
			return Mono.never();
		}
	};

	@Test
	public void simpleServerFromAsyncServer() throws InterruptedException {
		NettyContextFacade simpleServer =
				TcpServer.create()
				         .startSimple((in, out) -> out
						         .options(NettyPipeline.SendOptions::flushOnEach)
						         .sendString(
								         in.receive()
								           .asString()
								           .takeUntil(s -> s.endsWith("CONTROL"))
								           .map(s -> "ECHO: " + s.replaceAll("CONTROL", ""))
								           .concatWith(Mono.just("DONE"))
						         )
						         .neverComplete()
				         );

		System.out.println(simpleServer.getHost());
		System.out.println(simpleServer.getPort());

		AtomicReference<List<String>> data1 = new AtomicReference<>();
		AtomicReference<List<String>> data2 = new AtomicReference<>();

		NettyContextFacade simpleClient1 =
				TcpClient.create(simpleServer.getPort())
				         .startSimple((in, out) -> {
					         return out.options(NettyPipeline.SendOptions::flushOnEach)
					                   .sendString(Flux.just("Hello", "World", "CONTROL"))
					                   .then(in.receive()
					                           .asString()
					                           .takeUntil(s -> s.endsWith("DONE"))
					                           .map(s -> s.replaceAll("DONE", ""))
					                           .filter(s -> !s.isEmpty())
					                           .collectList()
					                           .doOnNext(data1::set)
					                           .doOnNext(System.err::println)
					                           .then());
				         });

		NettyContextFacade simpleClient2 =
				TcpClient.create(simpleServer.getPort())
				         .startSimple((in, out) -> {
					         return out.options(NettyPipeline.SendOptions::flushOnEach)
					                   .sendString(Flux.just("How", "Are", "You?", "CONTROL"))
					                   .then(in.receive()
					                           .asString()
					                           .takeUntil(s -> s.endsWith("DONE"))
					                           .map(s -> s.replaceAll("DONE", ""))
					                           .filter(s -> !s.isEmpty())
					                           .collectList()
					                           .doOnNext(data2::set)
					                           .doOnNext(System.err::println)
					                           .then());
				         });

		Thread.sleep(1000);
		System.err.println("STOPPING 1");
		simpleClient1.stop();

		System.err.println("STOPPING 2");
		simpleClient2.stop();

		System.err.println("STOPPING SERVER");
		simpleServer.stop();

		assertThat(data1.get())
				.allSatisfy(s -> assertThat(s).startsWith("ECHO: "));
		assertThat(data2.get())
				.allSatisfy(s -> assertThat(s).startsWith("ECHO: "));

		assertThat(data1.get()
		                .toString()
		                .replaceAll("ECHO: ", "")
		                .replaceAll(", ", ""))
				.isEqualTo("[HelloWorld]");
		assertThat(data2.get()
		                .toString()
		                .replaceAll("ECHO: ", "")
		                .replaceAll(", ", ""))
		.isEqualTo("[HowAreYou?]");
	}

	@Test
	public void testTimeoutOnStart() {
		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(() -> new NettyContextFacade(Mono.never(), "TEST NEVER START", Duration.ofMillis(100)))
				.withCauseExactlyInstanceOf(TimeoutException.class)
				.withMessage("java.util.concurrent.TimeoutException: TEST NEVER START couldn't be started within 100ms");
	}

	@Test
	public void testTimeoutOnStop() {
		final NettyContextFacade neverStop =
				new NettyContextFacade(Mono.just(NEVER_STOP_CONTEXT), "TEST NEVER STOP", Duration.ofMillis(100));

		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(neverStop::stop)
				.withCauseExactlyInstanceOf(TimeoutException.class)
				.withMessage("java.util.concurrent.TimeoutException: TEST NEVER STOP couldn't be stopped within 100ms");
	}

	@Test
	public void testTimeoutOnStopChangedTimeout() {
		final NettyContextFacade neverStop =
				new NettyContextFacade(Mono.just(NEVER_STOP_CONTEXT), "TEST NEVER STOP", Duration.ofMillis(500));

		neverStop.setLifecycleTimeout(Duration.ofMillis(100));

		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(neverStop::stop)
				.withCauseExactlyInstanceOf(TimeoutException.class)
				.withMessage("java.util.concurrent.TimeoutException: TEST NEVER STOP couldn't be stopped within 100ms");
	}

	@Test
	public void getContextAddressAndHost() {
		NettyContextFacade facade = new NettyContextFacade(Mono.just(NEVER_STOP_CONTEXT), "foo");

		assertThat(facade.getContext()).isSameAs(NEVER_STOP_CONTEXT);
		assertThat(facade.getPort()).isEqualTo(NEVER_STOP_CONTEXT.address().getPort());
		assertThat(facade.getHost()).isEqualTo(NEVER_STOP_CONTEXT.address().getHostString());
	}
}