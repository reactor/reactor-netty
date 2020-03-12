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
package reactor.netty.udp;

import java.util.function.Consumer;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.BootstrapHandlers;

/**
 * @author Stephane Maldini
 */
final class UdpServerDoOn extends UdpServerOperator implements ConnectionObserver {

	final Consumer<? super Bootstrap>  onBind;
	final Consumer<? super Connection> onBound;
	final Consumer<? super Connection> onUnbound;

	UdpServerDoOn(UdpServer server,
			@Nullable Consumer<? super Bootstrap> onBind,
			@Nullable Consumer<? super Connection> onBound,
			@Nullable Consumer<? super Connection> onUnbound) {
		super(server);
		this.onBind = onBind;
		this.onBound = onBound;
		this.onUnbound = onUnbound;
	}

	@Override
	public Bootstrap configure() {
		Bootstrap b = source.configure();
		ConnectionObserver observer = BootstrapHandlers.connectionObserver(b);
		BootstrapHandlers.connectionObserver(b, observer.then(this));
		return b;
	}

	@Override
	public Mono<? extends Connection> bind(Bootstrap b) {
		if (onBind != null) {
			return source.bind(b)
			             .doOnSubscribe(s -> onBind.accept(b));
		}
		return source.bind(b);
	}

	@Override
	public void onStateChange(Connection connection, State newState) {
		if (onBound != null && newState == State.CONFIGURED) {
			onBound.accept(connection);
			return;
		}
		if (onUnbound != null && newState == State.DISCONNECTING) {
			connection.onDispose(() -> onUnbound.accept(connection));
		}
	}
}
