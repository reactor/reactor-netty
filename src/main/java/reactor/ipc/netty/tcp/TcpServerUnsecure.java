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

package reactor.ipc.netty.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.handler.ssl.SslContext;

import javax.annotation.Nullable;

/**
 * @author Stephane Maldini
 */
final class TcpServerUnsecure extends TcpServerOperator {

	TcpServerUnsecure(TcpServer server) {
		super(server);
	}

	@Override
	public ServerBootstrap configure() {
		return TcpUtils.removeSslSupport(source.configure());
	}

	@Override
	@Nullable
	public SslContext sslContext(){
		return null;
	}
}
