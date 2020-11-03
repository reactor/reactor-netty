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

package reactor.netty.transport;

import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import reactor.netty.DisposableServer;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpServer;

import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class ClientTransportResolverHooksTest {

	private static final AttributeKey<Long> TRACE_ID_KEY = AttributeKey.newInstance("traceid");
	private static final long TRACE_ID_VALUE = 125L;

	@Test
	public void shouldCallHooksInSuccessScenario() {

		final AtomicLong doOnResolve = new AtomicLong(0);
		final AtomicLong doAfterResolve = new AtomicLong(0);
		final AtomicLong doOnResolveError = new AtomicLong(0);

		final DisposableServer server = TcpServer.create().port(0).bindNow();

		TcpClient.create()
				.doOnChannelInit((connectionObserver, channel, remoteAddress) -> {
					channel.attr(TRACE_ID_KEY).set(TRACE_ID_VALUE);
				})
				.host("localhost")
				.port(server.port())
				.doOnResolve(ch -> doOnResolve.set(ch.attr(TRACE_ID_KEY).get()))
				.doAfterResolve((ch, socketAddress) -> doAfterResolve.set(ch.attr(TRACE_ID_KEY).get()))
				.doOnResolveError((ch, th) -> doOnResolveError.set(ch.attr(TRACE_ID_KEY).get()))
				.connect()
				.block();

		assertThat(doOnResolve.get()).isEqualTo(TRACE_ID_VALUE);
		assertThat(doAfterResolve.get()).isEqualTo(TRACE_ID_VALUE);
		assertThat(doOnResolveError.get()).isEqualTo(0);
	}

	@Test
	public void shouldCallHooksInErrorScenario() {

		final AtomicLong doOnResolve = new AtomicLong(0);
		final AtomicLong doAfterResolve = new AtomicLong(0);
		final AtomicLong doOnResolveError = new AtomicLong(0);
		final AtomicReference<Throwable> throwable = new AtomicReference<>();

		final DisposableServer server = TcpServer.create().port(0).bindNow();

		try {
			TcpClient.create()
					.doOnChannelInit((connectionObserver, channel, remoteAddress) -> {
						channel.attr(TRACE_ID_KEY).set(TRACE_ID_VALUE);
					})
					.host("idontexist")
					.port(server.port())
					.doOnResolve(ch -> doOnResolve.set(ch.attr(TRACE_ID_KEY).get()))
					.doAfterResolve((ch, socketAddress) -> doAfterResolve.set(ch.attr(TRACE_ID_KEY).get()))
					.doOnResolveError((ch, th) -> {
						doOnResolveError.set(ch.attr(TRACE_ID_KEY).get());
						throwable.set(th);
					})
					.connect()
					.block();
		} catch (Exception ex) {
			// This is expected.  Ignore.
		}
		finally {
			assertThat(doOnResolve.get()).isEqualTo(TRACE_ID_VALUE);
			assertThat(doAfterResolve.get()).isEqualTo(0);
			assertThat(doOnResolveError.get()).isEqualTo(TRACE_ID_VALUE);
			assertThat(throwable.get()).isInstanceOf(UnknownHostException.class);
		}
	}
}
