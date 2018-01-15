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

import io.netty.bootstrap.ServerBootstrap;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.DisposableServer;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * @author Stephane Maldini
 */
final class TcpServerLifecycle extends TcpServerOperator implements Consumer<DisposableServer> {

	final Consumer<? super ServerBootstrap>       onBind;
	final Consumer<? super DisposableServer>      onBound;
	final Consumer<? super DisposableServer>      onUnbound;

	TcpServerLifecycle(TcpServer server,
			@Nullable Consumer<? super ServerBootstrap> onBind,
			@Nullable Consumer<? super DisposableServer> onBound,
			@Nullable Consumer<? super DisposableServer> onUnbound) {
		super(server);
		this.onBind = onBind;
		this.onBound = onBound;
		this.onUnbound = onUnbound;
	}

	@Override
	public Mono<? extends DisposableServer> bind(ServerBootstrap b) {
		Mono<? extends DisposableServer> m = source.bind(b);

		if (onBind != null) {
			m = m.doOnSubscribe(s -> onBind.accept(b));
		}

		if (onBound != null) {
			m = m.doOnNext(this);
		}

		if (onUnbound != null) {
			m = m.doOnNext(c -> c.onDispose(() -> onUnbound.accept(c)));
		}

		return m;
	}

	@Override
	public void accept(DisposableServer o) {
		if (onBound != null) {
			onBound.accept(o);
		}
	}
}
