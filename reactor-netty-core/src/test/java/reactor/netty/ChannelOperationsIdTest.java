/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpServer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelOperationsIdTest {

	DisposableServer serverConnection;

	Connection clientConnection;

	@AfterEach
	void tearDown() {
		if (serverConnection != null) {
			serverConnection.disposeNow();
		}

		if (clientConnection != null) {
			clientConnection.disposeNow();
		}
	}

	@Test
	void testIds() throws Exception {
		CountDownLatch latch = new CountDownLatch(2);

		AtomicReference<String> serverOpsShortId = new AtomicReference<>();
		AtomicReference<String> serverChannelShortId = new AtomicReference<>();
		AtomicReference<String> serverOpsLongId = new AtomicReference<>();
		AtomicReference<String> serverChannelId = new AtomicReference<>();
		serverConnection =
				TcpServer.create()
				         .wiretap(true)
				         .doOnConnection(conn -> {
				             serverOpsShortId.set(((ChannelOperationsId) conn).asShortText());
				             serverChannelShortId.set(conn.channel().id().asShortText());

				             serverOpsLongId.set(((ChannelOperationsId) conn).asLongText());
				             serverChannelId.set(conn.channel().toString());

				             latch.countDown();
				         })
				         .bindNow();

		AtomicReference<String> clientOpsShortId = new AtomicReference<>();
		AtomicReference<String> clientChannelShortId = new AtomicReference<>();
		AtomicReference<String> clientOpsLongId = new AtomicReference<>();
		AtomicReference<String> clientChannelId = new AtomicReference<>();
		clientConnection =
				TcpClient.create()
				         .port(serverConnection.port())
				         .wiretap(true)
				         .doOnConnected(conn -> {
				             clientOpsShortId.set(((ChannelOperationsId) conn).asShortText());
				             clientChannelShortId.set(conn.channel().id().asShortText());

				             clientOpsLongId.set(((ChannelOperationsId) conn).asLongText());
				             clientChannelId.set(conn.channel().toString());

				             latch.countDown();
				         })
				         .connectNow();

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(serverOpsShortId.get()).isNotNull();
		assertThat(serverChannelShortId.get()).isNotNull()
				.isEqualTo(serverOpsShortId.get());

		int originalChannelIdPrefixLength = "[id: 0x".length();
		assertThat(serverOpsLongId.get()).isNotNull();
		assertThat(serverChannelId.get().substring(originalChannelIdPrefixLength)).isNotNull()
				.isEqualTo(serverOpsLongId.get() + ']');

		assertThat(clientOpsShortId.get()).isNotNull();
		assertThat(clientChannelShortId.get()).isNotNull()
				.isEqualTo(clientOpsShortId.get());

		assertThat(clientOpsLongId.get()).isNotNull();
		assertThat(clientChannelId.get().substring(originalChannelIdPrefixLength)).isNotNull()
				.isEqualTo(clientOpsLongId.get() + ']');
	}
}
