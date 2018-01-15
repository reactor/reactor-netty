/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty;

import javax.annotation.Nullable;

import io.netty.channel.Channel;
import reactor.util.context.Context;

/**
 * Event listeners for connection lifecycle.
 *
 * A normal event cycle is as follow :
 * <ol>
 *     <li>onSetup</li>
 *     <li>onStart?</li>
 *     <li>onReceiveError?</li>
 *     <li>onDispose</li>
 * </ol>
 *
 * @author Stephane Maldini
 * @since 0.8
 */
public interface ConnectionEvents {

	/**
	 * Return a noop connection listener
	 *
	 * @return a noop connection listener
	 */
	static ConnectionEvents emptyListener(){
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
	 * React on remote channel resource cleanup
	 *
	 * @param channel the remote channel
	 */
	void onDispose(Channel channel);

	/**
	 * React on remote channel read fatal error
	 *
	 * @param channel the remote channel
	 * @param error the failing cause
	 */
	void onReceiveError(Channel channel, Throwable error);

	/**
	 * React on remote channel connection setup eventually given an initial packet (e.g.
	 * {@link io.netty.handler.codec.http.HttpRequest} for http servers).
	 *
	 * @param channel the remote channel
	 * @param msg an optional initial decoded message
	 */
	void onSetup(Channel channel, @Nullable Object msg);

	/**
	 * React after remote channel connection setup when promoted to {@link Connection}
	 *
	 * @param connection the active {@link Connection}
	 */
	void onStart(Connection connection);
}