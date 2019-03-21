/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

import java.util.Objects;

import io.netty.bootstrap.Bootstrap;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;

/**
 * @author Stephane Maldini
 */
abstract class UdpServerOperator extends UdpServer {

	final UdpServer source;

	UdpServerOperator(UdpServer source) {
		this.source = Objects.requireNonNull(source, "source");
	}

	@Override
	protected Bootstrap configure() {
		return source.configure();
	}

	@Override
	protected Mono<? extends Connection> bind(Bootstrap b) {
		return source.bind(b);
	}
}
