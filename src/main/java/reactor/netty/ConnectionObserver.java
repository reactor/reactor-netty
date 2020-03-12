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

package reactor.netty;

import reactor.util.context.Context;

/**
 * Event listeners for connection lifecycle.
 *
 * A normal event cycle is as follow :
 * <ol>
 *     <li>onUncaughtException</li>
 *     <li>onStateChange</li>
 * </ol>
 *
 * @author Stephane Maldini
 * @since 0.8
 */
@FunctionalInterface
public interface ConnectionObserver {

	/**
	 * Return a noop connection listener
	 *
	 * @return a noop connection listener
	 */
	static ConnectionObserver emptyListener(){
		return ReactorNetty.NOOP_LISTENER;
	}

	/**
	 * Connection listener {@link Context}
	 *
	 * @return current {@link Context} or {@link Context#empty()}
	 */
	default Context currentContext(){
		return Context.empty();
	}

	/**
	 * React on connection fatal error, will request a disconnecting state
	 * change by default. It should only catch exceptions that can't be consumed by a
	 * {@link NettyInbound#receive} subscriber.
	 *
	 * @param connection the remote connection
	 * @param error the failing cause
	 */
	default void onUncaughtException(Connection connection, Throwable error) {
		onStateChange(connection, State.DISCONNECTING);
	}

	/**
	 * React on connection state change (e.g. http request or response)
	 *
	 * @param connection the connection reference
	 * @param newState the new State
	 */
	void onStateChange(Connection connection, State newState);

	/**
	 * Chain together another {@link ConnectionObserver}
	 *
	 * @param other the next {@link ConnectionObserver}
	 *
	 * @return a new composite {@link ConnectionObserver}
	 */
	default ConnectionObserver then(ConnectionObserver other) {
		return ReactorNetty.compositeConnectionObserver(this, other);
	}

	/**
	 * A marker interface for various state signals used in {@link #onStateChange(Connection, State)}
	 * <p>
	 *     Specific protocol might implement more state type for instance
	 *     request/response lifecycle.
	 */
	interface State {

		/**
		 * Propagated when a connection has been established and is available
		 */
		State CONNECTED = ReactorNetty.CONNECTED;

		/**
		 * Propagated when a connection is bound to a channelOperation and ready for
		 * user interaction
		 */
		State CONFIGURED = ReactorNetty.CONFIGURED;

		/**
		 * Propagated when a connection has been reused / acquired
		 * (keep-alive or pooling)
		 */
		State ACQUIRED = ReactorNetty.ACQUIRED;

		/**
		 * Propagated when a connection has been released but not fully closed
		 * (keep-alive or pooling)
		 */
		State RELEASED = ReactorNetty.RELEASED;

		/**
		 * Propagated when a connection is being fully closed
		 */
		State DISCONNECTING = ReactorNetty.DISCONNECTING;
	}
}