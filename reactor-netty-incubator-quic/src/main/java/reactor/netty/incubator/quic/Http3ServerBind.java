/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.incubator.quic;

import io.netty.channel.ChannelOption;
import io.netty.util.NetUtil;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;

import java.net.InetSocketAddress;
import java.util.Collections;

public class Http3ServerBind extends QuicServer implements QuicServerConfigValidations, QuicServerConnectionProvider {


	static final Http3ServerBind INSTANCE = new Http3ServerBind();

	final Http3ServerConfig config;

	Http3ServerBind(Http3ServerConfig config) {
		this.config = config;
	}

	public Http3ServerBind() {
		this.config = new Http3ServerConfig(Collections.emptyMap(),
				Collections.singletonMap(ChannelOption.AUTO_READ, false),
				() -> new InetSocketAddress(NetUtil.LOCALHOST, 0));
	}

	@Override
	public AbstractQuicServerConfig configuration() {
		return config;
	}

	@Override
	protected QuicServer duplicate() {
		return new Http3ServerBind(new Http3ServerConfig(config));
	}

	@Override
	public Mono<? extends Connection> bind() {
		validate(config);
		return getConnection(config);
	}
}
