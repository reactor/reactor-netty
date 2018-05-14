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
package reactor.ipc.netty.udp;

import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.util.context.Context;

/**
 * @author Stephane Maldini
 */
final class UdpObserver implements ConnectionObserver {

	final MonoSink<Connection> sink;
	final ConnectionObserver obs;

	UdpObserver(MonoSink<Connection> sink, ConnectionObserver obs) {
		this.sink = sink;
		this.obs = obs;
	}

	@Override
	public Context currentContext() {
		return sink.currentContext();
	}

	@Override
	public void onUncaughtException(Connection connection, Throwable error) {
		sink.error(error);
	}

	@Override
	public void onStateChange(Connection connection, State newState) {
		if (newState == State.CONFIGURED) {
			sink.success(connection);
		}
		obs.onStateChange(connection, newState);
	}
}
