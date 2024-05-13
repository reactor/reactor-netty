/*
 * Copyright (c) 2022-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.resources.ConnectionProvider;
import reactor.netty5.tcp.TcpClient;
import reactor.netty5.tcp.TcpServer;
import reactor.test.StepVerifier;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty5.ReactorNetty.getChannelContext;

class ContextPropagationTest {
	static final ConnectionProvider provider = ConnectionProvider.create("testContextPropagation", 1);

	@AfterAll
	static void disposePool() {
		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	void testObservationKey() {
		assertThat(Metrics.OBSERVATION_KEY).isEqualTo(ObservationThreadLocalAccessor.KEY);
	}

	@ParameterizedTest
	@MethodSource("tcpClientCombinations")
	void testContextPropagation(TcpClient client) {
		DisposableServer disposableServer =
				TcpServer.create()
				         .wiretap(true)
				         .handle((in, out) -> out.send(in.receive().transferOwnership()))
				         .bindNow();

		ContextRegistry registry = ContextRegistry.getInstance();
		Connection connection = null;
		try {
			registry.registerThreadLocalAccessor(new TestThreadLocalAccessor());
			TestThreadLocalHolder.value("First");

			connection =
					client.port(disposableServer.port())
					      .wiretap(true)
					      .connect()
					      .contextWrite(ctx -> ContextSnapshot.captureAll(registry).updateContext(ctx))
					      .block(Duration.ofSeconds(5));

			assertThat(connection).isNotNull();

			connection.outbound()
			          .withConnection(conn -> conn.addHandlerLast(TestChannelOutboundHandler.INSTANCE))
			          .sendString(Mono.just("Test"))
			          .then()
			          .subscribe();

			connection.inbound()
			          .receive()
			          .asString()
			          .take(1)
			          .as(StepVerifier::create)
			          .expectNext("TestFirstSecond")
			          .expectComplete()
			          .verify(Duration.ofSeconds(5));
		}
		finally {
			if (connection != null) {
				connection.disposeNow();
			}
			TestThreadLocalHolder.reset();
			registry.removeThreadLocalAccessor(TestThreadLocalAccessor.KEY);
			disposableServer.disposeNow();
		}
	}

	static Object[] tcpClientCombinations() {
		return new Object[] {
				TcpClient.create(),
				TcpClient.newConnection(),
				TcpClient.create(provider)
		};
	}

	static final class TestChannelOutboundHandler extends ChannelHandlerAdapter {

		static final ChannelHandler INSTANCE = new TestChannelOutboundHandler();

		@Override
		public boolean isSharable() {
			return true;
		}

		@Override
		@SuppressWarnings("try")
		public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
			try {
				ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
				if (ops != null && ops.currentContext() == getChannelContext(ctx.channel())) {
					TestThreadLocalHolder.value("Second");
				}
				else {
					TestThreadLocalHolder.value("Error");
				}
				if (msg instanceof Buffer buffer) {
					Buffer buffer1;
					try (ContextSnapshot.Scope scope = ContextSnapshot.setAllThreadLocalsFrom(ctx.channel())) {
						buffer1 = ctx.bufferAllocator().copyOf(TestThreadLocalHolder.value(), Charset.defaultCharset());
					}
					Buffer buffer2 = ctx.bufferAllocator().copyOf(TestThreadLocalHolder.value(), Charset.defaultCharset());
					return ctx.write(ctx.bufferAllocator().compose(List.of(buffer.send(), buffer1.send(), buffer2.send())));
				}
				else {
					return ctx.write(msg);
				}
			}
			finally {
				TestThreadLocalHolder.reset();
			}
		}
	}
}
