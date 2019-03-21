/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty;

import io.netty.buffer.ByteBuf;
import reactor.ipc.connector.Connector;

/**
 * A Netty {@link Connector}
 * @param <INBOUND> incoming traffic API such as server request or client response
 * @param <OUTBOUND> outgoing traffic API such as server response or client request
 * @author Stephane Maldini
 * @since 0.6
 */
public interface NettyConnector<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
		extends Connector<ByteBuf, ByteBuf, INBOUND, OUTBOUND> {

}
