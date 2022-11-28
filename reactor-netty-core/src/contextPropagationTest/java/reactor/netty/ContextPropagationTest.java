/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpServer;
import reactor.test.StepVerifier;

import java.nio.charset.Charset;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ContextPropagationTest {

	@Test
	void testObservationKey() {
		assertThat(Metrics.OBSERVATION_KEY).isEqualTo(ObservationThreadLocalAccessor.KEY);
	}

	@Test
	void testContextPropagation() {
		DisposableServer disposableServer =
				TcpServer.create()
				         .wiretap(true)
				         .handle((in, out) -> out.send(in.receive().retain()))
				         .bindNow();

		ContextRegistry registry = ContextRegistry.getInstance();
		try {
			registry.registerThreadLocalAccessor(new TestThreadLocalAccessor());
			TestThreadLocalHolder.value("First");

			Connection connection =
					TcpClient.create()
					         .port(disposableServer.port())
					         .wiretap(true)
					         .connect()
					         .contextWrite(ctx -> ContextSnapshot.captureAll(registry).updateContext(ctx))
					         .block();

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
			registry.removeThreadLocalAccessor(TestThreadLocalAccessor.KEY);
			disposableServer.disposeNow();
		}
	}

	static final class TestChannelOutboundHandler extends ChannelOutboundHandlerAdapter {

		static final ChannelHandler INSTANCE = new TestChannelOutboundHandler();

		@Override
		public boolean isSharable() {
			return true;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			TestThreadLocalHolder.value("Second");
			if (msg instanceof ByteBuf) {
				ByteBuf buffer1;
				try (ContextSnapshot.Scope scope = ContextSnapshot.captureFrom(ctx.channel()).setThreadLocals()) {
					buffer1 = Unpooled.wrappedBuffer(TestThreadLocalHolder.value().getBytes(Charset.defaultCharset()));
				}
				ByteBuf buffer2 = Unpooled.wrappedBuffer(TestThreadLocalHolder.value().getBytes(Charset.defaultCharset()));
				//"FutureReturnValueIgnored" this is deliberate
				ctx.write(ctx.alloc().compositeBuffer()
						.addComponents(true, (ByteBuf) msg, buffer1, buffer2), promise);
			}
			else {
				//"FutureReturnValueIgnored" this is deliberate
				ctx.write(msg, promise);
			}
		}
	}
}
