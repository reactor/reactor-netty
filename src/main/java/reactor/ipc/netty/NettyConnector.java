/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

import java.util.function.BiFunction;

import io.netty.buffer.ByteBuf;
import org.reactivestreams.Publisher;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.connector.Connector;
import reactor.ipc.netty.channel.NettyOperations;

/**
 * @param <INBOUND> incoming traffic API such as server request or client response
 * @param <OUTBOUND> outgoing traffic API such as server response or client request
 * @author Stephane Maldini
 * @since 0.6
 */
public interface NettyConnector<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
		extends Connector<ByteBuf, ByteBuf, INBOUND, OUTBOUND> {

	/**
	 *
	 */
	int DEFAULT_PORT =
			System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) :
					12012;

	/**
	 *
	 */
	int DEFAULT_IO_THREAD_COUNT = Integer.parseInt(System.getProperty(
			"reactor.tcp.ioThreadCount",
			"" + Schedulers.DEFAULT_POOL_SIZE / 2));

	/**
	 *
	 */
	int DEFAULT_TCP_SELECT_COUNT = Integer.parseInt(System.getProperty(
			"reactor.tcp.selectThreadCount",
			"" + DEFAULT_IO_THREAD_COUNT));
}
